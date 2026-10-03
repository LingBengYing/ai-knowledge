package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.model.dto.RetrievalTestCommand;
import com.evidence.rag.model.dto.RetrievalTestResult;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Complete-result and actual-body lifetime boundaries for the independent retrieval use case. */
@SuppressWarnings("try")
class TextRetrievalFailureBoundaryTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"absent", "empty", "absent-vector", "dimensions"})
  void incompleteEmbeddingResponseReleasesAdmissionAndNeverSearchesOrReturnsText(String kind) {
    try (var fixture = prepared()) {
      fixture.context.models.embedding =
          ignored ->
              switch (kind) {
                case "absent" -> null;
                case "empty" -> List.of();
                case "absent-vector" -> Collections.singletonList(null);
                case "dimensions" -> List.of(List.of(1.0, 0.0, 0.0));
                default -> throw new AssertionError("Unknown synthetic embedding failure");
              };
      try (var retrieval = service(fixture)) {
        assertSafeFailure(fixture, retrieval, "retrieval_embedding_failed", false);
        awaitIdle(fixture);
        assertEquals(List.of("prepare"), fixture.context.projection.calls);
        fixture.context.models.embedding =
            values -> values.stream().map(value -> List.of(1.0, 0.0)).toList();
        assertEquals("completed", recovered(fixture, retrieval, false).status());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"absent", "over-limit", "absent-tail"})
  void invalidCompleteSearchResponseCannotBeTruncatedToALegitimateFirstHit(String kind) {
    try (var fixture = prepared()) {
      fixture.context.projection.results =
          hits ->
              switch (kind) {
                case "absent" -> null;
                case "over-limit" -> Collections.nCopies(65, hits.getFirst());
                case "absent-tail" -> Arrays.asList(hits.getFirst(), null);
                default -> throw new AssertionError("Unknown synthetic search failure");
              };
      try (var retrieval = service(fixture)) {
        assertSafeFailure(fixture, retrieval, "retrieval_search_failed", true);
        awaitIdle(fixture);
        assertFalse(fixture.context.models.calls.contains("rerank"));
        fixture.context.projection.results = ArrayList::new;
        assertEquals("completed", recovered(fixture, retrieval, false).status());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "absent",
        "absent-rank",
        "negative-index",
        "foreign-index",
        "duplicate-index",
        "nonfinite-score"
      })
  void invalidWholeRankingNeverReturnsTopKOrCreatesAnAnswerTrace(String kind) {
    try (var fixture = prepared()) {
      fixture.context.publish("second.txt", "北京住宿上限为450元。");
      fixture.context.models.ranking =
          ignored ->
              switch (kind) {
                case "absent" -> null;
                case "absent-rank" -> Arrays.asList(new TextModels.Ranked(0, 0.9), null);
                case "negative-index" ->
                    List.of(new TextModels.Ranked(0, 0.9), new TextModels.Ranked(-1, 0.1));
                case "foreign-index" ->
                    List.of(new TextModels.Ranked(0, 0.9), new TextModels.Ranked(2, 0.1));
                case "duplicate-index" ->
                    List.of(new TextModels.Ranked(0, 0.9), new TextModels.Ranked(0, 0.1));
                case "nonfinite-score" ->
                    List.of(new TextModels.Ranked(0, 0.9), new TextModels.Ranked(1, Double.NaN));
                default -> throw new AssertionError("Unknown synthetic ranking failure");
              };
      try (var retrieval = service(fixture)) {
        assertSafeFailure(fixture, retrieval, "retrieval_rerank_failed", true);
        awaitIdle(fixture);
        fixture.context.models.ranking =
            ignored -> List.of(new TextModels.Ranked(0, 0.9), new TextModels.Ranked(1, 0.1));
        assertEquals(1, recovered(fixture, retrieval, true).matches().size());
      }
    }
  }

  @Test
  void unexpectedActualBodyFailureIsSafeAndDoesNotLeakAReservationOrRetireRuntime() {
    try (var fixture = prepared();
        var retrieval = service(fixture)) {
      fixture.context.models.onEmbed =
          () -> {
            throw new AssertionError("Synthetic confidential provider body");
          };
      assertSafeFailure(fixture, retrieval, "retrieval_unavailable", false);
      awaitIdle(fixture);
      assertEquals(1L, fixture.runtime.currentVersion());
      try (var operation = fixture.context.authority.store().operationGate().enter()) {
        assertTrue(fixture.runtime.capture().isOpen());
      }
      fixture.context.models.onEmbed = () -> {};
      assertEquals("completed", recovered(fixture, retrieval, false).status());
    }
  }

  @Test
  void stoppingRetrievalRetainsItsActualBodyAndLeavesRuntimeUsableAfterTheBodyExits()
      throws Exception {
    try (var fixture = prepared();
        var retrieval = service(fixture)) {
      var upstream = new RetainedUpstream();
      fixture.context.models.onEmbed = upstream::run;
      var result = new AtomicReference<RetrievalTestResult>();
      var failure = new AtomicReference<Throwable>();
      Thread caller = call(fixture, retrieval, result, failure);
      try {
        assertTrue(upstream.entered.await(2, TimeUnit.SECONDS));
        retrieval.close();
        retrieval.close();
        assertEquals(
            "retrieval_unavailable",
            assertThrows(
                    ApplicationException.class,
                    () -> retrieval.test(fixture.context.owner, command(false)))
                .code());
        assertTrue(fixture.context.authority.store().operationGate().tryMaintenance().isEmpty());
        assertEquals(1L, fixture.runtime.currentVersion());
        try (var operation = fixture.context.authority.store().operationGate().enter()) {
          assertTrue(fixture.runtime.capture().isOpen());
        }
        assertNull(result.get());
      } finally {
        upstream.release.countDown();
        caller.join(3_000);
      }
      assertFalse(caller.isAlive());
      assertTrue(failure.get() instanceof ApplicationException);
      assertEquals("retrieval_timeout", ((ApplicationException) failure.get()).code());
      assertNull(result.get());
      awaitIdle(fixture);
      assertFalse(fixture.context.projection.calls.contains("search"));
      assertFalse(fixture.context.models.calls.contains("rerank"));
      fixture.context.models.onEmbed = () -> {};
      try (var replacement = service(fixture)) {
        assertEquals("completed", replacement.test(fixture.context.owner, command(false)).status());
      }
    }
  }

  @Test
  void interruptedCallerKeepsItsInterruptAndCannotReturnAnOtherwiseValidPartialResult()
      throws Exception {
    try (var fixture = prepared();
        var retrieval = service(fixture)) {
      var upstream = new RetainedUpstream();
      fixture.context.models.onEmbed = upstream::run;
      var failure = new AtomicReference<Throwable>();
      var interrupted = new AtomicBoolean();
      Thread caller =
          Thread.ofPlatform()
              .start(
                  () -> {
                    try {
                      retrieval.test(fixture.context.owner, command(true));
                    } catch (Throwable stopped) {
                      failure.set(stopped);
                      interrupted.set(Thread.currentThread().isInterrupted());
                    }
                  });
      try {
        assertTrue(upstream.entered.await(2, TimeUnit.SECONDS));
        caller.interrupt();
        caller.join(2_000);
        assertFalse(caller.isAlive());
        assertTrue(interrupted.get());
        assertTrue(failure.get() instanceof ApplicationException);
        assertEquals("retrieval_timeout", ((ApplicationException) failure.get()).code());
        assertTrue(fixture.context.authority.store().operationGate().tryMaintenance().isEmpty());
        assertEquals(
            "retrieval_capacity_exceeded",
            assertThrows(
                    ApplicationException.class,
                    () -> retrieval.test(fixture.context.owner, command(false)))
                .code());
      } finally {
        upstream.release.countDown();
        caller.join(3_000);
      }
      awaitIdle(fixture);
      assertFalse(fixture.context.models.calls.contains("extract"));
      assertFalse(fixture.context.models.calls.contains("rerank"));
      assertEquals(0, fixture.context.scalar("SELECT COUNT(*) FROM query_traces"));
    }
  }

  @Test
  void finalAuthorityQualificationAfterHydrationStillRejectsTheWholeMaterialList() {
    try (var fixture = prepared()) {
      var scope =
          fixture.context.evidence.snapshot(
              fixture.context.owner, DocumentSelection.allDocuments(), fixture.context.target);
      var generations = new LinkedHashMap<String, String>();
      for (var publication : scope.publications()) {
        generations.put(publication.documentId(), publication.projectionGenerationId());
      }
      var query =
          new RetrievalProjection.Query(
              "上海住宿上限是多少？",
              List.of(1.0, 0.0),
              new RetrievalProjection.AuthorizedScope(
                  fixture.context.owner.workspaceId(), generations),
              64,
              RetrievalProjection.SearchMode.HYBRID);
      var ids =
          fixture.context.projection.data.search(query).stream()
              .map(RetrievalProjection.Candidate::segmentId)
              .toList();
      assertEquals(1, ids.size());
      var checks = new AtomicInteger();
      assertEquals(
          "configuration_changed",
          assertThrows(
                  ApplicationException.class,
                  () ->
                      fixture.context.evidence.finishRetrieval(
                          scope, ids, fixture.context.target, () -> checks.incrementAndGet() == 1))
              .code());
      assertEquals(2, checks.get());
      assertEquals(
          1,
          fixture
              .context
              .evidence
              .finishRetrieval(scope, ids, fixture.context.target, () -> true)
              .size());
      assertTrue(fixture.context.models.calls.isEmpty());
      assertEquals(0, fixture.context.scalar("SELECT COUNT(*) FROM query_traces"));
    }
  }

  @ParameterizedTest
  @CsvSource({"9,1", "180001,1", "1000,0", "1000,9"})
  void unsafeRuntimeBudgetsCannotAllocateAWorkerOrBorrowTheLibrary(long millis, int concurrency) {
    try (var fixture = prepared()) {
      assertThrows(
          ApplicationException.class,
          () ->
              new TextRetrievalTestService(
                  fixture.context.evidence,
                  fixture.runtime,
                  Duration.ofMillis(millis),
                  concurrency));
      assertTrue(fixture.context.authority.store().operationGate().isIdle());
      assertTrue(fixture.context.models.calls.isEmpty());
      assertTrue(fixture.context.projection.calls.isEmpty());
    }
  }

  private ManagedTextTestFixture prepared() {
    var fixture = new ManagedTextTestFixture(directory);
    fixture.context.publish("policy.txt", "上海住宿上限为650元。");
    fixture.activate(1);
    return fixture;
  }

  private static TextRetrievalTestService service(ManagedTextTestFixture fixture) {
    return new TextRetrievalTestService(
        fixture.context.evidence, fixture.runtime, Duration.ofSeconds(5), 1);
  }

  private static RetrievalTestCommand command(boolean rerank) {
    return new RetrievalTestCommand(
        new AnswerCommand("上海住宿上限是多少？", DocumentSelection.allDocuments()), 1, rerank);
  }

  private static void assertSafeFailure(
      ManagedTextTestFixture fixture,
      TextRetrievalTestService retrieval,
      String code,
      boolean rerank) {
    var error =
        assertThrows(
            ApplicationException.class,
            () -> retrieval.test(fixture.context.owner, command(rerank)));
    assertEquals(code, error.code());
    assertNull(error.getCause());
    assertFalse(error.getMessage().contains("confidential"));
    assertFalse(fixture.context.models.calls.contains("extract"));
    assertEquals(0, fixture.context.scalar("SELECT COUNT(*) FROM query_traces"));
  }

  private static Thread call(
      ManagedTextTestFixture fixture,
      TextRetrievalTestService retrieval,
      AtomicReference<RetrievalTestResult> result,
      AtomicReference<Throwable> failure) {
    return Thread.ofPlatform()
        .start(
            () -> {
              try {
                result.set(retrieval.test(fixture.context.owner, command(true)));
              } catch (Throwable stopped) {
                failure.set(stopped);
              }
            });
  }

  private static void awaitIdle(ManagedTextTestFixture fixture) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (!fixture.context.authority.store().operationGate().isIdle()
        && System.nanoTime() < deadline) {
      try {
        Thread.sleep(5);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Test observer interrupted", interrupted);
      }
    }
    assertTrue(fixture.context.authority.store().operationGate().isIdle());
  }

  private static RetrievalTestResult recovered(
      ManagedTextTestFixture fixture, TextRetrievalTestService retrieval, boolean rerank) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (true) {
      try {
        return retrieval.test(fixture.context.owner, command(rerank));
      } catch (ApplicationException finishing) {
        if (!finishing.code().equals("retrieval_capacity_exceeded")
            || System.nanoTime() >= deadline) {
          throw finishing;
        }
        // The public result can precede the actual body's final admission release.
        try {
          Thread.sleep(5);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new AssertionError("Test observer interrupted", interrupted);
        }
      }
    }
  }

  private static final class RetainedUpstream {
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    void run() {
      entered.countDown();
      boolean done = false;
      while (!done) {
        try {
          done = release.await(20, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
          // Retain the actual synthetic provider body until the test explicitly releases it.
        }
      }
    }
  }
}
