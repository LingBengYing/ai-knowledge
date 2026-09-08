package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.VerifiedRevision;
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

/** Configuration can change after the last external phase while authority access is waiting. */
class AnswerCommitRaceTest {
  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  @TempDir Path directory;

  @Test
  void modelChangeDuringAuthorityWaitCannotCommitOldConfigurationAnswer() throws Exception {
    try (var fixture = new AnswerTestContext(directory, TIMEOUT, 1)) {
      fixture.publish("plan.txt", "星港项目的识别码为A-42。");
      var window = new CommitWindow();
      fixture.models.onExtract = window::extract;
      fixture.models.onRevision = window::identityRead;
      var result =
          answerAcrossChange(
              fixture,
              fixture.answers,
              window,
              () -> fixture.models.modelRevision = "different-model-v2");
      assertConfigurationRefusal(fixture, result);
    }
  }

  @Test
  void projectionChangeDuringAuthorityWaitCannotCommitOldConfigurationAnswer() throws Exception {
    try (var fixture = new AnswerTestContext(directory, TIMEOUT, 1)) {
      fixture.publish("plan.txt", "星港项目的识别码为A-42。");
      var window = new CommitWindow();
      var projection = new ChangingProjection(fixture.projection, window);
      fixture.models.onExtract = window::extract;
      try (var answers =
          new AnswerService(
              fixture.evidence, fixture.models, projection, fixture.target, TIMEOUT, 1)) {
        var result =
            answerAcrossChange(
                fixture, answers, window, () -> projection.identity.set("a".repeat(64)));
        assertConfigurationRefusal(fixture, result);
      }
    }
  }

  private static AnswerResult answerAcrossChange(
      AnswerTestContext fixture, AnswerService answers, CommitWindow window, Runnable change)
      throws Exception {
    var result = new AtomicReference<AnswerResult>();
    var failure = new AtomicReference<Throwable>();
    Thread caller =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    result.set(
                        answers.answer(
                            fixture.owner,
                            new AnswerCommand("星港项目的识别码是什么？", DocumentSelection.allDocuments())));
                  } catch (Throwable error) {
                    failure.set(error);
                  }
                });
    try {
      await(window.extractEntered, "Extraction did not start");
      synchronized (fixture.authority.store()) {
        window.releaseExtract.countDown();
        await(window.oldIdentityRead, "The post-extraction identity was not read");
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (window.worker.get().getState() != Thread.State.BLOCKED
            && System.nanoTime() < until) {
          LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        assertEquals(
            Thread.State.BLOCKED,
            window.worker.get().getState(),
            "The answer must be waiting on the actual authority Store monitor");
        change.run();
      }
      assertTrue(caller.join(Duration.ofSeconds(2)), "Answer did not finish after releasing Store");
      assertNull(failure.get(), () -> "Unexpected query failure: " + failure.get());
      assertNotNull(result.get());
      return result.get();
    } finally {
      window.releaseExtract.countDown();
      caller.interrupt();
      caller.join(Duration.ofSeconds(2));
    }
  }

  private static void assertConfigurationRefusal(AnswerTestContext fixture, AnswerResult result) {
    assertEquals("abstained", result.status());
    assertEquals("configuration_changed", result.reason());
    assertTrue(result.citations().isEmpty());
    assertFalse(result.answer().contains("A-42"));
    assertEquals(
        1,
        fixture.scalar(
            "SELECT COUNT(*) FROM query_traces WHERE outcome='abstained' "
                + "AND reason_code='configuration_changed' AND answer_sha256 IS NULL"));
    assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM query_trace_evidence"));
  }

  private static void await(CountDownLatch latch, String message) {
    try {
      assertTrue(latch.await(2, TimeUnit.SECONDS), message);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(message, interrupted);
    }
  }

  private static final class CommitWindow {
    private final CountDownLatch extractEntered = new CountDownLatch(1);
    private final CountDownLatch releaseExtract = new CountDownLatch(1);
    private final CountDownLatch oldIdentityRead = new CountDownLatch(1);
    private final AtomicBoolean extracting = new AtomicBoolean();
    private final AtomicReference<Thread> worker = new AtomicReference<>();

    private void extract() {
      worker.set(Thread.currentThread());
      extracting.set(true);
      extractEntered.countDown();
      await(releaseExtract, "Extraction was not released");
    }

    private void identityRead() {
      if (extracting.get()) {
        oldIdentityRead.countDown();
      }
    }
  }

  private static final class ChangingProjection implements RetrievalProjection {
    private final RetrievalProjection delegate;
    private final CommitWindow window;
    private final AtomicReference<String> identity;

    private ChangingProjection(RetrievalProjection delegate, CommitWindow window) {
      this.delegate = delegate;
      this.window = window;
      identity = new AtomicReference<>(delegate.identity());
    }

    @Override
    public String identity() {
      String current = identity.get();
      window.identityRead();
      return current;
    }

    @Override
    public VerifiedRevision verify(RevisionManifest manifest) {
      return delegate.verify(manifest);
    }

    @Override
    public void initialize() {
      delegate.initialize();
    }

    @Override
    public void prepareSearch() {
      delegate.prepareSearch();
    }

    @Override
    public void upsert(List<Entry> entries) {
      delegate.upsert(entries);
    }

    @Override
    public List<Candidate> search(Query query) {
      return delegate.search(query);
    }
  }
}
