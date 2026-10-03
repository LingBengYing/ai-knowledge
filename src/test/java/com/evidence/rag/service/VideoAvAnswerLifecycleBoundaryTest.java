package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.VideoAvModels;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.VideoAvMode;
import com.evidence.rag.model.domain.VideoAvRequirement;
import com.evidence.rag.model.dto.VideoAvAnswerResult;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VideoAvAnswerLifecycleBoundaryTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"parser", "model-runtime"})
  void failedActualMediaAssessmentWritesOnlyWholeScopeRefusal(String failure) {
    try (var fixture = new VideoAvTestFixture(directory);
        var answers = fixture.answers()) {
      fixture.register("library", 1, 1, 16000, true);
      if (failure.equals("parser")) {
        fixture.compilations.clear();
      } else {
        fixture.draft =
            (window, mode) -> {
              throw new IllegalStateException("Synthetic provider operation failed");
            };
      }
      var result =
          answers.answer(VideoAvTestFixture.OWNER, VideoAvAnswerServiceTest.all(VideoAvMode.JOINT));
      assertEquals("abstained", result.status());
      assertEquals(
          failure.equals("parser") ? "parser_invalid_output" : "upstream_invalid",
          result.reasonCode());
      assertTrue(result.citations().isEmpty());
      assertEquals(failure.equals("parser") ? 0 : 1, fixture.drafts.size());
      assertTrue(fixture.verifies.isEmpty());
      assertEquals(1, fixture.count("SELECT count(*) FROM video_av_trace_documents"));
      assertEquals(0, fixture.count("SELECT count(*) FROM video_av_trace_evidence"));
      assertThrows(
          ApplicationException.class,
          () -> answers.source(VideoAvTestFixture.OWNER, result.answerId(), 1));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing-requirement", "visual-claim-for-audio"})
  void audioModeRequiresExplicitAudioClaimBeforeVerifierDispatch(String malformed) {
    try (var fixture = new VideoAvTestFixture(directory);
        var answers = fixture.answers()) {
      fixture.register("library", 1, 1, 16000, true);
      fixture.draft =
          (window, mode) ->
              new VideoAvModels.Draft(
                  true,
                  List.of(
                      new VideoAvModels.Claim(
                          "画面为红灯。",
                          malformed.equals("missing-requirement")
                              ? null
                              : VideoAvRequirement.VISUAL)));
      var answer =
          answers.answer(VideoAvTestFixture.OWNER, VideoAvAnswerServiceTest.all(VideoAvMode.AUDIO));
      assertEquals("upstream_invalid", answer.reasonCode());
      assertTrue(fixture.verifies.isEmpty());
      assertTrue(answer.citations().isEmpty());
    }
  }

  @Test
  void jointQuestionSkipsAudioOnlyTailWithoutInventingVideoProof() {
    try (var fixture = new VideoAvTestFixture(directory);
        var answers = fixture.answers()) {
      fixture.register("library", 2, 1, 32000, true);
      var answer =
          answers.answer(VideoAvTestFixture.OWNER, VideoAvAnswerServiceTest.all(VideoAvMode.JOINT));
      assertEquals("answered", answer.status());
      assertEquals(1, fixture.drafts.size());
      assertEquals(0, fixture.drafts.getFirst().ordinal());
      assertEquals(1, fixture.verifies.size());
      assertEquals(0, answer.citations().getFirst().window().ordinal());
      assertEquals(2, fixture.queries.size());
      assertEquals(1, fixture.decodes);
    }
  }

  @Test
  void admissionRejectsNextQuestionWhileCurrentProofRunsAndRecoversAfterCompletion()
      throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var first = new AtomicReference<VideoAvAnswerResult>();
    var failure = new AtomicReference<Throwable>();
    var proofThread = new AtomicReference<Thread>();
    try (var fixture = new VideoAvTestFixture(directory);
        var answers = fixture.answers(Duration.ofSeconds(10), 1)) {
      fixture.register("library", 1, 1, 16000, true);
      var usual = fixture.draft;
      fixture.draft =
          (window, mode) -> {
            proofThread.set(Thread.currentThread());
            entered.countDown();
            await(release);
            return usual.apply(window, mode);
          };
      var caller =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      first.set(
                          answers.answer(
                              VideoAvTestFixture.OWNER,
                              VideoAvAnswerServiceTest.all(VideoAvMode.JOINT)));
                    } catch (Throwable problem) {
                      failure.set(problem);
                    }
                  });
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertTrue(fixture.store.operationGate().tryMaintenance().isEmpty());
        assertEquals(
            "answer_capacity_exceeded",
            assertThrows(
                    ApplicationException.class,
                    () ->
                        answers.answer(
                            VideoAvTestFixture.OWNER,
                            VideoAvAnswerServiceTest.all(VideoAvMode.JOINT)))
                .code());
        assertEquals(0, fixture.count("SELECT count(*) FROM video_av_traces"));
      } finally {
        release.countDown();
        caller.join(Duration.ofSeconds(5));
      }
      assertFalse(caller.isAlive());
      proofThread.get().join(Duration.ofSeconds(5));
      assertFalse(proofThread.get().isAlive());
      assertTrue(fixture.store.operationGate().isIdle());
      assertNull(failure.get());
      assertEquals("answered", first.get().status());
      assertEquals(
          "answered",
          answers
              .answer(VideoAvTestFixture.OWNER, VideoAvAnswerServiceTest.all(VideoAvMode.JOINT))
              .status());
      assertEquals(2, fixture.count("SELECT count(*) FROM video_av_traces"));
    }
  }

  @Test
  void interruptedCallerCancelsInFlightProofAndCannotPublishLateTrace() throws Exception {
    var entered = new CountDownLatch(1);
    var cancelled = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var failure = new AtomicReference<Throwable>();
    var interrupted = new AtomicBoolean();
    var proofThread = new AtomicReference<Thread>();
    try (var fixture = new VideoAvTestFixture(directory);
        var answers = fixture.answers(Duration.ofSeconds(10), 1)) {
      fixture.register("library", 1, 1, 16000, true);
      fixture.draft =
          (window, mode) -> {
            proofThread.set(Thread.currentThread());
            entered.countDown();
            try {
              release.await();
              throw new AssertionError("Caller cancellation must interrupt the blocked proof");
            } catch (InterruptedException expected) {
              Thread.currentThread().interrupt();
              cancelled.countDown();
              return new VideoAvModels.Draft(
                  true,
                  List.of(
                      new VideoAvModels.Claim(VideoAvTestFixture.FACT, VideoAvRequirement.JOINT)));
            }
          };
      var caller =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      answers.answer(
                          VideoAvTestFixture.OWNER,
                          VideoAvAnswerServiceTest.all(VideoAvMode.JOINT));
                    } catch (Throwable problem) {
                      failure.set(problem);
                      interrupted.set(Thread.currentThread().isInterrupted());
                    }
                  });
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        caller.interrupt();
        caller.join(Duration.ofSeconds(5));
        assertFalse(caller.isAlive());
        assertTrue(cancelled.await(5, TimeUnit.SECONDS));
        proofThread.get().join(Duration.ofSeconds(5));
        assertFalse(proofThread.get().isAlive());
        assertEquals(
            "answer_timeout", assertInstanceOf(ApplicationException.class, failure.get()).code());
        assertTrue(interrupted.get());
        assertTrue(fixture.verifies.isEmpty());
        assertEquals(0, fixture.count("SELECT count(*) FROM video_av_traces"));
      } finally {
        release.countDown();
        caller.interrupt();
        caller.join(Duration.ofSeconds(5));
      }
    }
  }

  @Test
  void closedAnswerServiceRejectsNewQuestionWithoutAnyProviderWork() {
    try (var fixture = new VideoAvTestFixture(directory)) {
      fixture.register("library", 1, 1, 16000, true);
      var answers = fixture.answers();
      answers.close();
      assertEquals(
          "video_av_answers_unavailable",
          assertThrows(
                  ApplicationException.class,
                  () ->
                      answers.answer(
                          VideoAvTestFixture.OWNER,
                          VideoAvAnswerServiceTest.all(VideoAvMode.JOINT)))
              .code());
      assertEquals(0, fixture.decodes);
      assertEquals(0, fixture.textEmbeds);
      assertEquals(0, fixture.count("SELECT count(*) FROM video_av_traces"));
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new AssertionError("Synthetic proof release was not signalled");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }
}
