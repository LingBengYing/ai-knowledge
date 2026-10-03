package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AudioVectorBuildClaim;
import com.evidence.rag.model.domain.AudioVectorReceipt;
import com.evidence.rag.model.domain.DecodedAudio;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.repository.AudioVectorRepository;
import com.evidence.rag.repository.EvidenceRepository;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.AnswerTestContext;
import com.evidence.rag.worker.indexing.ProcessAudioVectorIndexer;
import com.evidence.rag.worker.parser.AudioDecoder;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AudioVectorBuildBoundaryTest {
  private static final String DECODER = "test-audio-decoder-v1";
  private static final IndexTarget AUDIO = AudioVectorIndexingServiceTest.AUDIO;
  @TempDir Path directory;

  @Test
  void safeAndUnsafeWorkerFailuresPublishNothingAndReleaseAdmissionForExplicitRetry() {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(5), 2)) {
      var published = AudioTestFixture.publish(context, List.of("speech"));
      var current = new AtomicReference<RuntimeException>();
      var calls = new AtomicInteger();
      var service =
          service(
              context,
              decoder(source -> decoded(source, DECODER)),
              (claim, budget) -> {
                calls.incrementAndGet();
                var failure = current.get();
                if (failure != null) {
                  throw failure;
                }
                return receipt(claim);
              });
      var failures =
          List.of(
              new ProcessAudioVectorIndexer.Failure("audio_vector_timeout"),
              new ProcessAudioVectorIndexer.Failure("audio_vector_closed"),
              new ProcessAudioVectorIndexer.Failure("untrusted-provider-code"),
              new IllegalStateException("private upstream credential detail"),
              new ApplicationException(FailureKind.CONFLICT, "fixture-conflict", "Safe conflict"));
      var codes =
          List.of(
              "audio_vector_timeout",
              "audio_vector_closed",
              "audio_vector_unavailable",
              "audio_vector_unavailable",
              "fixture-conflict");
      for (int i = 0; i < failures.size(); i++) {
        current.set(failures.get(i));
        var failure =
            assertThrows(
                ApplicationException.class,
                () -> service.build(context.owner, published.documentId()));
        assertEquals(codes.get(i), failure.code());
        assertFalse(failure.getMessage().contains("private upstream"));
        assertEquals(0, context.scalar("SELECT COUNT(*) FROM audio_vector_publications"));
        assertEquals(0, context.scalar("SELECT COUNT(*) FROM audio_vector_entries"));
      }
      current.set(null);
      assertTrue(service.build(context.owner, published.documentId()).publication() != null);
      assertEquals(6, calls.get());
    }
  }

  @Test
  void everyTailReceiptBindingAndWholeManifestMustValidateBeforeAnySqlInsertion() {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(5), 2)) {
      var published = AudioTestFixture.publish(context, List.of("first", "tail"));
      var damage = new AtomicInteger();
      var service =
          service(
              context,
              decoder(source -> decoded(source, DECODER)),
              (claim, budget) -> {
                var good = receipt(claim);
                var entries = new ArrayList<>(good.entries());
                var tail = entries.getLast();
                return switch (damage.get()) {
                  case 0 -> null;
                  case 1 ->
                      new AudioVectorReceipt(
                          List.of(entries.getFirst()),
                          new VerifiedRevision(
                              good.verified().projectionIdentity(),
                              good.verified().manifestSha256(),
                              1));
                  case 2 -> {
                    entries.set(
                        1,
                        new AudioVectorReceipt.Entry(
                            "wrong-physical", tail.vector(), tail.entrySha256()));
                    yield new AudioVectorReceipt(entries, good.verified());
                  }
                  case 3 -> {
                    entries.set(
                        1,
                        new AudioVectorReceipt.Entry(
                            tail.physicalSegmentId(),
                            List.of(0.25, 0.75, 0.5),
                            tail.entrySha256()));
                    yield new AudioVectorReceipt(entries, good.verified());
                  }
                  case 4 -> {
                    entries.set(
                        1,
                        new AudioVectorReceipt.Entry(
                            tail.physicalSegmentId(), tail.vector(), "e".repeat(64)));
                    yield new AudioVectorReceipt(entries, good.verified());
                  }
                  case 5 ->
                      new AudioVectorReceipt(
                          entries,
                          new VerifiedRevision(
                              "e".repeat(64), good.verified().manifestSha256(), 2));
                  case 6 ->
                      new AudioVectorReceipt(
                          entries,
                          new VerifiedRevision(
                              good.verified().projectionIdentity(), "e".repeat(64), 2));
                  default -> good;
                };
              });
      for (int i = 0; i < 7; i++) {
        damage.set(i);
        assertEquals(
            "audio_vector_stale",
            assertThrows(
                    ApplicationException.class,
                    () -> service.build(context.owner, published.documentId()))
                .code());
        assertEquals(0, context.scalar("SELECT COUNT(*) FROM audio_vector_publications"));
        assertEquals(0, context.scalar("SELECT COUNT(*) FROM audio_vector_entries"));
      }
      damage.set(7);
      assertEquals(
          2, service.build(context.owner, published.documentId()).publication().entries().size());
    }
  }

  @Test
  void decoderMustReturnTheCurrentSourceProfileAndCompleteSavedDurationBeforeModels() {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(5), 2)) {
      var published = AudioTestFixture.publish(context, List.of("speech"));
      var decoding = new AtomicReference<Function<byte[], DecodedAudio>>();
      var modelCalls = new AtomicInteger();
      var service =
          service(
              context,
              decoder(source -> decoding.get().apply(source)),
              (claim, budget) -> {
                modelCalls.incrementAndGet();
                return receipt(claim);
              });
      List<Function<byte[], DecodedAudio>> failures =
          List.of(
              source -> null,
              source ->
                  new DecodedAudio(
                      "e".repeat(64), DECODER, Arrays.copyOfRange(source, 44, source.length)),
              source -> decoded(source, "other-decoder"),
              source ->
                  new DecodedAudio(
                      ModelValues.sha256(source),
                      DECODER,
                      Arrays.copyOfRange(source, 44, source.length - 32)));
      for (var failure : failures) {
        decoding.set(failure);
        assertEquals(
            "audio_vector_stale",
            assertThrows(
                    ApplicationException.class,
                    () -> service.build(context.owner, published.documentId()))
                .code());
        assertEquals(0, modelCalls.get());
      }
      decoding.set(
          source -> {
            throw new IllegalStateException("private decoder diagnostic");
          });
      var failure =
          assertThrows(
              ApplicationException.class,
              () -> service.build(context.owner, published.documentId()));
      assertEquals("audio_vector_unavailable", failure.code());
      assertFalse(failure.getMessage().contains("private decoder"));
      decoding.set(source -> decoded(source, DECODER));
      assertTrue(service.build(context.owner, published.documentId()).publication() != null);
      assertEquals(1, modelCalls.get());
    }
  }

  @Test
  void cancellationDuringDecodeWaitsForDecoderExitAndPreservesCallerInterruption()
      throws Exception {
    var entered = new CountDownLatch(1);
    var stopped = new CountDownLatch(1);
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(5), 2)) {
      var published = AudioTestFixture.publish(context, List.of("speech"));
      var decoder =
          decoder(
              source -> {
                entered.countDown();
                try {
                  new CountDownLatch(1).await();
                  throw new AssertionError("Expected interruption");
                } catch (InterruptedException cancelled) {
                  throw new IllegalStateException("Cancelled");
                } finally {
                  stopped.countDown();
                }
              });
      var service =
          service(
              context,
              decoder,
              (claim, budget) -> {
                throw new AssertionError("Cancelled decode must not reach models");
              });
      var task =
          new FutureTask<>(
              () -> {
                var failure =
                    assertThrows(
                        ApplicationException.class,
                        () -> service.build(context.owner, published.documentId()));
                return failure.code() + ":" + Thread.currentThread().isInterrupted();
              });
      var caller = Thread.ofVirtual().start(task);
      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        caller.interrupt();
        assertEquals("audio_vector_interrupted:true", task.get(3, TimeUnit.SECONDS));
        assertEquals(0, stopped.getCount());
        assertEquals(0, context.scalar("SELECT COUNT(*) FROM audio_vector_publications"));
      } finally {
        caller.interrupt();
      }
    }
  }

  @Test
  void textDocumentsWrongWorkspaceAndReaderBuildAreRejectedWithoutDecodeOrModel() throws Exception {
    var decodes = new AtomicInteger();
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(5), 2)) {
      String text = context.publish("policy.txt", "Policy is data.");
      var audio = AudioTestFixture.publish(context, List.of("speech"));
      var service =
          service(
              context,
              decoder(
                  source -> {
                    decodes.incrementAndGet();
                    return decoded(source, DECODER);
                  }),
              (claim, budget) -> {
                throw new AssertionError("Ineligible source cannot reach models");
              });
      assertEquals(
          FailureKind.NOT_FOUND,
          assertThrows(ApplicationException.class, () -> service.build(context.owner, text))
              .kind());
      assertEquals(
          FailureKind.NOT_FOUND,
          assertThrows(
                  ApplicationException.class,
                  () ->
                      service.get(
                          new Actor("other-workspace", context.owner.principalId()),
                          audio.documentId()))
              .kind());
      try (var connection =
              DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
          var statement =
              connection.prepareStatement(
                  "UPDATE document_acl SET role='reader' WHERE document_id=? AND principal_id=?")) {
        statement.setString(1, audio.documentId());
        statement.setString(2, context.owner.principalId());
        assertEquals(1, statement.executeUpdate());
      }
      assertEquals(null, service.get(context.owner, audio.documentId()).publication());
      assertEquals(
          FailureKind.NOT_FOUND,
          assertThrows(
                  ApplicationException.class,
                  () -> service.build(context.owner, audio.documentId()))
              .kind());
      assertEquals(0, decodes.get());
    }
  }

  private static AudioDecoder decoder(Function<byte[], DecodedAudio> decoding) {
    return new AudioDecoder() {
      public String revision() {
        return DECODER;
      }

      public DecodedAudio decode(String filename, String mime, byte[] source) {
        return decoding.apply(source);
      }

      public void close() {
        throw new AssertionError("Service does not own the shared decoder");
      }
    };
  }

  private static DecodedAudio decoded(byte[] source, String revision) {
    return new DecodedAudio(
        ModelValues.sha256(source), revision, Arrays.copyOfRange(source, 44, source.length));
  }

  private static AudioVectorIndexingService service(
      AnswerTestContext context,
      AudioDecoder decoder,
      BiFunction<AudioVectorBuildClaim, Duration, AudioVectorReceipt> worker) {
    var store = context.authority.store();
    return new AudioVectorIndexingService(
        store,
        new AudioVectorRepository(store),
        new EvidenceRepository(store),
        new ManagementRepository(store),
        new IngestionRepository(store),
        new DocumentPermissionPolicy(),
        context.target,
        AUDIO,
        decoder,
        Duration.ofSeconds(5),
        2,
        () -> AUDIO,
        worker);
  }

  private static AudioVectorReceipt receipt(AudioVectorBuildClaim claim) {
    var entries = new ArrayList<AudioVectorReceipt.Entry>();
    var digests = new TreeMap<String, String>();
    for (var span : claim.spans()) {
      String id =
          RetrievalProjection.physicalSegmentId(claim.vectorGenerationId(), span.audioEvidenceId());
      var entry =
          new RetrievalProjection.Entry(
              id,
              claim.actor().workspaceId(),
              claim.basePublication().documentId(),
              claim.vectorGenerationId(),
              span.waveform().pcmSha256(),
              List.of(0.25, 0.75));
      String digest = RetrievalProjection.entryDigest(entry);
      digests.put(id, digest);
      entries.add(new AudioVectorReceipt.Entry(id, entry.vector(), digest));
    }
    var manifest =
        new RetrievalProjection.RevisionManifest(
            claim.actor().workspaceId(),
            claim.basePublication().documentId(),
            claim.vectorGenerationId(),
            digests);
    return new AudioVectorReceipt(
        entries,
        new VerifiedRevision(
            claim.target().projectionIdentity(), manifest.sha256(), entries.size()));
  }
}
