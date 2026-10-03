package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.GeminiSoundEmbeddingModels;
import com.evidence.rag.client.model.GeminiSoundModels;
import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DecodedAudio;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.SoundBuildClaim;
import com.evidence.rag.model.domain.SoundReceipt;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SoundRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.tool.parser.AudioPcm;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.worker.indexing.ProcessSoundIndexer;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SoundLibraryServiceBoundaryTest {
  private static final Actor OWNER = new Actor("org", "owner");
  @TempDir Path directory;

  @Test
  void receiptMustContainEveryWindowWithBoundPhysicalVectorAndFinalManifest() {
    var mode = new AtomicInteger();
    try (var store = new SqliteAuthorityStore(directory);
        var compilation = compilation(new AtomicInteger())) {
      var service =
          service(
              store,
              compilation,
              (claim, budget) -> {
                var good = receipt(claim);
                var first = good.entries().getFirst();
                var entries = new ArrayList<>(good.entries());
                switch (mode.get()) {
                  case 0 -> {
                    return null;
                  }
                  case 1 -> {
                    return new SoundReceipt(
                        entries.subList(0, 2),
                        new VerifiedRevision(
                            good.verified().projectionIdentity(),
                            good.verified().manifestSha256(),
                            2));
                  }
                  case 2 ->
                      entries.set(
                          0,
                          new SoundReceipt.Entry(
                              "other-span",
                              first.physicalSegmentId(),
                              first.recallText(),
                              first.vector(),
                              first.entrySha256()));
                  case 3 ->
                      entries.set(
                          0,
                          new SoundReceipt.Entry(
                              first.spanId(),
                              "wrong-physical",
                              first.recallText(),
                              first.vector(),
                              first.entrySha256()));
                  case 4 ->
                      entries.set(
                          0,
                          new SoundReceipt.Entry(
                              first.spanId(),
                              first.physicalSegmentId(),
                              first.recallText(),
                              List.of(0.25, 0.75, 0.5),
                              first.entrySha256()));
                  case 5 -> {
                    return new SoundReceipt(
                        entries,
                        new VerifiedRevision("b".repeat(64), good.verified().manifestSha256(), 3));
                  }
                  case 6 -> {
                    return new SoundReceipt(
                        entries,
                        new VerifiedRevision(
                            good.verified().projectionIdentity(), "c".repeat(64), 3));
                  }
                  default -> {
                    return good;
                  }
                }
                return new SoundReceipt(entries, good.verified());
              });
      String document = service.upload(OWNER, "tone.wav", "audio/wav", raw()).documentId();
      for (int invalid = 0; invalid < 7; invalid++) {
        mode.set(invalid);
        assertEquals(
            "sound_index_stale",
            assertThrows(ApplicationException.class, () -> service.build(OWNER, document)).code());
        assertNull(service.get(OWNER, document).publication());
        assertEquals(0, scalar("SELECT COUNT(*) FROM sound_spans"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM sound_publications"));
      }
      mode.set(7);
      assertEquals(3, service.build(OWNER, document).publication().spans().size());
    }
  }

  @Test
  void workerTimeoutAndUnknownFailuresNeverSealAndRemainRetryable() {
    var count = new AtomicInteger();
    try (var store = new SqliteAuthorityStore(directory);
        var compilation = compilation(new AtomicInteger())) {
      var service =
          service(
              store,
              compilation,
              (claim, budget) -> {
                if (count.getAndIncrement() == 0) {
                  throw new ProcessSoundIndexer.Failure("sound_index_timeout");
                }
                if (count.get() == 2) {
                  throw new ProcessSoundIndexer.Failure("unsafe-provider-error");
                }
                return receipt(claim);
              });
      String document = service.upload(OWNER, "tone.wav", "audio/wav", raw()).documentId();
      var timeout = assertThrows(ApplicationException.class, () -> service.build(OWNER, document));
      assertEquals("sound_index_timeout", timeout.code());
      assertEquals(FailureKind.TIMEOUT, timeout.kind());
      var unknown = assertThrows(ApplicationException.class, () -> service.build(OWNER, document));
      assertEquals("sound_index_unavailable", unknown.code());
      assertFalse(unknown.toString().contains("unsafe-provider-error"));
      assertNull(service.get(OWNER, document).publication());
      assertEquals(3, service.build(OWNER, document).publication().spans().size());
    }
  }

  @Test
  void decoderTimeoutAndInvalidOutputAreSafeAndDoNotCreateRemoteWork() {
    for (String code : List.of("parser_timeout", "parser_invalid_output")) {
      Path path = directory.resolve(code);
      var calls = new AtomicInteger();
      try (var store = new SqliteAuthorityStore(path);
          var compilation =
              new SoundCompilationService(
                  new AudioDecoder() {
                    public String revision() {
                      return "decoder-v1";
                    }

                    public DecodedAudio decode(String filename, String mime, byte[] bytes) {
                      throw new TextParser.Failure(code);
                    }

                    public void close() {}
                  },
                  1,
                  Duration.ofSeconds(5))) {
        var service =
            service(
                store,
                compilation,
                (claim, budget) -> {
                  calls.incrementAndGet();
                  return receipt(claim);
                });
        String document = service.upload(OWNER, "tone.wav", "audio/wav", raw()).documentId();
        assertEquals(
            code.equals("parser_timeout") ? "sound_index_timeout" : "sound_index_unavailable",
            assertThrows(ApplicationException.class, () -> service.build(OWNER, document)).code());
        assertEquals(0, calls.get());
        assertNull(service.get(OWNER, document).publication());
      }
    }
  }

  @Test
  void totalDeadlineInterruptsAndJoinsIndexerBeforeReleasingAdmission() throws Exception {
    var entered = new CountDownLatch(1);
    var stopped = new CountDownLatch(1);
    var calls = new AtomicInteger();
    try (var store = new SqliteAuthorityStore(directory);
        var compilation = compilation(new AtomicInteger())) {
      var service =
          service(
              store,
              compilation,
              (claim, budget) -> {
                if (calls.incrementAndGet() == 1) {
                  entered.countDown();
                  try {
                    new CountDownLatch(1).await();
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                  } finally {
                    stopped.countDown();
                  }
                }
                return receipt(claim);
              },
              1,
              Duration.ofSeconds(1));
      String document = service.upload(OWNER, "tone.wav", "audio/wav", raw()).documentId();
      var build =
          new FutureTask<>(
              () -> assertThrows(ApplicationException.class, () -> service.build(OWNER, document)));
      Thread.ofVirtual().start(build);
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      assertEquals("sound_index_timeout", build.get(3, TimeUnit.SECONDS).code());
      assertEquals(0, stopped.getCount());
      assertNull(service.get(OWNER, document).publication());
      assertEquals(3, service.build(OWNER, document).publication().spans().size());
    }
  }

  @Test
  void callerCancellationPreservesItsFlagAndJoinsWorkerBeforeRetry() throws Exception {
    var entered = new CountDownLatch(1);
    var stopped = new CountDownLatch(1);
    var calls = new AtomicInteger();
    try (var store = new SqliteAuthorityStore(directory);
        var compilation = compilation(new AtomicInteger())) {
      var service =
          service(
              store,
              compilation,
              (claim, budget) -> {
                if (calls.incrementAndGet() == 1) {
                  entered.countDown();
                  try {
                    new CountDownLatch(1).await();
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                  } finally {
                    stopped.countDown();
                  }
                }
                return receipt(claim);
              });
      String document = service.upload(OWNER, "tone.wav", "audio/wav", raw()).documentId();
      var build =
          new FutureTask<>(
              () -> {
                var failure =
                    assertThrows(ApplicationException.class, () -> service.build(OWNER, document));
                assertTrue(Thread.currentThread().isInterrupted());
                return failure.code();
              });
      var caller = Thread.ofVirtual().start(build);
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      caller.interrupt();
      assertEquals("sound_index_unavailable", build.get(3, TimeUnit.SECONDS));
      assertEquals(0, stopped.getCount());
      assertNull(service.get(OWNER, document).publication());
      assertEquals(3, service.build(OWNER, document).publication().spans().size());
    }
  }

  @Test
  void decoderProfileChangeDuringRemoteWorkCancelsCommitAndCanRecover() {
    var revision = new AtomicReference<>("decoder-v1");
    try (var store = new SqliteAuthorityStore(directory);
        var compilation =
            new SoundCompilationService(
                new AudioDecoder() {
                  public String revision() {
                    return revision.get();
                  }

                  public DecodedAudio decode(String filename, String mime, byte[] source) {
                    return new DecodedAudio(
                        ModelValues.sha256(source), revision(), new byte[64002]);
                  }

                  public void close() {}
                },
                1,
                Duration.ofSeconds(5))) {
      var service =
          service(
              store,
              compilation,
              (claim, budget) -> {
                revision.set("decoder-v2");
                return receipt(claim);
              });
      String document = service.upload(OWNER, "tone.wav", "audio/wav", raw()).documentId();
      assertEquals(
          "sound_index_stale",
          assertThrows(ApplicationException.class, () -> service.build(OWNER, document)).code());
      assertFalse(service.configurationCurrent());
      assertEquals(0, scalar("SELECT COUNT(*) FROM sound_publications"));
      revision.set("decoder-v1");
      assertNull(service.get(OWNER, document).publication());
    }
  }

  @Test
  void tombstoneAfterRemoteReceiptCannotActivateOldSource() {
    try (var store = new SqliteAuthorityStore(directory);
        var compilation = compilation(new AtomicInteger())) {
      var service =
          service(
              store,
              compilation,
              (claim, budget) -> {
                sql(
                    "UPDATE documents SET tombstoned=1 WHERE document_id='"
                        + claim.original().documentId()
                        + "'");
                return receipt(claim);
              });
      String document = service.upload(OWNER, "tone.wav", "audio/wav", raw()).documentId();
      assertThrows(ApplicationException.class, () -> service.build(OWNER, document));
      assertEquals(0, scalar("SELECT COUNT(*) FROM sound_publications"));
    }
  }

  @Test
  void twoConfiguredDocumentsRunTogetherThirdIsDeniedAndPermitsAreReusable() throws Exception {
    var entered = new CountDownLatch(2);
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
                  if (!release.await(4, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Fixture timeout");
                  }
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException(interrupted);
                }
                return receipt(claim);
              });
      String first = service.upload(OWNER, "first.wav", "audio/wav", raw()).documentId();
      String second = service.upload(OWNER, "second.wav", "audio/wav", raw()).documentId();
      String third = service.upload(OWNER, "third.wav", "audio/wav", raw()).documentId();
      var one = new FutureTask<>(() -> service.build(OWNER, first));
      var two = new FutureTask<>(() -> service.build(OWNER, second));
      Thread.ofVirtual().start(one);
      Thread.ofVirtual().start(two);
      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        assertEquals(
            "sound_index_busy",
            assertThrows(ApplicationException.class, () -> service.build(OWNER, third)).code());
      } finally {
        release.countDown();
      }
      assertEquals(3, one.get(3, TimeUnit.SECONDS).publication().spans().size());
      assertEquals(3, two.get(3, TimeUnit.SECONDS).publication().spans().size());
      assertEquals(3, service.build(OWNER, third).publication().spans().size());
    }
  }

  @Test
  void constructorRejectsCrossedProfilesAndUnsupportedTotalBudgetsBeforeAnyDecode() {
    var calls = new AtomicInteger();
    try (var store = new SqliteAuthorityStore(directory);
        var compilation = compilation(calls)) {
      var profile = new Settings();
      for (IndexTarget target :
          List.of(
              new IndexTarget(
                  "wrong-embedding",
                  profile.projection.identity(),
                  profile.embeddings.revision(),
                  2),
              new IndexTarget(
                  profile.embeddings.revision(), profile.projection.identity(), "wrong-model", 2),
              new IndexTarget(
                  profile.embeddings.revision(), "b".repeat(64), profile.embeddings.revision(), 2),
              new IndexTarget(
                  profile.embeddings.revision(),
                  profile.projection.identity(),
                  profile.embeddings.revision(),
                  3))) {
        assertThrows(
            ApplicationException.class,
            () ->
                new SoundLibraryService(
                    store,
                    new SoundRepository(store),
                    new ManagementRepository(store),
                    new DocumentPermissionPolicy(),
                    compilation,
                    target,
                    profile.models,
                    profile.embeddings,
                    profile.projection,
                    Duration.ofSeconds(5),
                    2,
                    (claim, budget) -> receipt(claim)));
      }
      for (Duration budget : List.of(Duration.ofMillis(9), Duration.ofMillis(120001))) {
        assertThrows(
            ApplicationException.class,
            () -> service(store, compilation, (claim, remaining) -> receipt(claim), 2, budget));
      }
      var incompatible =
          new GeminiSoundEmbeddingModels.Configuration(
              profile.endpoint,
              "embedding-v1",
              2,
              "decoder-v2",
              Duration.ofSeconds(5),
              65536,
              true);
      var projection =
          new MilvusRestProjection.Settings(
              profile.projection.endpoint(),
              "",
              "default",
              "java_sound_fixture",
              "org",
              incompatible.revision(),
              2,
              Duration.ofSeconds(5),
              65536,
              true);
      var exactTarget =
          new IndexTarget(
              incompatible.revision(), projection.identity(), incompatible.revision(), 2);
      assertThrows(
          ApplicationException.class,
          () ->
              new SoundLibraryService(
                  store,
                  new SoundRepository(store),
                  new ManagementRepository(store),
                  new DocumentPermissionPolicy(),
                  compilation,
                  exactTarget,
                  profile.models,
                  incompatible,
                  projection,
                  Duration.ofSeconds(5),
                  2,
                  (claim, budget) -> receipt(claim)));
      var wrongEmbeddingProjection =
          new MilvusRestProjection.Settings(
              profile.projection.endpoint(),
              "",
              "default",
              "java_sound_fixture",
              "org",
              "wrong-embedding",
              2,
              Duration.ofSeconds(5),
              65536,
              true);
      var wrongEmbeddingTarget =
          new IndexTarget(
              profile.embeddings.revision(),
              wrongEmbeddingProjection.identity(),
              profile.embeddings.revision(),
              2);
      assertThrows(
          ApplicationException.class,
          () ->
              new SoundLibraryService(
                  store,
                  new SoundRepository(store),
                  new ManagementRepository(store),
                  new DocumentPermissionPolicy(),
                  compilation,
                  wrongEmbeddingTarget,
                  profile.models,
                  profile.embeddings,
                  wrongEmbeddingProjection,
                  Duration.ofSeconds(5),
                  2,
                  (claim, budget) -> receipt(claim)));
      var threeDimensions =
          new GeminiSoundEmbeddingModels.Configuration(
              profile.endpoint,
              "embedding-v1",
              3,
              "decoder-v1",
              Duration.ofSeconds(5),
              65536,
              true);
      var wrongDimensionProjection =
          new MilvusRestProjection.Settings(
              profile.projection.endpoint(),
              "",
              "default",
              "java_sound_fixture",
              "org",
              threeDimensions.revision(),
              2,
              Duration.ofSeconds(5),
              65536,
              true);
      var wrongDimensionTarget =
          new IndexTarget(
              threeDimensions.revision(),
              wrongDimensionProjection.identity(),
              threeDimensions.revision(),
              3);
      assertThrows(
          ApplicationException.class,
          () ->
              new SoundLibraryService(
                  store,
                  new SoundRepository(store),
                  new ManagementRepository(store),
                  new DocumentPermissionPolicy(),
                  compilation,
                  wrongDimensionTarget,
                  profile.models,
                  threeDimensions,
                  wrongDimensionProjection,
                  Duration.ofSeconds(5),
                  2,
                  (claim, budget) -> receipt(claim)));
      assertEquals(0, calls.get());
    }
  }

  @Test
  void unavailableProfileAndInvalidSourceEnvelopeNeverRegisterDocuments() {
    var decoded = new AtomicInteger();
    try (var store = new SqliteAuthorityStore(directory);
        var compilation = compilation(decoded)) {
      var service = service(store, compilation, (claim, budget) -> receipt(claim));
      assertEquals(
          "unsupported_document",
          assertThrows(
                  ApplicationException.class,
                  () -> service.upload(OWNER, "q.txt", "text/plain", new byte[] {1}))
              .code());
      assertEquals(0, scalar("SELECT COUNT(*) FROM sound_originals"));
      compilation.close();
      assertEquals(
          "sound_index_stale",
          assertThrows(
                  ApplicationException.class,
                  () -> service.upload(OWNER, "tone.wav", "audio/wav", raw()))
              .code());
      assertEquals(0, decoded.get());
    }
  }

  private static final class Settings {
    final OpenAiCompatibleModels.Endpoint endpoint =
        new OpenAiCompatibleModels.Endpoint(
            URI.create("http://127.0.0.1:1"), "sound", "fixture-key");
    final GeminiSoundModels.Configuration models =
        new GeminiSoundModels.Configuration(
            endpoint, "sound-v1", Duration.ofSeconds(5), 65536, true);
    final GeminiSoundEmbeddingModels.Configuration embeddings =
        new GeminiSoundEmbeddingModels.Configuration(
            endpoint, "embedding-v1", 2, "decoder-v1", Duration.ofSeconds(5), 65536, true);
    final MilvusRestProjection.Settings projection =
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
    return service(store, compilation, indexer, maxConcurrent, Duration.ofSeconds(5));
  }

  private static SoundLibraryService service(
      SqliteAuthorityStore store,
      SoundCompilationService compilation,
      BiFunction<SoundBuildClaim, Duration, SoundReceipt> indexer,
      int maxConcurrent,
      Duration budget) {
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
        budget,
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
