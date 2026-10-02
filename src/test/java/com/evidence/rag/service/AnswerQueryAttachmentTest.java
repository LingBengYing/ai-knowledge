package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.AnswerEligibility;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.QueryAttachment;
import com.evidence.rag.model.domain.QueryTrace;
import com.evidence.rag.model.domain.TraceDraft;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.model.dto.AnswerResult;
import com.evidence.rag.model.dto.QueryAnswerMode;
import com.evidence.rag.model.dto.QueryAttachmentCommand;
import com.evidence.rag.support.AnswerTestContext;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnswerQueryAttachmentTest {
  @TempDir Path directory;

  @Test
  void attachmentHintsNeverBecomeTextEvidenceAndOriginalCitationSurvivesRestart() {
    String answerId;
    try (var context = context()) {
      String doc = context.publish("plan.txt", "星港项目的识别码为A-42。");
      var vision = new QueryAttachmentServiceTest.Vision();
      try (var answers = answers(context, vision)) {
        var envelope = answers.answerAttached(context.owner, command("星港项目的识别码是什么？", List.of(doc)));
        var answer = (AnswerResult) envelope.result();
        assertEquals("answered", answer.status(), answer.reason());
        assertTrue(answer.answer().contains("A-42"));
        assertFalse(context.models.lastEvidence.toString().contains("123"));
        assertEquals(1, vision.calls);
        assertEquals(1, envelope.queryAttachments().size());
        assertEquals("prepared", envelope.queryAttachments().getFirst().status());
        assertEquals(1, context.scalar("SELECT COUNT(*) FROM documents"));
        assertEquals(1, context.scalar("SELECT COUNT(*) FROM query_trace_preparations"));
        answerId = answer.answerId();
        assertEquals(doc, answers.source(context.owner, answerId, 1).citation().documentId());
      }
    }
    try (var reopened = context()) {
      assertTrue(
          reopened.answers.source(reopened.owner, answerId, 1).citation().quote().contains("A-42"));
      assertTrue(reopened.models.calls.isEmpty());
    }
  }

  @Test
  void attachmentOnlyPasswordDoesNotRepairAbsentLibraryFact() {
    try (var context = context()) {
      String doc = context.publish("plan.txt", "星港项目的识别码为A-42。");
      try (var answers = answers(context, new QueryAttachmentServiceTest.Vision())) {
        var result =
            (AnswerResult)
                answers
                    .answerAttached(context.owner, command("星港项目的密码是什么？", List.of(doc)))
                    .result();
        assertEquals("abstained", result.status());
        assertTrue(result.citations().isEmpty());
        assertFalse(result.answer().contains("123"));
        assertEquals(1, context.scalar("SELECT COUNT(*) FROM query_trace_attachments"));
      }
    }
  }

  @Test
  void emptyAndUnavailableSelectionCompileNothingAndNeverFallBack() {
    try (var context = context()) {
      var vision = new QueryAttachmentServiceTest.Vision();
      try (var answers = answers(context, vision)) {
        var empty = answers.answerAttached(context.owner, command("问题？", List.of()));
        assertEquals("empty_scope", empty.result().reason());
        assertEquals("failed", empty.queryAttachments().getFirst().status());
        assertEquals("empty_scope", empty.queryAttachments().getFirst().reason());
        assertThrows(
            ApplicationException.class,
            () -> answers.answerAttached(context.owner, command("问题？", List.of("unavailable"))));
        assertEquals(0, vision.calls);
        assertTrue(context.models.calls.isEmpty());
        assertEquals(1, context.scalar("SELECT COUNT(*) FROM query_traces"));
      }
    }
  }

  @Test
  void failedDecodeStillRecordsSourceHashWithoutInventedPreparation() {
    try (var context = context()) {
      String doc = context.publish("plan.txt", "星港项目的识别码为A-42。");
      try (var answers = answers(context, new QueryAttachmentServiceTest.Vision())) {
        var invalid =
            new QueryAttachmentCommand(
                new AnswerCommand("星港项目的识别码是什么？", DocumentSelection.selected(List.of(doc))),
                QueryAnswerMode.TEXT,
                List.of(new QueryAttachment("bad.png", "image/png", new byte[] {1, 2, 3})));
        var result = answers.answerAttached(context.owner, invalid);
        assertEquals("abstained", result.result().status());
        assertEquals("failed", result.queryAttachments().getFirst().status());
        assertEquals(1, context.scalar("SELECT COUNT(*) FROM query_trace_attachments"));
        assertEquals(0, context.scalar("SELECT COUNT(*) FROM query_trace_attachment_images"));
      }
    }
  }

  @Test
  void finalEligibilityRefusalRetainsPreparedHashOnlyAuditAndClearsCitations() {
    try (var context = context()) {
      var scope =
          context.evidence.snapshot(
              context.owner, DocumentSelection.selected(List.of()), context.target);
      var queryTrace =
          QueryTrace.prepared(QueryAttachmentAnswerFixture.prepared("原始问题？"), "ranking-v1");
      var draft =
          new TraceDraft(
                  queryTrace.questionSha256(),
                  null,
                  "abstained",
                  "no_evidence",
                  context.target.modelRevision(),
                  AnswerService.PROMPT_REVISION,
                  "test-policy-v1",
                  List.of())
              .withQueryTrace(queryTrace);
      var receipt =
          context.evidence.finish(scope, draft, () -> AnswerEligibility.CONFIGURATION_CHANGED);
      assertEquals("configuration_changed", receipt.reasonCode());
      assertEquals(
          1,
          context.scalar("SELECT COUNT(*) FROM query_trace_preparations WHERE status='prepared'"));
      assertEquals(1, context.scalar("SELECT COUNT(*) FROM query_trace_attachments"));
      assertEquals(0, context.scalar("SELECT COUNT(*) FROM query_trace_evidence"));
    }
  }

  @Test
  void emptyAttachmentEnvelopeKeepsLegacyCallsDespiteUnusedMediaProfileChange() {
    try (var context = context()) {
      String doc = context.publish("plan.txt", "星港项目的识别码为A-42。");
      var vision = new QueryAttachmentServiceTest.Vision();
      try (var answers = answers(context, vision)) {
        vision.revision = "unused-changed";
        var result =
            answers.answerAttached(
                context.owner,
                new QueryAttachmentCommand(
                    new AnswerCommand("星港项目的识别码是什么？", DocumentSelection.selected(List.of(doc))),
                    QueryAnswerMode.TEXT,
                    List.of()));
        assertEquals("answered", result.result().status());
        assertTrue(result.queryAttachments().isEmpty());
        assertEquals(0, vision.calls);
        assertEquals(List.of("embed", "rerank", "extract"), context.models.calls);
        assertEquals(0, context.scalar("SELECT COUNT(*) FROM query_trace_preparations"));
      }
    }
  }

  private AnswerTestContext context() {
    return new AnswerTestContext(directory, QueryAttachmentAnswerFixture.BUDGET, 1);
  }

  private static QueryAttachmentCommand command(String question, List<String> ids) {
    return new QueryAttachmentCommand(
        new AnswerCommand(question, DocumentSelection.selected(ids)),
        QueryAnswerMode.TEXT,
        List.of(QueryAttachmentAnswerFixture.attachment()));
  }

  private static AnswerService answers(
      AnswerTestContext context, QueryAttachmentServiceTest.Vision vision) {
    return new AnswerService(
        context.evidence,
        context.models,
        context.projection,
        context.target,
        QueryAttachmentAnswerFixture.BUDGET,
        1,
        null,
        QueryAttachmentAnswerFixture.queries(
            context, vision, new QueryAttachmentServiceTest.Ranking()));
  }
}
