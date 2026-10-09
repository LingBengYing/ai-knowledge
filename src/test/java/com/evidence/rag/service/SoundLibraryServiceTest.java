package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.client.model.GeminiSoundEmbeddingModels;
import com.evidence.rag.client.model.GeminiSoundModels;
import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DecodedAudio;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.SoundBuildClaim;
import com.evidence.rag.model.domain.SoundReceipt;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.repository.ImportIndexRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SoundRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.tool.parser.AudioPcm;
import com.evidence.rag.worker.parser.AudioDecoder;
import java.net.URI;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SoundLibraryServiceTest {
  private static final Actor OWNER = new Actor("org", "owner");
  @TempDir Path directory;

  @Test
  void automaticAdmissionUsesTheExistingSoundPipelineOnce() {
    var calls = new AtomicInteger();
    try (var store = new SqliteAuthorityStore(directory);
        var compilation = compilation(new AtomicInteger())) {
      var service =
          service(
              store,
              compilation,
              (claim, budget) -> {
                calls.incrementAndGet();
                return receipt(claim);
              });
      var original = service.upload(OWNER, "tone.wav", "application/octet-stream", raw());
      var automatic =
          new ImportAutoIndexService(
              store,
              () -> {
                throw new AssertionError("not corpus");
              },
              service,
              null);
      assertEquals(true, automatic.processNext());
      assertNotNull(service.get(OWNER, original.documentId()).publication());
      assertEquals(false, automatic.processNext());
      assertEquals(1, calls.get());
      assertEquals(0, scalar("SELECT COUNT(*) FROM indexing_jobs"));
    }
  }

  @Test
  void uncertainNativeDispatchIsNotRepeatedAfterRecovery() {
    try (var store = new SqliteAuthorityStore(directory);
        var compilation = compilation(new AtomicInteger())) {
      var service =
          service(
              store,
              compilation,
              (claim, budget) -> {
                throw new AssertionError("do not retry a possibly billed native dispatch");
              });
      var original = service.upload(OWNER, "tone.wav", "application/octet-stream", raw());
      var requests = new ImportIndexRepository(store);
      store.transaction(() -> requests.claim().orElseThrow());
      var automatic = new ImportAutoIndexService(store, () -> null, service, null);
      assertEquals(false, automatic.processNext());
      assertEquals(
          "indexing_interrupted",
          store.transaction(() -> requests.latest(original.documentId())).errorCode());
      assertNull(service.get(OWNER, original.documentId()).publication());
    }
  }

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
      var uploaded = service.upload(OWNER, "tone.wav", "application/octet-stream", raw());
      assertEquals(0, decoded.get());
      assertEquals(0, calls.get());
      assertNull(service.get(OWNER, uploaded.documentId()).publication());
      assertEquals(0, scalar("SELECT COUNT(*) FROM ingestion_jobs"));
      assertEquals(0, scalar("SELECT COUNT(*) FROM indexing_jobs"));
      var publication = service.build(OWNER, uploaded.documentId()).publication();
      assertEquals(3, publication.spans().size());
      assertEquals("", publication.spans().get(1).recallText());
      assertEquals(32001, publication.sampleCount());
      assertEquals(publication, service.get(OWNER, uploaded.documentId()).publication());
      assertEquals(publication, service.build(OWNER, uploaded.documentId()).publication());
      assertEquals(1, calls.get());
      assertEquals(1, decoded.get());
      assertEquals(0, scalar("SELECT COUNT(*) FROM active_corpus_publications"));
      assertEquals(3, scalar("SELECT COUNT(*) FROM sound_spans"));
    }
  }

  @Test
  void reopenReadsPublicationWithoutDecodeOrProvider() {
    String id;
    String publication;
    try (var store = new SqliteAuthorityStore(directory);
        var compilation = compilation(new AtomicInteger())) {
      var service = service(store, compilation, (claim, budget) -> receipt(claim));
      id = service.upload(OWNER, "tone.wav", "audio/wav", raw()).documentId();
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
      var original = service.upload(OWNER, "tone.wav", "audio/wav", raw());
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
                    new SoundReceipt.Entry(
                        tail.spanId(),
                        tail.physicalSegmentId(),
                        tail.recallText(),
                        List.of(0.5, 0.5),
                        tail.entrySha256()));
                return new SoundReceipt(entries, good.verified());
              });
      String id = service.upload(OWNER, "tone.wav", "audio/wav", raw()).documentId();
      assertEquals(
          "sound_index_stale",
          assertThrows(ApplicationException.class, () -> service.build(OWNER, id)).code());
      assertEquals(0, scalar("SELECT COUNT(*) FROM sound_publications"));
      assertEquals(0, scalar("SELECT COUNT(*) FROM sound_spans"));
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
      String id = service.upload(OWNER, "tone.wav", "audio/wav", raw()).documentId();
      assertEquals(
          "sound_index_unavailable",
          assertThrows(ApplicationException.class, () -> service.build(OWNER, id)).code());
      assertNull(service.get(OWNER, id).publication());
      assertEquals(0, scalar("SELECT COUNT(*) FROM sound_spans"));
      assertEquals(3, service.build(OWNER, id).publication().spans().size());
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
      String id = service.upload(OWNER, "tone.wav", "audio/wav", raw()).documentId();
      assertNotNull(service.build(OWNER, id).publication());
      assertEquals(1, scalar("SELECT COUNT(*) FROM sound_publications"));
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
      String first = service.upload(OWNER, "first.wav", "audio/wav", raw()).documentId();
      String second = service.upload(OWNER, "second.wav", "audio/wav", raw()).documentId();
      var running = new FutureTask<>(() -> service.build(OWNER, first));
      Thread.ofVirtual().start(running);
      try {
        org.junit.jupiter.api.Assertions.assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertEquals(
            "sound_index_busy",
            assertThrows(ApplicationException.class, () -> service.build(OWNER, first)).code());
        assertEquals(
            "sound_index_busy",
            assertThrows(ApplicationException.class, () -> service.build(OWNER, second)).code());
      } finally {
        release.countDown();
      }
      assertEquals(3, running.get(3, TimeUnit.SECONDS).publication().spans().size());
      assertEquals(3, service.build(OWNER, second).publication().spans().size());
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

  private static SoundCompilationService compilation(AtomicInteger calls) {
    return new SoundCompilationService(
        new AudioDecoder() {
          public String revision() {
            return "decoder-v1";
          }

          public DecodedAudio decode(String filename, String mime, byte[] source) {
            calls.incrementAndGet();
            return new DecodedAudio(ModelValues.sha256(source), revision(), new byte[64002]);
          }

          public void close() {}
        },
        1,
        Duration.ofSeconds(5));
  }

  private static SoundLibraryService service(
      SqliteAuthorityStore store,
      SoundCompilationService compilation,
      BiFunction<SoundBuildClaim, Duration, SoundReceipt> indexer) {
    return service(store, compilation, indexer, 2);
  }

  private static SoundLibraryService service(
      SqliteAuthorityStore store,
      SoundCompilationService compilation,
      BiFunction<SoundBuildClaim, Duration, SoundReceipt> indexer,
      int maxConcurrent) {
    var endpoint =
        new OpenAiCompatibleModels.Endpoint(
            URI.create("http://127.0.0.1:1"), "sound", "fixture-key");
    var models =
        new GeminiSoundModels.Configuration(
            endpoint, "sound-v1", Duration.ofSeconds(5), 65536, true);
    var embeddings =
        new GeminiSoundEmbeddingModels.Configuration(
            endpoint, "embedding-v1", 2, "decoder-v1", Duration.ofSeconds(5), 65536, true);
    var projection =
        new MilvusRestProjection.Settings(
            URI.create("http://127.0.0.1:1"),
            "",
            "default",
            "java_sound_fixture",
            "org",
            embeddings.revision(),
            2,
            Duration.ofSeconds(5),
            65536,
            true);
    var target =
        new IndexTarget(embeddings.revision(), projection.identity(), embeddings.revision(), 2);
    return new SoundLibraryService(
        store,
        new SoundRepository(store),
        new ManagementRepository(store),
        new DocumentPermissionPolicy(),
        compilation,
        target,
        models,
        embeddings,
        projection,
        Duration.ofSeconds(5),
        maxConcurrent,
        indexer);
  }

  private static SoundReceipt receipt(SoundBuildClaim claim) {
    var entries = new ArrayList<SoundReceipt.Entry>();
    var digests = new TreeMap<String, String>();
    for (var span : claim.spans()) {
      String physical = RetrievalProjection.physicalSegmentId(claim.generationId(), span.id());
      var entry =
          new RetrievalProjection.Entry(
              physical,
              claim.actor().workspaceId(),
              claim.original().documentId(),
              claim.generationId(),
              span.waveform().pcmSha256(),
              List.of(0.25, 0.75));
      String digest = RetrievalProjection.entryDigest(entry);
      digests.put(physical, digest);
      entries.add(
          new SoundReceipt.Entry(
              span.id(), physical, span.ordinal() == 1 ? "" : "tone", entry.vector(), digest));
    }
    var manifest =
        new RetrievalProjection.RevisionManifest(
            claim.actor().workspaceId(),
            claim.original().documentId(),
            claim.generationId(),
            digests);
    return new SoundReceipt(
        entries,
        new VerifiedRevision(
            claim.target().projectionIdentity(), manifest.sha256(), entries.size()));
  }

  private static byte[] raw() {
    return AudioPcm.wav(new byte[64002], 0, 64002);
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
