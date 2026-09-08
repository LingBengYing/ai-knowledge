package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.vector.RetrievalProjection.Candidate;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.support.AnswerTestContext;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnswerServiceTest {
  @TempDir Path directory;

  @Test
  void explicitEmptyScopeAbstainsWithoutContactingAnyAdapter() {
    try (var fixture = fixture()) {
      fixture.publish("plan.txt", "星港项目的识别码为A-42。");
      var answer = fixture.answers.answer(fixture.owner, command("星港项目的识别码是什么？", List.of()));
      assertEquals("abstained", answer.status());
      assertEquals("empty_scope", answer.reason());
      assertTrue(answer.citations().isEmpty());
      assertTrue(fixture.models.calls.isEmpty());
      assertTrue(fixture.projection.calls.isEmpty());
      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM query_traces"));
    }
  }

  @Test
  void aRealPublishedSegmentProducesServerBoundedAnswerAndSource() {
    try (var fixture = fixture()) {
      String document = fixture.publish("plan.txt", "星港项目的识别码为A-42。");
      var answer =
          fixture.answers.answer(fixture.owner, command("星港项目的识别码是什么？", List.of(document)));
      assertEquals("answered", answer.status());
      assertTrue(answer.answer().contains("A-42"));
      assertFalse(answer.citations().isEmpty());
      var citation = answer.citations().getFirst();
      assertEquals(document, citation.documentId());
      assertEquals("plan.txt", citation.filename());
      assertEquals(1, citation.page());
      assertEquals(
          citation.end() - citation.start(),
          citation.quote().codePointCount(0, citation.quote().length()));
      assertEquals("/v1/sources/" + answer.answerId() + "/1", citation.sourceUrl());
      assertNotEquals(
          citation.revisionId(), fixture.projection.lastScope.documentRevisions().get(document));
      assertEquals(List.of("prepare", "search"), fixture.projection.calls);
      assertEquals(List.of("embed", "rerank", "extract"), fixture.models.calls);
      assertEquals(
          citation, fixture.answers.source(fixture.owner, answer.answerId(), 1).citation());
      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM query_traces"));
      fixture.revoke(document);
      assertEquals(
          FailureKind.NOT_FOUND,
          assertThrows(
                  ApplicationException.class,
                  () -> fixture.answers.source(fixture.owner, answer.answerId(), 1))
              .kind());
    }
  }

  @Test
  void explicitUnavailableDocumentNeverFallsBackToRemainingSelectionOrAll() {
    try (var fixture = fixture()) {
      String document = fixture.publish("plan.txt", "星港项目的识别码为A-42。");
      assertEquals(
          FailureKind.NOT_FOUND,
          assertThrows(
                  ApplicationException.class,
                  () ->
                      fixture.answers.answer(
                          fixture.owner, command("星港项目的识别码是什么？", List.of(document, "missing"))))
              .kind());
      assertTrue(fixture.models.calls.isEmpty());
      assertTrue(fixture.projection.calls.isEmpty());
    }
  }

  @Test
  void nonCandidateSelectedRevocationDuringModelCallPreventsAllAnswerRelease() {
    try (var fixture = fixture()) {
      String first = fixture.publish("plan.txt", "星港项目的识别码为A-42。");
      String second = fixture.publish("other.txt", "另一个计划的资料尚未公开。");
      fixture.projection.results = values -> values.stream().limit(1).toList();
      fixture.models.onExtract = () -> fixture.revoke(second);
      var answer =
          fixture.answers.answer(fixture.owner, command("星港项目的识别码是什么？", List.of(first, second)));
      assertEquals("abstained", answer.status());
      assertEquals("scope_changed", answer.reason());
      assertFalse(answer.answer().contains("A-42"));
      assertTrue(answer.citations().isEmpty());
      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM query_traces"));
      assertEquals(2, fixture.scalar("SELECT COUNT(*) FROM query_trace_documents"));
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM query_trace_evidence"));
    }
  }

  @Test
  void inventedPhysicalCandidateNeverEntersModelContext() {
    try (var fixture = fixture()) {
      String document = fixture.publish("plan.txt", "星港项目的识别码为A-42。");
      fixture.projection.results = values -> List.of(new Candidate("invented", 1));
      var answer =
          fixture.answers.answer(fixture.owner, command("星港项目的识别码是什么？", List.of(document)));
      assertEquals("abstained", answer.status());
      assertTrue(answer.citations().isEmpty());
      assertEquals(List.of("embed"), fixture.models.calls);
    }
  }

  @Test
  void malformedCompleteRankingDoesNotReachExtraction() {
    try (var fixture = fixture()) {
      String document = fixture.publish("plan.txt", "星港项目的识别码为A-42。");
      fixture.models.ranking = values -> List.of(new TextModels.Ranked(0, Double.NaN));
      var answer =
          fixture.answers.answer(fixture.owner, command("星港项目的识别码是什么？", List.of(document)));
      assertEquals("abstained", answer.status());
      assertTrue(answer.citations().isEmpty());
      assertEquals(List.of("embed", "rerank"), fixture.models.calls);
    }
  }

  @Test
  void irrelevantExactQuotesAreNotAProofOfTheQuestion() {
    try (var fixture = fixture()) {
      String document = fixture.publish("plan.txt", "星港项目的识别码为A-42。");
      var answer =
          fixture.answers.answer(fixture.owner, command("火星基地的网络带宽是多少？", List.of(document)));
      assertEquals("abstained", answer.status());
      assertFalse(answer.answer().contains("A-42"));
      assertTrue(answer.citations().isEmpty());
    }
  }

  @Test
  void timeoutDoesNotReleaseAdmissionUntilTheActualWorkExits() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var fixture = new AnswerTestContext(directory, Duration.ofMillis(100), 1)) {
      String document = fixture.publish("plan.txt", "星港项目的识别码为A-42。");
      fixture.models.onEmbed =
          () -> {
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
          };
      try {
        assertEquals(
            FailureKind.TIMEOUT,
            assertThrows(
                    ApplicationException.class,
                    () ->
                        fixture.answers.answer(
                            fixture.owner, command("星港项目的识别码是什么？", List.of(document))))
                .kind());
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertEquals(
            FailureKind.CAPACITY_EXCEEDED,
            assertThrows(
                    ApplicationException.class,
                    () -> fixture.answers.answer(fixture.owner, command("未入库的事实？", List.of())))
                .kind());
        assertEquals(List.of("embed"), fixture.models.calls);
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void anotherPrincipalCannotReadTheOriginalAnswerSource() {
    try (var fixture = fixture()) {
      String document = fixture.publish("plan.txt", "星港项目的识别码为A-42。");
      var answer =
          fixture.answers.answer(fixture.owner, command("星港项目的识别码是什么？", List.of(document)));
      assertEquals("answered", answer.status());
      assertEquals(
          FailureKind.NOT_FOUND,
          assertThrows(
                  ApplicationException.class,
                  () ->
                      fixture.answers.source(
                          new Actor("org-main", "other-reader"), answer.answerId(), 1))
              .kind());
    }
  }

  private AnswerTestContext fixture() {
    return new AnswerTestContext(directory, Duration.ofSeconds(5), 2);
  }

  private static AnswerCommand command(String question, List<String> documents) {
    return new AnswerCommand(question, new DocumentSelection(false, documents));
  }
}
