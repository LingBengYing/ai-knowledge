package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.VideoAvTestFixture;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VideoAvBuildClaim;
import com.evidence.rag.model.domain.VideoAvCompilation;
import com.evidence.rag.model.domain.VideoAvReceipt;
import com.evidence.rag.model.domain.VideoAvRoute;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.repository.VideoAvRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.worker.indexing.ProcessVideoAvIndexer;
import com.evidence.rag.worker.parser.VideoAvDecoder;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VideoAvLibraryBoundaryTest {
  @TempDir Path directory;

  @Test
  void deadlineInterruptsAndJoinsWorkerBeforePermitCanBeReused() throws Exception {
    var entered = new CountDownLatch(1);
    var stopped = new CountDownLatch(1);
    var calls = new AtomicInteger();
    try (var store = new SqliteAuthorityStore(directory);
        var compiler = compiler(new AtomicReference<>("decoder-v1"), true)) {
      var library =
          library(
              store,
              compiler,
              Duration.ofSeconds(1),
              (claim, budget) -> {
                if (calls.incrementAndGet() == 1) {
                  entered.countDown();
                  waitUntilInterrupted(stopped);
                }
                return VideoAvTestFixture.receipt(claim);
              });
      String id = upload(library);
      var running = new FutureTask<>(() -> failure(library, id));
      Thread.ofVirtual().start(running);
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      assertEquals("video_av_index_timeout:false", running.get(4, TimeUnit.SECONDS));
      assertTrue(stopped.await(1, TimeUnit.SECONDS));
      assertEquals(0, count("video_av_publications"));
      assertEquals(0, count("video_av_windows"));
      assertEquals(3, library.build(VideoAvTestFixture.OWNER, id).publication().windowCount());
    }
  }

  @Test
  void callerInterruptionPreservesFlagAndDoesNotLeaveAnIndexWorkerOrPartialPublication()
      throws Exception {
    var entered = new CountDownLatch(1);
    var stopped = new CountDownLatch(1);
    try (var store = new SqliteAuthorityStore(directory);
        var compiler = compiler(new AtomicReference<>("decoder-v1"), true)) {
      var library =
          library(
              store,
              compiler,
              VideoAvTestFixture.BUDGET,
              (claim, budget) -> {
                entered.countDown();
                waitUntilInterrupted(stopped);
                return VideoAvTestFixture.receipt(claim);
              });
      String id = upload(library);
      var running = new FutureTask<>(() -> failure(library, id));
      var caller = Thread.ofVirtual().start(running);
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      caller.interrupt();
      assertEquals("video_av_index_unavailable:true", running.get(4, TimeUnit.SECONDS));
      assertTrue(stopped.await(1, TimeUnit.SECONDS));
      assertEquals(0, count("video_av_publications"));
      assertNull(library.get(VideoAvTestFixture.OWNER, id).publication());
    }
  }

  @Test
  void closeDuringIndexingCancelsWorkerAndPreventsFurtherSourceAccess() throws Exception {
    var entered = new CountDownLatch(1);
    var stopped = new CountDownLatch(1);
    try (var store = new SqliteAuthorityStore(directory);
        var compiler = compiler(new AtomicReference<>("decoder-v1"), true)) {
      var library =
          library(
              store,
              compiler,
              VideoAvTestFixture.BUDGET,
              (claim, budget) -> {
                entered.countDown();
                waitUntilInterrupted(stopped);
                return VideoAvTestFixture.receipt(claim);
              });
      String id = upload(library);
      var running = new FutureTask<>(() -> failure(library, id));
      Thread.ofVirtual().start(running);
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      library.close();
      assertEquals("video_av_index_stale:false", running.get(4, TimeUnit.SECONDS));
      assertTrue(stopped.await(1, TimeUnit.SECONDS));
      assertFalse(library.configurationCurrent());
      assertEquals(
          "video_av_index_stale",
          assertThrows(ApplicationException.class, () -> library.get(VideoAvTestFixture.OWNER, id))
              .code());
      assertEquals(0, count("video_av_windows"));
    }
  }

  @Test
  void decoderProfileDriftWhileReceiptIsProducedCannotCommit() {
    var revision = new AtomicReference<>("decoder-v1");
    try (var store = new SqliteAuthorityStore(directory);
        var compiler = compiler(revision, true)) {
      var library =
          library(
              store,
              compiler,
              VideoAvTestFixture.BUDGET,
              (claim, budget) -> {
                revision.set("decoder-v2");
                return VideoAvTestFixture.receipt(claim);
              });
      String id = upload(library);
      assertEquals(
          "video_av_index_stale",
          assertThrows(
                  ApplicationException.class, () -> library.build(VideoAvTestFixture.OWNER, id))
              .code());
      assertEquals(0, count("video_av_publications"));
      assertEquals(0, count("video_av_windows"));
    }
  }

  @Test
  void visualOnlyBuildPersistsExplicitAbsenceWithoutAudioEntries() {
    try (var store = new SqliteAuthorityStore(directory);
        var compiler = compiler(new AtomicReference<>("decoder-v1"), false)) {
      var library =
          library(
              store,
              compiler,
              VideoAvTestFixture.BUDGET,
              (claim, budget) -> VideoAvTestFixture.receipt(claim));
      var publication = library.build(VideoAvTestFixture.OWNER, upload(library)).publication();
      assertFalse(publication.hasAudio());
      assertEquals(2, publication.windowCount());
      assertEquals(0, publication.audioWindowCount());
      assertNull(publication.audioReceipt().verified());
      assertTrue(publication.windows().stream().allMatch(window -> window.audio() == null));
      assertEquals(2, count("video_av_windows"));
      assertEquals(
          publication,
          library.get(VideoAvTestFixture.OWNER, publication.documentId()).publication());
    }
  }

  @Test
  void aCurrentPublicationCommittedByAnotherBuilderWinsWithoutDuplicateChildren() {
    var winningGeneration = new AtomicReference<String>();
    try (var store = new SqliteAuthorityStore(directory);
        var compiler = compiler(new AtomicReference<>("decoder-v1"), true)) {
      var library =
          library(
              store,
              compiler,
              VideoAvTestFixture.BUDGET,
              (claim, budget) -> {
                var other =
                    new VideoAvBuildClaim(
                        claim.actor(),
                        claim.original(),
                        claim.targets(),
                        UUID.randomUUID().toString(),
                        claim.analysisModelRevision(),
                        claim.decoderRevision(),
                        claim.chunkSeconds(),
                        claim.compilation(),
                        claim.profileFingerprint());
                winningGeneration.set(other.generationId());
                store.transaction(
                    () -> {
                      new VideoAvRepository(store)
                          .insertPublication(VideoAvTestFixture.publication(other));
                      return null;
                    });
                return VideoAvTestFixture.receipt(claim);
              });
      var publication = library.build(VideoAvTestFixture.OWNER, upload(library)).publication();
      assertEquals(winningGeneration.get(), publication.id());
      assertEquals(1, count("video_av_publications"));
      assertEquals(3, count("video_av_windows"));
    }
  }

  @Test
  void workerTimeoutAndDecoderTimeoutStaySafeAndNeverPersistAReceipt() {
    try (var store = new SqliteAuthorityStore(directory);
        var compiler = compiler(new AtomicReference<>("decoder-v1"), true)) {
      var library =
          library(
              store,
              compiler,
              VideoAvTestFixture.BUDGET,
              (claim, budget) -> {
                throw new ProcessVideoAvIndexer.Failure("video_av_index_timeout");
              });
      String id = upload(library);
      var failure =
          assertThrows(
              ApplicationException.class, () -> library.build(VideoAvTestFixture.OWNER, id));
      assertEquals(FailureKind.TIMEOUT, failure.kind());
      assertEquals("video_av_index_timeout", failure.code());
      assertEquals(0, count("video_av_publications"));
    }
    try (var store = new SqliteAuthorityStore(directory);
        var compiler =
            compiler(
                new AtomicReference<>("decoder-v1"),
                original -> {
                  throw new TextParser.Failure("parser_timeout");
                })) {
      var library =
          library(
              store,
              compiler,
              VideoAvTestFixture.BUDGET,
              (claim, budget) -> {
                throw new AssertionError("Decoder failure must precede provider");
              });
      var failure =
          assertThrows(
              ApplicationException.class,
              () -> library.build(VideoAvTestFixture.OWNER, upload(library)));
      assertEquals(FailureKind.TIMEOUT, failure.kind());
      assertEquals(0, count("video_av_windows"));
    }
  }

  @Test
  void invalidRawEnvelopeHasNoDocumentOrAuditSideEffect() {
    try (var store = new SqliteAuthorityStore(directory);
        var compiler = compiler(new AtomicReference<>("decoder-v1"), true)) {
      var library =
          library(
              store,
              compiler,
              VideoAvTestFixture.BUDGET,
              (claim, budget) -> {
                throw new AssertionError("Invalid upload must not invoke provider");
              });
      assertThrows(
          ApplicationException.class,
          () -> library.upload(VideoAvTestFixture.OWNER, "clip.mp4", "video/mp4", new byte[] {1}));
      assertEquals(0, count("documents"));
      assertEquals(0, count("management_audit"));
    }
  }

  private static VideoAvCompilationService compiler(
      AtomicReference<String> revision, boolean hasAudio) {
    return compiler(revision, original -> VideoAvTestFixture.compilation(original, hasAudio));
  }

  private static VideoAvCompilationService compiler(
      AtomicReference<String> revision, Function<DocumentOriginal, VideoAvCompilation> decode) {
    return new VideoAvCompilationService(
        new VideoAvDecoder() {
          public String revision() {
            return revision.get();
          }

          public VideoAvCompilation decode(String filename, String mime, byte[] bytes) {
            return decode.apply(
                new DocumentOriginal(
                    "doc",
                    "rev",
                    filename,
                    "video",
                    mime,
                    ModelValues.sha256(bytes),
                    bytes.length,
                    bytes));
          }

          public void close() {}
        },
        1,
        VideoAvTestFixture.BUDGET);
  }

  private static VideoAvLibraryService library(
      SqliteAuthorityStore store,
      VideoAvCompilationService compiler,
      Duration budget,
      BiFunction<VideoAvBuildClaim, Duration, VideoAvReceipt> indexer) {
    return new VideoAvLibraryService(
        store,
        new VideoAvRepository(store),
        new ManagementRepository(store),
        new DocumentPermissionPolicy(),
        compiler,
        VideoAvTestFixture.targets(),
        "analysis-v1",
        VideoAvTestFixture.models(),
        VideoAvTestFixture.projection(VideoAvRoute.VISUAL),
        VideoAvTestFixture.projection(VideoAvRoute.AUDIO),
        budget,
        1,
        indexer);
  }

  private static String upload(VideoAvLibraryService library) {
    return library
        .upload(VideoAvTestFixture.OWNER, "clip.mp4", "video/mp4", VideoAvTestFixture.raw())
        .documentId();
  }

  private static String failure(VideoAvLibraryService library, String id) {
    try {
      library.build(VideoAvTestFixture.OWNER, id);
      return "unexpected";
    } catch (ApplicationException failure) {
      return failure.code() + ":" + Thread.currentThread().isInterrupted();
    }
  }

  private static void waitUntilInterrupted(CountDownLatch stopped) {
    try {
      new CountDownLatch(1).await();
      throw new AssertionError("Worker unexpectedly released");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Synthetic cancelled worker");
    } finally {
      stopped.countDown();
    }
  }

  private long count(String table) {
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
      rows.next();
      return rows.getLong(1);
    } catch (java.sql.SQLException failure) {
      throw new IllegalStateException(failure);
    }
  }
}
