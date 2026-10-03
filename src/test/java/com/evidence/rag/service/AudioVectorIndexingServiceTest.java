package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.AudioCompilation;
import com.evidence.rag.model.domain.AudioTranscriptSpan;
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
import com.evidence.rag.tool.parser.AudioPcm;
import com.evidence.rag.worker.parser.AudioDecoder;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AudioVectorIndexingServiceTest {
  static final IndexTarget AUDIO = new IndexTarget("audio-v1", "c".repeat(64), "audio-v1", 2);
  @TempDir Path directory;

  @Test
  void completeSpeechSetPreservesSilentOrdinalGapAndIdempotentReadDoesNoDecode() {
    var decoded = new AtomicInteger();
    var calls = new AtomicInteger();
    var documentId = new AtomicReference<String>();
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(5), 2)) {
      var published = AudioTestFixture.publish(context, List.of("first", "", "tail"));
      documentId.set(published.documentId());
      var service =
          service(
              context,
              decoder(decoded),
              AUDIO,
              (claim, budget) -> {
                calls.incrementAndGet();
                assertEquals(
                    List.of(0, 2), claim.spans().stream().map(span -> span.ordinal()).toList());
                assertEquals(48000, claim.spans().getLast().waveform().endSample());
                return receipt(claim);
              });
      assertEquals(null, service.get(context.owner, published.documentId()).publication());
      assertEquals(0, decoded.get());
      var built = service.build(context.owner, published.documentId());
      assertEquals(2, built.publication().entries().size());
      assertEquals(built, service.build(context.owner, published.documentId()));
      assertEquals(built, service.get(context.owner, published.documentId()));
      assertEquals(1, decoded.get());
      assertEquals(1, calls.get());
      assertEquals(1, context.scalar("SELECT COUNT(*) FROM audio_vector_publications"));
      assertEquals(2, context.scalar("SELECT COUNT(*) FROM audio_vector_entries"));
      assertEquals(1, context.scalar("SELECT COUNT(*) FROM active_corpus_publications"));
    }
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(5), 2)) {
      var service =
          service(
              context,
              decoder(decoded),
              AUDIO,
              (claim, budget) -> {
                throw new AssertionError("No remote work on reopen");
              });
      assertTrue(service.get(context.owner, documentId.get()).publication() != null);
      assertEquals(1, decoded.get());
    }
  }

  @Test
  void corruptTailReceiptRollsBackWholePublication() {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(5), 2)) {
      var published = AudioTestFixture.publish(context, List.of("first", "tail"));
      var service =
          service(
              context,
              decoder(new AtomicInteger()),
              AUDIO,
              (claim, budget) -> {
                var good = receipt(claim);
                var entries = new ArrayList<>(good.entries());
                var tail = entries.getLast();
                entries.set(
                    1,
                    new AudioVectorReceipt.Entry(
                        tail.physicalSegmentId(), List.of(0.75, 0.25), tail.entrySha256()));
                return new AudioVectorReceipt(entries, good.verified());
              });
      assertEquals(
          "audio_vector_stale",
          assertThrows(
                  ApplicationException.class,
                  () -> service.build(context.owner, published.documentId()))
              .code());
      assertEquals(0, context.scalar("SELECT COUNT(*) FROM audio_vector_publications"));
      assertEquals(0, context.scalar("SELECT COUNT(*) FROM audio_vector_entries"));
    }
  }

  @Test
  void permissionRevocationDuringRemoteWorkCannotCommit() {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(5), 2)) {
      var published = AudioTestFixture.publish(context, List.of("speech"));
      var service =
          service(
              context,
              decoder(new AtomicInteger()),
              AUDIO,
              (claim, budget) -> {
                context.revoke(published.documentId());
                return receipt(claim);
              });
      assertThrows(
          ApplicationException.class, () -> service.build(context.owner, published.documentId()));
      assertEquals(0, context.scalar("SELECT COUNT(*) FROM audio_vector_publications"));
    }
  }

  @Test
  void changedPinnedDecoderRejectedBeforeDecodeOrModel() {
    var count = new AtomicInteger();
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(5), 2)) {
      var published = AudioTestFixture.publish(context, List.of("speech"));
      AudioDecoder decoder =
          new AudioDecoder() {
            public String revision() {
              return "other-decoder";
            }

            public DecodedAudio decode(String f, String m, byte[] b) {
              count.incrementAndGet();
              throw new AssertionError();
            }

            public void close() {}
          };
      var service =
          service(
              context,
              decoder,
              AUDIO,
              (claim, budget) -> {
                throw new AssertionError();
              });
      assertEquals(
          "audio_vector_stale",
          assertThrows(
                  ApplicationException.class,
                  () -> service.build(context.owner, published.documentId()))
              .code());
      assertEquals(0, count.get());
    }
  }

  @Test
  void liveProfileChangePreventsPublicationAfterCompleteReceipt() {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(5), 2)) {
      var published = AudioTestFixture.publish(context, List.of("speech"));
      var profile = new AtomicReference<>(AUDIO);
      var service =
          service(
              context,
              decoder(new AtomicInteger()),
              profile,
              (claim, budget) -> {
                profile.set(new IndexTarget("audio-v2", "d".repeat(64), "audio-v2", 2));
                return receipt(claim);
              },
              Duration.ofSeconds(5),
              2);
      assertEquals(
          "audio_vector_stale",
          assertThrows(
                  ApplicationException.class,
                  () -> service.build(context.owner, published.documentId()))
              .code());
      assertEquals(0, context.scalar("SELECT COUNT(*) FROM audio_vector_publications"));
    }
  }

  @Test
  void sameDocumentInFlightRejectedAndReleasedAfterCompletion() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(5), 2)) {
      var published = AudioTestFixture.publish(context, List.of("speech"));
      var service =
          service(
              context,
              decoder(new AtomicInteger()),
              AUDIO,
              (claim, budget) -> {
                entered.countDown();
                try {
                  if (!release.await(3, TimeUnit.SECONDS)) {
                    throw new AssertionError();
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new AssertionError(e);
                }
                return receipt(claim);
              });
      var task = new FutureTask<>(() -> service.build(context.owner, published.documentId()));
      Thread.ofVirtual().start(task);
      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        assertEquals(
            "audio_vector_busy",
            assertThrows(
                    ApplicationException.class,
                    () -> service.build(context.owner, published.documentId()))
                .code());
      } finally {
        release.countDown();
      }
      assertTrue(task.get(3, TimeUnit.SECONDS).publication() != null);
    }
  }

  @Test
  void totalBudgetCancelsDecoderAndConfirmsItStoppedWithoutModelCall() {
    var stopped = new CountDownLatch(1);
    AudioDecoder decoder =
        new AudioDecoder() {
          public String revision() {
            return "test-audio-decoder-v1";
          }

          public DecodedAudio decode(String f, String m, byte[] b) {
            try {
              new CountDownLatch(1).await();
              throw new AssertionError();
            } catch (InterruptedException e) {
              throw new IllegalStateException();
            } finally {
              stopped.countDown();
            }
          }

          public void close() {
            throw new AssertionError("Shared decoder must not be closed");
          }
        };
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(5), 2)) {
      var published = AudioTestFixture.publish(context, List.of("speech"));
      var service =
          service(
              context,
              decoder,
              new AtomicReference<>(AUDIO),
              (claim, budget) -> {
                throw new AssertionError();
              },
              Duration.ofMillis(80),
              2);
      assertEquals(
          "audio_vector_timeout",
          assertThrows(
                  ApplicationException.class,
                  () -> service.build(context.owner, published.documentId()))
              .code());
      assertEquals(0, stopped.getCount());
      assertEquals(0, context.scalar("SELECT COUNT(*) FROM audio_vector_publications"));
    }
  }

  @Test
  void interruptedCallerPreservesFlagAndDoesNoDecode() {
    var count = new AtomicInteger();
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(5), 2)) {
      var published = AudioTestFixture.publish(context, List.of("speech"));
      var service =
          service(
              context,
              decoder(count),
              AUDIO,
              (claim, budget) -> {
                throw new AssertionError();
              });
      Thread.currentThread().interrupt();
      try {
        assertEquals(
            "audio_vector_interrupted",
            assertThrows(
                    ApplicationException.class,
                    () -> service.build(context.owner, published.documentId()))
                .code());
        assertTrue(Thread.currentThread().isInterrupted());
        assertEquals(0, count.get());
      } finally {
        Thread.interrupted();
      }
    }
  }

  @Test
  void realDecodedTailUsesAllThreeSamplesInsideTheSavedCeilMillisecond() {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(5), 2)) {
      String documentId = publishShortTail(context);
      var service =
          service(
              context,
              decoder(new AtomicInteger()),
              AUDIO,
              (claim, budget) -> {
                var tail = claim.spans().getLast();
                assertEquals(2, tail.ordinal());
                assertEquals(2000, tail.startMs());
                assertEquals(2001, tail.endMs());
                assertEquals(32000, tail.waveform().startSample());
                assertEquals(32003, tail.waveform().endSample());
                assertEquals(6, tail.waveform().pcm().length);
                return receipt(claim);
              });
      var saved = service.build(context.owner, documentId).publication();
      assertEquals(32003, saved.entries().getLast().endSample());
      assertEquals(saved, service.get(context.owner, documentId).publication());
    }
  }

  @Test
  void databaseRejectsMissingTailAndImmutableReceiptMutationsAtomically() throws Exception {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(5), 2)) {
      var published = AudioTestFixture.publish(context, List.of("first", "tail"));
      var service =
          service(context, decoder(new AtomicInteger()), AUDIO, (claim, budget) -> receipt(claim));
      var saved = service.build(context.owner, published.documentId()).publication();
      String generation = UUID.randomUUID().toString();
      var first = saved.entries().getFirst();
      try (var connection =
              DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
          var statement = connection.createStatement()) {
        statement.execute("PRAGMA foreign_keys=ON");
        connection.setAutoCommit(false);
        try (var insert =
            connection.prepareStatement(
                "INSERT INTO audio_vector_entries SELECT ?,audio_evidence_id,base_physical_segment_id,?,ordinal,start_sample,end_sample,pcm_sha256,entry_sha256 FROM audio_vector_entries WHERE audio_vector_publication_id=? AND ordinal=?")) {
          insert.setString(1, "prefix");
          insert.setString(
              2, RetrievalProjection.physicalSegmentId(generation, first.audioEvidenceId()));
          insert.setString(3, saved.id());
          insert.setInt(4, first.ordinal());
          assertEquals(1, insert.executeUpdate());
        }
        try (var insert =
            connection.prepareStatement(
                "INSERT INTO audio_vector_publications SELECT ?,publication_id,document_id,source_revision_id,source_sha256,?, 'other-profile',?, 'other-profile',dimensions,decoder_revision,manifest_sha256,segment_count,created_at FROM audio_vector_publications WHERE id=?")) {
          insert.setString(1, "prefix");
          insert.setString(2, generation);
          insert.setString(3, "e".repeat(64));
          insert.setString(4, saved.id());
          assertThrows(SQLException.class, insert::executeUpdate);
        }
        connection.rollback();
      }
      assertEquals(1, context.scalar("SELECT COUNT(*) FROM audio_vector_publications"));
      assertEquals(2, context.scalar("SELECT COUNT(*) FROM audio_vector_entries"));
      try (var connection =
              DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
          var statement = connection.createStatement()) {
        for (String table : List.of("audio_vector_publications", "audio_vector_entries")) {
          assertThrows(SQLException.class, () -> statement.executeUpdate("DELETE FROM " + table));
          assertThrows(
              SQLException.class,
              () ->
                  statement.executeUpdate(
                      "UPDATE "
                          + table
                          + " SET "
                          + (table.endsWith("entries") ? "pcm_sha256" : "manifest_sha256")
                          + "='"
                          + "f".repeat(64)
                          + "'"));
        }
      }
    }
  }

  @Test
  void globalCapacityRejectsThirdDocumentAndRecoversAfterBothFinish() throws Exception {
    var entered = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(5), 2)) {
      var first = AudioTestFixture.publish(context, List.of("first"));
      var second = AudioTestFixture.publish(context, List.of("second"));
      var third = AudioTestFixture.publish(context, List.of("third"));
      var service =
          service(
              context,
              decoder(new AtomicInteger()),
              AUDIO,
              (claim, budget) -> {
                entered.countDown();
                try {
                  if (!release.await(3, TimeUnit.SECONDS)) {
                    throw new AssertionError();
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new AssertionError(e);
                }
                return receipt(claim);
              });
      var one = new FutureTask<>(() -> service.build(context.owner, first.documentId()));
      var two = new FutureTask<>(() -> service.build(context.owner, second.documentId()));
      Thread.ofVirtual().start(one);
      Thread.ofVirtual().start(two);
      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        assertEquals(
            "audio_vector_busy",
            assertThrows(
                    ApplicationException.class,
                    () -> service.build(context.owner, third.documentId()))
                .code());
      } finally {
        release.countDown();
      }
      assertTrue(one.get(3, TimeUnit.SECONDS).publication() != null);
      assertTrue(two.get(3, TimeUnit.SECONDS).publication() != null);
      assertTrue(service.build(context.owner, third.documentId()).publication() != null);
    }
  }

  private static String publishShortTail(AnswerTestContext context) {
    var store = context.authority.store();
    var ingestion =
        new IngestionService(
            store,
            new IngestionRepository(store),
            new ManagementRepository(store),
            new DocumentPermissionPolicy(),
            null,
            null,
            AudioTestFixture.COMPILER);
    byte[] pcm = new byte[64006];
    pcm[64004] = 17;
    byte[] original = AudioPcm.wav(pcm, 0, pcm.length);
    var uploaded = ingestion.uploadDocument(context.owner, "tail.wav", "audio/wav", original);
    var claim = ingestion.claimIngestion(context.owner.workspaceId()).orElseThrow();
    assertTrue(
        ingestion.completeAudioIngestion(
            claim,
            new AudioCompilation(
                ModelValues.sha256(original),
                "test-audio-decoder-v1",
                "test-audio-model-v1",
                AudioTestFixture.COMPILER,
                2001,
                List.of(
                    new AudioTranscriptSpan(0, 0, 1000, "first"),
                    new AudioTranscriptSpan(1, 1000, 2000, ""),
                    new AudioTranscriptSpan(2, 2000, 2001, "tail")))));
    context.authority.createIndexing(context.owner, uploaded.documentId(), context.target);
    var indexing = context.authority.claimIndexing(context.owner.workspaceId()).orElseThrow();
    context.projection.data.initialize();
    var digests = new TreeMap<String, String>();
    for (var item : indexing.items()) {
      var entry =
          new RetrievalProjection.Entry(
              RetrievalProjection.physicalSegmentId(
                  indexing.projectionGenerationId(), item.evidenceId()),
              context.owner.workspaceId(),
              uploaded.documentId(),
              indexing.projectionGenerationId(),
              item.recallText(),
              List.of(1.0, 0.0));
      context.projection.data.upsert(List.of(entry));
      digests.put(entry.segmentId(), RetrievalProjection.entryDigest(entry));
    }
    var manifest =
        new RetrievalProjection.RevisionManifest(
            context.owner.workspaceId(),
            uploaded.documentId(),
            indexing.projectionGenerationId(),
            digests);
    assertTrue(
        context.authority.completeIndexing(
            indexing, digests, context.projection.data.verify(manifest)));
    return uploaded.documentId();
  }

  private static AudioDecoder decoder(AtomicInteger calls) {
    return new AudioDecoder() {
      public String revision() {
        return "test-audio-decoder-v1";
      }

      public DecodedAudio decode(String f, String m, byte[] source) {
        calls.incrementAndGet();
        return new DecodedAudio(
            ModelValues.sha256(source), revision(), Arrays.copyOfRange(source, 44, source.length));
      }

      public void close() {}
    };
  }

  private static AudioVectorIndexingService service(
      AnswerTestContext context,
      AudioDecoder decoder,
      IndexTarget target,
      BiFunction<AudioVectorBuildClaim, Duration, AudioVectorReceipt> worker) {
    return service(
        context, decoder, new AtomicReference<>(target), worker, Duration.ofSeconds(5), 2);
  }

  private static AudioVectorIndexingService service(
      AnswerTestContext context,
      AudioDecoder decoder,
      AtomicReference<IndexTarget> profile,
      BiFunction<AudioVectorBuildClaim, Duration, AudioVectorReceipt> worker,
      Duration budget,
      int concurrent) {
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
        budget,
        concurrent,
        profile::get,
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
