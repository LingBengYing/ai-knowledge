package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.VideoAvTestFixture;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VideoAvBuildClaim;
import com.evidence.rag.model.domain.VideoAvCompilation;
import com.evidence.rag.model.domain.VideoAvReceipt;
import com.evidence.rag.model.domain.VideoAvRoute;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.repository.VideoAvRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.worker.parser.VideoAvDecoder;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VideoAvLibraryServiceTest {
  private static final Actor OWNER = new Actor("org", "owner");
  @TempDir Path directory;

  @Test
  void rawRegistrationHasNoDecodeProviderOrOldTaskAndBuildIsCompleteIdempotent() {
    var calls = new AtomicInteger();
    var decoded = new AtomicInteger();
    try (var store = new SqliteAuthorityStore(directory);
        var compilation = compilation(decoded)) {
      var service =
          service(
              store,
              compilation,
              (claim, budget) -> {
                calls.incrementAndGet();
                return receipt(claim);
              });
      var uploaded = service.upload(OWNER, "clip.mp4", "application/octet-stream", raw());
      assertEquals(0, decoded.get());
      assertEquals(0, calls.get());
      assertNull(service.get(OWNER, uploaded.documentId()).publication());
      assertEquals(0, scalar("SELECT COUNT(*) FROM ingestion_jobs"));
      assertEquals(0, scalar("SELECT COUNT(*) FROM indexing_jobs"));
      var publication = service.build(OWNER, uploaded.documentId()).publication();
      assertEquals(3, publication.windows().size());
      assertEquals(2, publication.videoWindowCount());
      assertEquals(3, publication.audioWindowCount());
      assertEquals(null, publication.windows().getLast().video());
      assertEquals(32001, publication.windows().getLast().audio().endSample());
      assertEquals(publication, service.get(OWNER, uploaded.documentId()).publication());
      assertEquals(publication, service.build(OWNER, uploaded.documentId()).publication());
      assertEquals(1, calls.get());
      assertEquals(1, decoded.get());
      assertEquals(0, scalar("SELECT COUNT(*) FROM active_corpus_publications"));
      assertEquals(3, scalar("SELECT COUNT(*) FROM video_av_windows"));
    }
  }

  @Test
  void reopenReadsPublicationWithoutDecodeOrProvider() {
    String id;
    String publication;
    try (var store = new SqliteAuthorityStore(directory);
        var compilation = compilation(new AtomicInteger())) {
      var service = service(store, compilation, (claim, budget) -> receipt(claim));
      id = service.upload(OWNER, "clip.mp4", "video/mp4", raw()).documentId();
      publication = service.build(OWNER, id).publication().id();
    }
    var decoded = new AtomicInteger();
    try (var store = new SqliteAuthorityStore(directory);
        var compilation = compilation(decoded)) {
      var service =
          service(
              store,
              compilation,
              (claim, budget) -> {
                throw new AssertionError("No provider on reopen");
              });
      assertEquals(publication, service.get(OWNER, id).publication().id());
      assertEquals(publication, service.build(OWNER, id).publication().id());
      assertEquals(0, decoded.get());
    }
  }

  @Test
  void memberWithoutDocumentGrantCanReadAndBuildButForeignWorkspaceCannot() {
    var decoded = new AtomicInteger();
    try (var store = new SqliteAuthorityStore(directory);
        var compilation = compilation(decoded)) {
      var service = service(store, compilation, (claim, budget) -> receipt(claim));
      var original = service.upload(OWNER, "clip.mp4", "video/mp4", raw());
      var reader = new Actor("org", "reader");
      assertNull(service.get(reader, original.documentId()).publication());
      assertNotNull(service.build(reader, original.documentId()).publication());
      assertThrows(
          ApplicationException.class,
          () -> service.get(new Actor("other", "owner"), original.documentId()));
      assertEquals(1, decoded.get());
    }
  }

  @Test
  void changedTailReceiptCannotPartiallySeal() {
    try (var store = new SqliteAuthorityStore(directory);
        var compilation = compilation(new AtomicInteger())) {
      var service =
          service(
              store,
              compilation,
              (claim, budget) -> {
                var good = receipt(claim);
                var entries = new ArrayList<>(good.entries());
                var tail = entries.getLast();
                entries.set(
                    entries.size() - 1,
                    new VideoAvReceipt.Entry(
                        tail.windowId(),
                        tail.route(),
                        tail.physicalSegmentId(),
                        List.of(0.5, 0.5),
                        tail.entrySha256()));
                return new VideoAvReceipt(entries, good.visualReceipt(), good.audioReceipt());
              });
      String id = service.upload(OWNER, "clip.mp4", "video/mp4", raw()).documentId();
      assertEquals(
          "video_av_index_stale",
          assertThrows(ApplicationException.class, () -> service.build(OWNER, id)).code());
      assertEquals(0, scalar("SELECT COUNT(*) FROM video_av_publications"));
      assertEquals(0, scalar("SELECT COUNT(*) FROM video_av_windows"));
    }
  }

  @Test
  void failureRetryUsesFreshGenerationAndDoesNotActivateFailedWrite() {
    var generations = new ArrayList<String>();
    try (var store = new SqliteAuthorityStore(directory);
        var compilation = compilation(new AtomicInteger())) {
      var service =
          service(
              store,
              compilation,
              (claim, budget) -> {
                generations.add(claim.generationId());
                if (generations.size() == 1) {
                  throw new IllegalStateException("synthetic failure");
                }
                return receipt(claim);
              });
      String id = service.upload(OWNER, "clip.mp4", "video/mp4", raw()).documentId();
      assertEquals(
          "video_av_index_unavailable",
          assertThrows(ApplicationException.class, () -> service.build(OWNER, id)).code());
      assertNull(service.get(OWNER, id).publication());
      assertEquals(0, scalar("SELECT COUNT(*) FROM video_av_windows"));
      assertEquals(3, service.build(OWNER, id).publication().windows().size());
      assertNotEquals(generations.get(0), generations.get(1));
    }
  }

  @Test
  void historicalAclRemovalDoesNotPreventWorkspaceWorkerCommit() {
    try (var store = new SqliteAuthorityStore(directory);
        var compilation = compilation(new AtomicInteger())) {
      var service =
          service(
              store,
              compilation,
              (claim, budget) -> {
                sql(
                    "DELETE FROM document_acl WHERE document_id='"
                        + claim.original().documentId()
                        + "'");
                return receipt(claim);
              });
      String id = service.upload(OWNER, "clip.mp4", "video/mp4", raw()).documentId();
      assertNotNull(service.build(OWNER, id).publication());
      assertEquals(1, scalar("SELECT COUNT(*) FROM video_av_publications"));
    }
  }

  @Test
  void oneConfiguredPermitAndSameDocumentAdmissionRemainBoundedAndReusable() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var store = new SqliteAuthorityStore(directory);
        var compilation = compilation(new AtomicInteger())) {
      var service =
          service(
              store,
              compilation,
              (claim, budget) -> {
                entered.countDown();
                try {
                  if (!release.await(3, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Fixture deadline");
                  }
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException(interrupted);
                }
                return receipt(claim);
              },
              1);
      String first = service.upload(OWNER, "first.mp4", "video/mp4", raw()).documentId();
      String second = service.upload(OWNER, "second.mp4", "video/mp4", raw()).documentId();
      var running = new FutureTask<>(() -> service.build(OWNER, first));
      Thread.ofVirtual().start(running);
      try {
        org.junit.jupiter.api.Assertions.assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertEquals(
            "video_av_index_busy",
            assertThrows(ApplicationException.class, () -> service.build(OWNER, first)).code());
        assertEquals(
            "video_av_index_busy",
            assertThrows(ApplicationException.class, () -> service.build(OWNER, second)).code());
      } finally {
        release.countDown();
      }
      assertEquals(3, running.get(3, TimeUnit.SECONDS).publication().windows().size());
      assertEquals(3, service.build(OWNER, second).publication().windows().size());
    }
  }

  @Test
  void concurrencyAboveTwoIsRejectedWithoutDecodeOrProvider() {
    var decoded = new AtomicInteger();
    try (var store = new SqliteAuthorityStore(directory);
        var compilation = compilation(decoded)) {
      for (int concurrency : new int[] {0, 3, 8}) {
        assertThrows(
            ApplicationException.class,
            () ->
                service(
                    store,
                    compilation,
                    (claim, budget) -> {
                      throw new AssertionError("No provider during invalid configuration");
                    },
                    concurrency));
      }
      assertEquals(0, decoded.get());
    }
  }

  private static VideoAvCompilationService compilation(AtomicInteger calls) {
    return new VideoAvCompilationService(
        new VideoAvDecoder() {
          public String revision() {
            return "decoder-v1";
          }

          public VideoAvCompilation decode(String filename, String mime, byte[] source) {
            calls.incrementAndGet();
            var original =
                new com.evidence.rag.model.domain.DocumentOriginal(
                    "doc",
                    "rev",
                    filename,
                    "video",
                    mime,
                    ModelValues.sha256(source),
                    source.length,
                    source);
            return VideoAvTestFixture.compilation(original, true);
          }

          public void close() {}
        },
        1,
        Duration.ofSeconds(5));
  }

  private static VideoAvLibraryService service(
      SqliteAuthorityStore store,
      VideoAvCompilationService compilation,
      BiFunction<VideoAvBuildClaim, Duration, VideoAvReceipt> indexer) {
    return service(store, compilation, indexer, 2);
  }

  private static VideoAvLibraryService service(
      SqliteAuthorityStore store,
      VideoAvCompilationService compilation,
      BiFunction<VideoAvBuildClaim, Duration, VideoAvReceipt> indexer,
      int concurrency) {
    return new VideoAvLibraryService(
        store,
        new VideoAvRepository(store),
        new ManagementRepository(store),
        new DocumentPermissionPolicy(),
        compilation,
        VideoAvTestFixture.targets(),
        "analysis-v1",
        VideoAvTestFixture.models(),
        VideoAvTestFixture.projection(VideoAvRoute.VISUAL),
        VideoAvTestFixture.projection(VideoAvRoute.AUDIO),
        Duration.ofSeconds(5),
        concurrency,
        indexer);
  }

  private static VideoAvReceipt receipt(VideoAvBuildClaim claim) {
    return VideoAvTestFixture.receipt(claim);
  }

  private static byte[] raw() {
    return VideoAvTestFixture.raw();
  }

  private long scalar(String sql) {
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getLong(1);
    } catch (java.sql.SQLException error) {
      throw new IllegalStateException(error);
    }
  }

  private void sql(String sql) {
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement()) {
      statement.execute(sql);
    } catch (java.sql.SQLException error) {
      throw new IllegalStateException(error);
    }
  }
}
