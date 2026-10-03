package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.SoundEmbeddingModels;
import com.evidence.rag.client.model.SoundModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.AudioWaveform;
import com.evidence.rag.model.domain.DecodedAudio;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.QueryAttachment;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.worker.parser.AudioDecoder;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SoundQueryBoundaryTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null-vector",
        "wrong-dimension",
        "non-finite",
        "zero-vector",
        "null-hits",
        "too-many-hits",
        "null-tail",
        "duplicate-tail"
      })
  void malformedCompleteProviderRouteCannotReachDraftOrCitations(String defect) {
    try (var fixture = new SoundTestFixture(directory);
        var pipeline = new Pipeline(fixture)) {
      fixture.register("library", SoundTestFixture.pcm(32000, 1), true);
      String id = fixture.publications.getFirst().spans().getFirst().physicalSegmentId();
      switch (defect) {
        case "null-vector" -> pipeline.vector = () -> null;
        case "wrong-dimension" -> pipeline.vector = () -> List.of(1.0, 0.0, 0.0);
        case "non-finite" -> pipeline.vector = () -> List.of(Double.NaN, 1.0);
        case "zero-vector" -> pipeline.vector = () -> List.of(0.0, 0.0);
        case "null-hits" -> fixture.search = query -> null;
        case "too-many-hits" ->
            fixture.search =
                query -> Collections.nCopies(65, new RetrievalProjection.Candidate(id, 1));
        case "null-tail" ->
            fixture.search = query -> Arrays.asList(new RetrievalProjection.Candidate(id, 1), null);
        case "duplicate-tail" ->
            fixture.search =
                query ->
                    List.of(
                        new RetrievalProjection.Candidate(id, 1),
                        new RetrievalProjection.Candidate(id, 0.5));
        default -> throw new AssertionError(defect);
      }
      try (var answers = pipeline.answers(Duration.ofSeconds(5), 2)) {
        var result = answers.answer(SoundTestFixture.OWNER, command());
        assertEquals("upstream_invalid", result.reasonCode(), defect);
        assertEquals("abstained", result.status());
        assertTrue(result.citations().isEmpty());
        assertEquals(0, pipeline.drafts);
        assertEquals(0, pipeline.decodes);
        assertEquals(1, fixture.count("SELECT count(*) FROM sound_traces"));
        assertEquals(0, fixture.count("SELECT count(*) FROM sound_trace_evidence"));
      }
    }
  }

  @Test
  void authorizedEmptyProjectionReturnsNoEvidenceWithoutOriginalDecode() {
    try (var fixture = new SoundTestFixture(directory);
        var answers = fixture.answers()) {
      fixture.register("library", SoundTestFixture.pcm(32000, 1), true);
      fixture.search = query -> List.of();
      var result = answers.answer(SoundTestFixture.OWNER, command());
      assertEquals("no_evidence", result.reasonCode());
      assertEquals(0, fixture.decodes);
      assertTrue(fixture.drafts.isEmpty());
      assertEquals(1, fixture.count("SELECT count(*) FROM sound_trace_documents"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"draft-absent", "verification-absent", "verification-incomplete-list"})
  void missingStructuredProofOutputRefusesTheWholeQuestion(String defect) {
    try (var fixture = new SoundTestFixture(directory);
        var answers = fixture.answers()) {
      fixture.register("library", SoundTestFixture.pcm(32000, 1), true);
      switch (defect) {
        case "draft-absent" -> fixture.draft = waveform -> null;
        case "verification-absent" -> fixture.verify = waveform -> null;
        case "verification-incomplete-list" ->
            fixture.verify = waveform -> new SoundModels.Verification(true, List.of());
        default -> throw new AssertionError(defect);
      }
      var result = answers.answer(SoundTestFixture.OWNER, command());
      assertEquals("upstream_invalid", result.reasonCode());
      assertTrue(result.citations().isEmpty());
      assertEquals(1, fixture.count("SELECT count(*) FROM sound_traces"));
      assertEquals(0, fixture.count("SELECT count(*) FROM sound_trace_evidence"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"window-count", "actual-tail", "pcm-content"})
  void secondDecodeMustMatchTheEntirePublishedActualPcm(String defect) {
    try (var fixture = new SoundTestFixture(directory);
        var pipeline = new Pipeline(fixture)) {
      fixture.register("library", SoundTestFixture.pcm(64000, 1), true);
      pipeline.decodedDefect = defect;
      try (var answers = pipeline.answers(Duration.ofSeconds(5), 2)) {
        var result = answers.answer(SoundTestFixture.OWNER, command());
        assertEquals("source_changed", result.reasonCode());
        assertEquals(1, pipeline.decodes);
        assertEquals(0, pipeline.drafts);
        assertTrue(result.citations().isEmpty());
        assertEquals(0, fixture.count("SELECT count(*) FROM sound_trace_evidence"));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"decoder", "dimensions", "projection", "closed-compiler"})
  void runtimeProfileChangesBeforeDispatchProduceNoEmbeddingsOrTrace(String change) {
    try (var fixture = new SoundTestFixture(directory);
        var pipeline = new Pipeline(fixture);
        var answers = pipeline.answers(Duration.ofSeconds(5), 2)) {
      fixture.register("library", SoundTestFixture.pcm(32000, 1), true);
      switch (change) {
        case "decoder" -> pipeline.decoderRevision = "changed-decoder";
        case "dimensions" -> pipeline.dimensions = 3;
        case "projection" -> pipeline.projectionIdentity = "a".repeat(64);
        case "closed-compiler" -> pipeline.compiler.close();
        default -> throw new AssertionError(change);
      }
      assertEquals(
          "configuration_changed",
          assertThrows(
                  ApplicationException.class,
                  () -> answers.answer(SoundTestFixture.OWNER, command()))
              .code());
      assertEquals(0, pipeline.embeddings);
      assertEquals(0, pipeline.decodes);
      assertEquals(0, fixture.count("SELECT count(*) FROM sound_traces"));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"target-model", "profile", "short-budget", "long-budget", "too-many-concurrent"})
  void invalidPinnedProcessingConfigurationFailsBeforeAnyWork(String change) {
    try (var fixture = new SoundTestFixture(directory);
        var pipeline = new Pipeline(fixture)) {
      Duration budget =
          change.equals("short-budget")
              ? Duration.ofMillis(9)
              : change.equals("long-budget") ? Duration.ofMillis(120001) : Duration.ofSeconds(5);
      int concurrency = change.equals("too-many-concurrent") ? 3 : 2;
      if (change.equals("target-model")) {
        pipeline.target =
            new IndexTarget(
                SoundTestFixture.EMBEDDING,
                SoundTestFixture.TARGET.projectionIdentity(),
                "wrong-model",
                2);
      }
      if (change.equals("profile")) {
        pipeline.profile = "0".repeat(64);
      }
      assertThrows(ApplicationException.class, () -> pipeline.answers(budget, concurrency));
      assertEquals(0, pipeline.embeddings);
      assertEquals(0, pipeline.decodes);
    }
  }

  @Test
  void admissionCapacityIsReleasedAfterTheActualBlockedProofFinishes() throws Exception {
    try (var fixture = new SoundTestFixture(directory);
        var pipeline = new Pipeline(fixture);
        var answers = pipeline.answers(Duration.ofSeconds(5), 1);
        var caller = Executors.newVirtualThreadPerTaskExecutor()) {
      fixture.register("library", SoundTestFixture.pcm(32000, 1), true);
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      pipeline.beforeDraft =
          () -> {
            entered.countDown();
            await(release);
          };
      var first = caller.submit(() -> answers.answer(SoundTestFixture.OWNER, command()));
      try {
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertEquals(
            "answer_capacity_exceeded",
            assertThrows(
                    ApplicationException.class,
                    () -> answers.answer(SoundTestFixture.OWNER, command()))
                .code());
        assertEquals(1, pipeline.embeddings);
        assertEquals(0, fixture.count("SELECT count(*) FROM sound_traces"));
      } finally {
        release.countDown();
      }
      assertEquals("answered", first.get(2, TimeUnit.SECONDS).status());
      pipeline.beforeDraft = () -> {};
      long until = System.nanoTime() + Duration.ofSeconds(2).toNanos();
      while (true) {
        try {
          assertEquals("answered", answers.answer(SoundTestFixture.OWNER, command()).status());
          break;
        } catch (ApplicationException inFlight) {
          assertEquals("answer_capacity_exceeded", inFlight.code());
          assertTrue(System.nanoTime() < until, "The first worker must release its admission slot");
          Thread.sleep(1);
        }
      }
      assertEquals(2, fixture.count("SELECT count(*) FROM sound_traces"));
    }
  }

  @Test
  void deadlineCancelsBlockedProviderAndNeverCommitsItsLateResult() throws Exception {
    try (var fixture = new SoundTestFixture(directory);
        var pipeline = new Pipeline(fixture);
        var answers = pipeline.answers(Duration.ofSeconds(1), 1);
        var caller = Executors.newVirtualThreadPerTaskExecutor()) {
      fixture.register("library", SoundTestFixture.pcm(32000, 1), true);
      var entered = new CountDownLatch(1);
      var stopped = new CountDownLatch(1);
      pipeline.vector =
          () -> {
            entered.countDown();
            try {
              new CountDownLatch(1).await();
            } catch (InterruptedException cancelled) {
              Thread.currentThread().interrupt();
            } finally {
              stopped.countDown();
            }
            return List.of(1.0, 0.0);
          };
      var response =
          caller.submit(
              () ->
                  assertThrows(
                      ApplicationException.class,
                      () -> answers.answer(SoundTestFixture.OWNER, command())));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      assertEquals("answer_timeout", response.get(2, TimeUnit.SECONDS).code());
      assertTrue(stopped.await(2, TimeUnit.SECONDS));
      assertEquals(0, pipeline.drafts);
      assertEquals(0, fixture.count("SELECT count(*) FROM sound_traces"));
    }
  }

  @Test
  void callerInterruptionCancelsItsProviderAndPreservesTheCallerInterrupt() throws Exception {
    try (var fixture = new SoundTestFixture(directory);
        var pipeline = new Pipeline(fixture);
        var answers = pipeline.answers(Duration.ofSeconds(5), 1)) {
      fixture.register("library", SoundTestFixture.pcm(32000, 1), true);
      var entered = new CountDownLatch(1);
      var stopped = new CountDownLatch(1);
      pipeline.vector =
          () -> {
            entered.countDown();
            try {
              new CountDownLatch(1).await();
            } catch (InterruptedException cancelled) {
              Thread.currentThread().interrupt();
            } finally {
              stopped.countDown();
            }
            return List.of(1.0, 0.0);
          };
      var result = new CompletableFuture<String>();
      var caller =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      answers.answer(SoundTestFixture.OWNER, command());
                      result.complete("unexpected-success");
                    } catch (ApplicationException failure) {
                      result.complete(
                          failure.code() + ":" + Thread.currentThread().isInterrupted());
                    }
                  });
      try {
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        caller.interrupt();
        assertEquals("answer_timeout:true", result.get(2, TimeUnit.SECONDS));
        assertTrue(stopped.await(2, TimeUnit.SECONDS));
        assertEquals(0, fixture.count("SELECT count(*) FROM sound_traces"));
      } finally {
        caller.interrupt();
        assertTrue(caller.join(Duration.ofSeconds(2)));
      }
    }
  }

  @Test
  void closingDuringProviderWorkPreventsBothLateTraceAndNewAdmissions() throws Exception {
    try (var fixture = new SoundTestFixture(directory);
        var pipeline = new Pipeline(fixture);
        var answers = pipeline.answers(Duration.ofSeconds(5), 1);
        var caller = Executors.newVirtualThreadPerTaskExecutor()) {
      fixture.register("library", SoundTestFixture.pcm(32000, 1), true);
      var entered = new CountDownLatch(1);
      pipeline.vector =
          () -> {
            entered.countDown();
            try {
              new CountDownLatch(1).await();
            } catch (InterruptedException cancelled) {
              Thread.currentThread().interrupt();
            }
            return List.of(1.0, 0.0);
          };
      var response =
          caller.submit(
              () ->
                  assertThrows(
                      ApplicationException.class,
                      () -> answers.answer(SoundTestFixture.OWNER, command())));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      answers.close();
      assertEquals("answer_timeout", response.get(2, TimeUnit.SECONDS).code());
      assertEquals(
          "sound_answers_unavailable",
          assertThrows(
                  ApplicationException.class,
                  () -> answers.answer(SoundTestFixture.OWNER, command()))
              .code());
      assertEquals(0, fixture.count("SELECT count(*) FROM sound_traces"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"empty", "non-audio", "four-files", "total-bytes"})
  void attachedInputAdmissionRejectsIncompleteOrOversizedBatchesWithoutDispatch(String defect) {
    try (var fixture = new SoundTestFixture(directory);
        var answers = fixture.answers()) {
      byte[] bytes = new byte[defect.equals("total-bytes") ? 7 * 1024 * 1024 : 2];
      var audio = new QueryAttachment("query.wav", "audio/wav", bytes);
      List<QueryAttachment> inputs =
          switch (defect) {
            case "empty" -> List.of();
            case "non-audio" ->
                List.of(audio, new QueryAttachment("image.png", "image/png", new byte[] {1}));
            case "four-files" -> Collections.nCopies(4, audio);
            case "total-bytes" -> Collections.nCopies(3, audio);
            default -> throw new AssertionError(defect);
          };
      assertThrows(
          ApplicationException.class,
          () -> answers.answerAttached(SoundTestFixture.OWNER, command(), inputs));
      assertEquals(0, fixture.decodes);
      assertEquals(0, fixture.textEmbeds);
      assertEquals(0, fixture.count("SELECT count(*) FROM sound_traces"));
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(3, TimeUnit.SECONDS));
    } catch (InterruptedException cancelled) {
      Thread.currentThread().interrupt();
      throw new AssertionError(cancelled);
    }
  }

  private static AnswerCommand command() {
    return new AnswerCommand(
        SoundTestFixture.QUESTION, DocumentSelection.selected(List.of("library")));
  }

  private static final class Pipeline implements AutoCloseable {
    final SoundTestFixture fixture;
    final SoundCompilationService compiler;
    String decoderRevision = SoundTestFixture.DECODER;
    String projectionIdentity = SoundTestFixture.TARGET.projectionIdentity();
    String profile = SoundTestFixture.PROFILE;
    String decodedDefect = "none";
    IndexTarget target = SoundTestFixture.TARGET;
    int dimensions = 2;
    int decodes, embeddings, drafts;
    Supplier<List<Double>> vector = () -> List.of(1.0, 0.0);
    Runnable beforeDraft = () -> {};

    Pipeline(SoundTestFixture fixture) {
      this.fixture = fixture;
      compiler =
          new SoundCompilationService(
              new AudioDecoder() {
                public String revision() {
                  return decoderRevision;
                }

                public DecodedAudio decode(String filename, String mime, byte[] source) {
                  decodes++;
                  byte[] pcm = Arrays.copyOfRange(source, 44, source.length);
                  if (decodedDefect.equals("window-count")) {
                    pcm = Arrays.copyOf(pcm, 32000);
                  }
                  if (decodedDefect.equals("actual-tail")) {
                    pcm = Arrays.copyOf(pcm, pcm.length - 2);
                  }
                  if (decodedDefect.equals("pcm-content")) {
                    pcm[0] ^= 1;
                  }
                  return new DecodedAudio(ModelValues.sha256(source), decoderRevision, pcm);
                }

                public void close() {}
              },
              1,
              Duration.ofSeconds(5));
    }

    SoundAnswerService answers(Duration budget, int concurrency) {
      SoundModels models =
          new SoundModels() {
            public String revision() {
              return SoundTestFixture.MODEL;
            }

            public Description describe(AudioWaveform waveform) {
              throw new AssertionError("Query cannot describe");
            }

            public Draft draft(String question, AudioWaveform waveform) {
              drafts++;
              beforeDraft.run();
              return new Draft(true, List.of(SoundTestFixture.FACT));
            }

            public Verification verify(
                String question, AudioWaveform waveform, List<String> claims) {
              return new Verification(true, List.of(true));
            }
          };
      SoundEmbeddingModels embedding =
          new SoundEmbeddingModels() {
            public String revision() {
              return SoundTestFixture.EMBEDDING;
            }

            public int dimensions() {
              return dimensions;
            }

            public List<Double> embedText(String question) {
              embeddings++;
              return vector.get();
            }

            public List<Double> embedAudio(byte[] wav) {
              throw new AssertionError("These scenarios have no query attachment");
            }
          };
      RetrievalProjection projection =
          new RetrievalProjection() {
            public String identity() {
              return projectionIdentity;
            }

            public void initialize() {
              throw new AssertionError("No query writes");
            }

            public void prepareSearch() {
              fixture.store.transaction(() -> null);
            }

            public VerifiedRevision verify(RevisionManifest manifest) {
              throw new AssertionError("No query writes");
            }

            public void upsert(List<Entry> entries) {
              throw new AssertionError("No query writes");
            }

            public List<Candidate> search(Query query) {
              return fixture.search.apply(query);
            }
          };
      return new SoundAnswerService(
          fixture.store,
          fixture.repository,
          fixture.management,
          new DocumentPermissionPolicy(),
          compiler,
          models,
          embedding,
          projection,
          target,
          profile,
          budget,
          concurrency);
    }

    public void close() {
      compiler.close();
    }
  }
}
