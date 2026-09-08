package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.model.dto.AnswerResult;
import com.evidence.rag.support.AnswerTestContext;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AnswerLifecycleTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"prepare", "search", "rerank", "extract"})
  void totalDeadlineHoldsAdmissionUntilEachActualRemoteStageExits(String stage) throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var fixture = new AnswerTestContext(directory, Duration.ofMillis(500), 1)) {
      fixture.publish("plan.txt", "星港项目的识别码为A-42。");
      Runnable blocked = () -> waitIgnoringInterruption(entered, release);
      switch (stage) {
        case "prepare" -> fixture.projection.onPrepare = blocked;
        case "search" -> fixture.projection.onSearch = blocked;
        case "rerank" -> fixture.models.onRerank = blocked;
        case "extract" -> fixture.models.onExtract = blocked;
        default -> throw new AssertionError(stage);
      }
      try {
        assertEquals(
            FailureKind.TIMEOUT,
            assertThrows(ApplicationException.class, () -> answer(fixture)).kind());
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertEquals(
            FailureKind.CAPACITY_EXCEEDED,
            assertThrows(ApplicationException.class, () -> empty(fixture)).kind());
        List<String> callsBeforeExit = List.copyOf(fixture.models.calls);
        release.countDown();
        assertEquals("empty_scope", awaitAdmission(fixture).reason());
        assertEquals(callsBeforeExit, fixture.models.calls);
        assertEquals(2, fixture.scalar("SELECT COUNT(*) FROM query_traces"));
        assertEquals(
            1,
            fixture.scalar(
                "SELECT COUNT(*) FROM query_traces WHERE reason_code='processing_timeout'"));
        assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM query_trace_evidence"));
        assertEquals(
            0, fixture.scalar("SELECT COUNT(*) FROM query_traces WHERE outcome='answered'"));
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void callerInterruptionIsPreservedWhileActualWorkerKeepsItsPermit() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var completed = new CountDownLatch(1);
    var failure = new AtomicReference<ApplicationException>();
    var interrupted = new AtomicBoolean();
    try (var fixture = new AnswerTestContext(directory, Duration.ofSeconds(5), 1)) {
      fixture.publish("plan.txt", "星港项目的识别码为A-42。");
      fixture.models.onEmbed = () -> waitIgnoringInterruption(entered, release);
      var caller =
          Thread.ofVirtual()
              .name("test-answer-caller")
              .start(
                  () -> {
                    try {
                      answer(fixture);
                    } catch (ApplicationException expected) {
                      failure.set(expected);
                      interrupted.set(Thread.currentThread().isInterrupted());
                    } finally {
                      completed.countDown();
                    }
                  });
      try {
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        caller.interrupt();
        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertEquals(FailureKind.TIMEOUT, failure.get().kind());
        assertTrue(interrupted.get());
        assertEquals(
            FailureKind.CAPACITY_EXCEEDED,
            assertThrows(ApplicationException.class, () -> empty(fixture)).kind());
        release.countDown();
        assertEquals("empty_scope", awaitAdmission(fixture).reason());
        assertEquals(List.of("embed"), fixture.models.calls);
        assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM query_trace_evidence"));
      } finally {
        release.countDown();
        caller.interrupt();
        caller.join(2000);
      }
    }
  }

  @Test
  void closingInterruptsWorkAndCannotReturnAnAnsweredTrace() throws Exception {
    var entered = new CountDownLatch(1);
    var waiting = new CountDownLatch(1);
    var completed = new CountDownLatch(1);
    var interrupted = new AtomicBoolean();
    var failure = new AtomicReference<ApplicationException>();
    try (var fixture = new AnswerTestContext(directory, Duration.ofSeconds(5), 1)) {
      fixture.publish("plan.txt", "星港项目的识别码为A-42。");
      fixture.models.onExtract =
          () -> {
            entered.countDown();
            try {
              waiting.await();
            } catch (InterruptedException expected) {
              interrupted.set(true);
              Thread.currentThread().interrupt();
            }
          };
      var caller =
          Thread.ofVirtual()
              .name("test-answer-shutdown")
              .start(
                  () -> {
                    try {
                      answer(fixture);
                    } catch (ApplicationException expected) {
                      failure.set(expected);
                    } finally {
                      completed.countDown();
                    }
                  });
      try {
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        fixture.answers.close();
        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertTrue(interrupted.get());
        assertEquals(FailureKind.TIMEOUT, failure.get().kind());
        caller.join(2000);
        assertFalse(caller.isAlive());
        assertEquals(
            FailureKind.UNAVAILABLE,
            assertThrows(ApplicationException.class, () -> empty(fixture)).kind());
        assertEquals(
            0, fixture.scalar("SELECT COUNT(*) FROM query_traces WHERE outcome='answered'"));
        assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM query_trace_evidence"));
      } finally {
        waiting.countDown();
        caller.interrupt();
        caller.join(2000);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void closingReportsUndrainedWorkAndCanBeRetried(boolean interruptClose) throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var exited = new CountDownLatch(1);
    var completed = new CountDownLatch(1);
    var failure = new AtomicReference<ApplicationException>();
    try (var fixture = new AnswerTestContext(directory, Duration.ofSeconds(30), 1)) {
      fixture.publish("plan.txt", "星港项目的识别码为A-42。");
      fixture.models.onExtract =
          () -> {
            try {
              waitIgnoringInterruption(entered, release);
            } finally {
              exited.countDown();
            }
          };
      var caller =
          Thread.ofVirtual()
              .name("test-answer-undrained")
              .start(
                  () -> {
                    try {
                      answer(fixture);
                    } catch (ApplicationException expected) {
                      failure.set(expected);
                    } finally {
                      completed.countDown();
                    }
                  });
      try {
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        if (interruptClose) {
          Thread.currentThread().interrupt();
        }
        try {
          var shutdown = assertThrows(ApplicationException.class, fixture.answers::close);
          assertEquals(FailureKind.TIMEOUT, shutdown.kind());
          assertEquals(
              interruptClose ? "answer_shutdown_interrupted" : "answer_shutdown_timeout",
              shutdown.code());
          assertEquals(interruptClose, Thread.currentThread().isInterrupted());
        } finally {
          Thread.interrupted();
        }
        assertEquals(1L, exited.getCount(), "Ignoring interruption must not be reported as exit");
        assertEquals(
            FailureKind.UNAVAILABLE,
            assertThrows(ApplicationException.class, () -> empty(fixture)).kind());
        var closeStarted = new CountDownLatch(1);
        var closeCompleted = new CountDownLatch(1);
        var closeFailure = new AtomicReference<Throwable>();
        var closer =
            Thread.ofVirtual()
                .name("test-answer-repeated-close")
                .start(
                    () -> {
                      closeStarted.countDown();
                      try {
                        fixture.answers.close();
                      } catch (Throwable unexpected) {
                        closeFailure.set(unexpected);
                      } finally {
                        closeCompleted.countDown();
                      }
                    });
        try {
          assertTrue(closeStarted.await(2, TimeUnit.SECONDS));
          assertFalse(
              closeCompleted.await(100, TimeUnit.MILLISECONDS),
              "Repeated close returned before the blocked worker exited");
          release.countDown();
          assertTrue(closeCompleted.await(2, TimeUnit.SECONDS));
          assertNull(closeFailure.get());
        } finally {
          release.countDown();
          closer.join(2000);
          assertFalse(closer.isAlive());
        }
        assertTrue(exited.await(2, TimeUnit.SECONDS));
        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertEquals(FailureKind.TIMEOUT, failure.get().kind());
        assertEquals(
            1,
            fixture.scalar(
                "SELECT COUNT(*) FROM query_traces WHERE reason_code='processing_timeout'"));
        assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM query_trace_evidence"));
        assertEquals(
            0, fixture.scalar("SELECT COUNT(*) FROM query_traces WHERE outcome='answered'"));
      } finally {
        release.countDown();
        Thread.interrupted();
        fixture.answers.close();
        caller.join(2000);
        assertFalse(caller.isAlive());
      }
    }
  }

  private static void waitIgnoringInterruption(CountDownLatch entered, CountDownLatch release) {
    entered.countDown();
    boolean interrupted = false;
    while (release.getCount() != 0) {
      try {
        release.await();
      } catch (InterruptedException expected) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private static AnswerResult awaitAdmission(AnswerTestContext fixture) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (true) {
      try {
        return empty(fixture);
      } catch (ApplicationException busy) {
        assertEquals(FailureKind.CAPACITY_EXCEEDED, busy.kind());
        assertTrue(System.nanoTime() < deadline, "Actual worker did not release admission");
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
      }
    }
  }

  private static AnswerResult answer(AnswerTestContext fixture) {
    return fixture.answers.answer(
        fixture.owner, new AnswerCommand("星港项目的识别码是什么？", DocumentSelection.allDocuments()));
  }

  private static AnswerResult empty(AnswerTestContext fixture) {
    return fixture.answers.answer(
        fixture.owner, new AnswerCommand("尚未选择资料的问题？", DocumentSelection.selected(List.of())));
  }
}
