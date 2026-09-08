package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.model.dto.AnswerResult;
import com.evidence.rag.repository.DocumentLifecycleRepository;
import com.evidence.rag.repository.IndexingRepository;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.AnswerTestContext;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real parsing/publication and lifecycle; remote stand-ins only supply deterministic evidence. */
class AnswerRemovalTest {
  @TempDir Path directory;

  @Test
  void removingPublishedEvidenceInvalidatesItsPreviouslySuccessfulAnswerSource() {
    try (var fixture = new AnswerTestContext(directory, Duration.ofSeconds(5), 1)) {
      String document = fixture.publish("plan.txt", "星港项目的识别码为A-42。");
      var answer = fixture.answers.answer(fixture.owner, command(List.of(document)));
      assertEquals("answered", answer.status());
      assertEquals(
          answer.citations().getFirst(),
          fixture.answers.source(fixture.owner, answer.answerId(), 1).citation());

      assertEquals(
          "pending", lifecycle(fixture).removeDocument(fixture.owner, document).cleanupStatus());

      assertEquals(
          FailureKind.NOT_FOUND,
          assertThrows(
                  ApplicationException.class,
                  () -> fixture.answers.source(fixture.owner, answer.answerId(), 1))
              .kind());
      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM query_traces WHERE outcome='answered'"));
      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM query_trace_documents"));
      assertEquals(
          answer.citations().size(), fixture.scalar("SELECT COUNT(*) FROM query_trace_evidence"));
      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM index_publications"));
    }
  }

  @Test
  void removedSelectionNeverFallsBackToTheRemainingDocumentOrAll() {
    try (var fixture = new AnswerTestContext(directory, Duration.ofSeconds(5), 1)) {
      String removed = fixture.publish("removed.txt", "星港项目的识别码为A-42。");
      String remaining = fixture.publish("remaining.txt", "另一个项目的识别码为B-55。");
      lifecycle(fixture).removeDocument(fixture.owner, removed);
      for (var selected : List.of(List.of(removed), List.of(removed, remaining))) {
        assertEquals(
            FailureKind.NOT_FOUND,
            assertThrows(
                    ApplicationException.class,
                    () -> fixture.answers.answer(fixture.owner, command(selected)))
                .kind());
      }
      assertTrue(fixture.models.calls.isEmpty());
      assertTrue(fixture.projection.calls.isEmpty());
    }
  }

  @Test
  void allDocumentsFiltersBeforeRetrievalAndStillAnswersFromRemainingEvidence() {
    try (var fixture = new AnswerTestContext(directory, Duration.ofSeconds(5), 1)) {
      String removed = fixture.publish("removed.txt", "星港项目的识别码为X-99。");
      String remaining = fixture.publish("remaining.txt", "星港项目的识别码为A-42。");
      lifecycle(fixture).removeDocument(fixture.owner, removed);
      var result =
          fixture.answers.answer(
              fixture.owner, new AnswerCommand("星港项目的识别码是什么？", DocumentSelection.allDocuments()));
      assertEquals("answered", result.status());
      assertEquals(Set.of(remaining), fixture.projection.lastScope.documentRevisions().keySet());
      assertTrue(
          result.citations().stream()
              .allMatch(citation -> remaining.equals(citation.documentId())));
      assertFalse(
          fixture.models.lastEvidence.stream().anyMatch(value -> value.text().contains("X-99")));
      assertEquals(2, fixture.scalar("SELECT COUNT(*) FROM index_publications"));
    }
  }

  @Test
  void allRemovedDocumentsReturnEmptyScopeWithoutAnyExternalRequest() {
    try (var fixture = new AnswerTestContext(directory, Duration.ofSeconds(5), 1)) {
      String removed = fixture.publish("removed.txt", "星港项目的识别码为A-42。");
      lifecycle(fixture).removeDocument(fixture.owner, removed);
      var result =
          fixture.answers.answer(
              fixture.owner, new AnswerCommand("星港项目的识别码是什么？", DocumentSelection.allDocuments()));
      assertEquals("abstained", result.status());
      assertEquals("empty_scope", result.reason());
      assertTrue(fixture.models.calls.isEmpty());
      assertTrue(fixture.projection.calls.isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"embed", "prepare", "search", "rerank", "extract"})
  void deletingANonCandidateSelectedDocumentInvalidatesEveryExternalPhase(String phase) {
    try (var fixture = new AnswerTestContext(directory, Duration.ofSeconds(5), 1)) {
      String cited = fixture.publish("cited.txt", "星港项目的识别码为A-42。");
      String dependency = fixture.publish("dependency.txt", "另一个项目的资料尚未公开。");
      onlyCandidatesFrom(fixture, cited);
      Runnable remove = () -> lifecycle(fixture).removeDocument(fixture.owner, dependency);
      switch (phase) {
        case "embed" -> fixture.models.onEmbed = remove;
        case "prepare" -> fixture.projection.onPrepare = remove;
        case "search" -> fixture.projection.onSearch = remove;
        case "rerank" -> fixture.models.onRerank = remove;
        case "extract" -> fixture.models.onExtract = remove;
        default -> throw new AssertionError("Unknown test phase");
      }
      var result = fixture.answers.answer(fixture.owner, command(List.of(cited, dependency)));
      assertScopeRefusal(fixture, result);
      assertFalse(
          fixture.models.lastEvidence.stream().anyMatch(value -> value.text().contains("尚未公开")));
      assertEquals(2, fixture.scalar("SELECT COUNT(*) FROM query_trace_documents"));
    }
  }

  @Test
  void deletingAnUnselectedDocumentDoesNotInvalidateTheExplicitlySelectedAnswer() {
    try (var fixture = new AnswerTestContext(directory, Duration.ofSeconds(5), 1)) {
      String cited = fixture.publish("cited.txt", "星港项目的识别码为A-42。");
      String other = fixture.publish("other.txt", "另一个项目的资料尚未公开。");
      fixture.models.onExtract = () -> lifecycle(fixture).removeDocument(fixture.owner, other);
      var result = fixture.answers.answer(fixture.owner, command(List.of(cited)));
      assertEquals("answered", result.status());
      assertEquals(
          cited,
          fixture.answers.source(fixture.owner, result.answerId(), 1).citation().documentId());
    }
  }

  @Test
  void oldSourceRequiresTheWholeOriginalScopeEvenWhenTheRemovedDocumentWasNotCited() {
    try (var fixture = new AnswerTestContext(directory, Duration.ofSeconds(5), 1)) {
      String cited = fixture.publish("cited.txt", "星港项目的识别码为A-42。");
      String dependency = fixture.publish("dependency.txt", "另一个项目的资料尚未公开。");
      onlyCandidatesFrom(fixture, cited);
      var result = fixture.answers.answer(fixture.owner, command(List.of(cited, dependency)));
      assertEquals("answered", result.status());
      assertTrue(result.citations().stream().allMatch(value -> cited.equals(value.documentId())));
      assertEquals(
          cited,
          fixture.answers.source(fixture.owner, result.answerId(), 1).citation().documentId());
      lifecycle(fixture).removeDocument(fixture.owner, dependency);
      assertEquals(
          FailureKind.NOT_FOUND,
          assertThrows(
                  ApplicationException.class,
                  () -> fixture.answers.source(fixture.owner, result.answerId(), 1))
              .kind());
      assertEquals(2, fixture.scalar("SELECT COUNT(*) FROM query_trace_documents"));
      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM query_traces WHERE outcome='answered'"));
    }
  }

  @Test
  void deletionWhileWaitingForTheFinalStoreLockCannotCommitAnOldScope() throws Exception {
    try (var fixture = new AnswerTestContext(directory, Duration.ofSeconds(8), 1)) {
      String cited = fixture.publish("cited.txt", "星港项目的识别码为A-42。");
      String dependency = fixture.publish("dependency.txt", "另一个项目的资料尚未公开。");
      onlyCandidatesFrom(fixture, cited);
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var identityRead = new CountDownLatch(1);
      var extracting = new AtomicBoolean();
      var worker = new AtomicReference<Thread>();
      fixture.models.onExtract =
          () -> {
            worker.set(Thread.currentThread());
            extracting.set(true);
            entered.countDown();
            await(release);
          };
      fixture.models.onRevision =
          () -> {
            if (extracting.get()) identityRead.countDown();
          };
      var result = new AtomicReference<AnswerResult>();
      var failure = new AtomicReference<Throwable>();
      Thread caller =
          Thread.ofPlatform()
              .start(
                  () -> {
                    try {
                      result.set(
                          fixture.answers.answer(
                              fixture.owner, command(List.of(cited, dependency))));
                    } catch (Throwable error) {
                      failure.set(error);
                    }
                  });
      try {
        await(entered);
        synchronized (fixture.authority.store()) {
          release.countDown();
          await(identityRead);
          long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
          while (worker.get().getState() != Thread.State.BLOCKED && System.nanoTime() < until) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
          }
          assertEquals(
              Thread.State.BLOCKED,
              worker.get().getState(),
              "Answer must wait on the actual authority monitor");
          lifecycle(fixture).removeDocument(fixture.owner, dependency);
        }
        assertTrue(caller.join(Duration.ofSeconds(3)));
        assertNull(failure.get());
        assertNotNull(result.get());
        assertScopeRefusal(fixture, result.get());
      } finally {
        release.countDown();
        caller.interrupt();
        assertTrue(caller.join(Duration.ofSeconds(3)), "Query caller must actually exit");
      }
    }
  }

  private static void onlyCandidatesFrom(AnswerTestContext fixture, String document) {
    var snapshot =
        fixture.evidence.snapshot(
            fixture.owner, new DocumentSelection(false, List.of(document)), fixture.target);
    var scope =
        new RetrievalProjection.AuthorizedScope(
            fixture.owner.workspaceId(),
            Map.of(document, snapshot.publications().getFirst().projectionGenerationId()));
    var ids =
        fixture
            .projection
            .data
            .search(new RetrievalProjection.Query("星港项目的识别码是什么？", List.of(1.0, 0.0), scope, 64))
            .stream()
            .map(RetrievalProjection.Candidate::segmentId)
            .collect(Collectors.toSet());
    assertFalse(ids.isEmpty());
    fixture.projection.results =
        values -> values.stream().filter(value -> ids.contains(value.segmentId())).toList();
  }

  private static void assertScopeRefusal(AnswerTestContext fixture, AnswerResult result) {
    assertEquals("abstained", result.status());
    assertEquals("scope_changed", result.reason());
    assertTrue(result.citations().isEmpty());
    assertFalse(result.answer().contains("A-42"));
    assertEquals(
        1,
        fixture.scalar(
            "SELECT COUNT(*) FROM query_traces WHERE outcome='abstained' AND reason_code='scope_changed' AND answer_sha256 IS NULL"));
    assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM query_trace_evidence"));
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(3, TimeUnit.SECONDS), "Lifecycle test phase did not arrive");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Lifecycle test interrupted", interrupted);
    }
  }

  private static DocumentLifecycleService lifecycle(AnswerTestContext fixture) {
    var store = fixture.authority.store();
    return new DocumentLifecycleService(
        store,
        new DocumentLifecycleRepository(store),
        new ManagementRepository(store),
        new IngestionRepository(store),
        new IndexingRepository(store),
        new DocumentPermissionPolicy());
  }

  private static AnswerCommand command(List<String> documents) {
    return new AnswerCommand("星港项目的识别码是什么？", new DocumentSelection(false, documents));
  }
}
