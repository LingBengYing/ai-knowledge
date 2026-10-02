package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.QueryRankingModels;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.PreparedQuery;
import com.evidence.rag.model.domain.QueryAttachment;
import com.evidence.rag.model.domain.QueryRankCandidate;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.model.dto.VisualAnswerResult;
import com.evidence.rag.support.AnswerTestContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VisualQueryAttachmentAnswerTest {
  @TempDir Path directory;
  private static final String QUESTION = "Name both shapes and colors; include the final shape.";

  @Test
  void auxiliaryQueryPixelsRankLibraryOriginalButNeverEnterOriginalImageProof() {
    try (var context = new AnswerTestContext(directory, QueryAttachmentAnswerFixture.BUDGET, 1)) {
      var vision = new Vision();
      String doc = QueryAttachmentAnswerFixture.publishImage(context, vision.revision());
      var ranking = new Ranking();
      var queries = QueryAttachmentAnswerFixture.queries(context, vision, ranking);
      context.models.embedding =
          values -> {
            assertTrue(
                values.stream()
                    .anyMatch(value -> value.contains(QueryAttachmentAnswerFixture.HINT)));
            return values.stream().map(value -> List.of(1.0, 0.0)).toList();
          };
      try (var answers = answers(context, vision, queries)) {
        var result =
            (VisualAnswerResult)
                answers
                    .answerAttached(
                        context.owner,
                        new AnswerCommand(QUESTION, DocumentSelection.selected(List.of(doc))),
                        List.of(QueryAttachmentAnswerFixture.attachment()))
                    .result();
        assertEquals("answered", result.status(), result.reason());
        assertEquals(List.of("describe", "draft", "verify"), vision.calls);
        assertEquals(1, ranking.calls);
        assertEquals(QUESTION, ranking.query.originalQuestion());
        assertEquals(1, ranking.candidates.size());
        assertArrayEquals(
            QueryAttachmentAnswerFixture.image("png").content(),
            ranking.candidates.getFirst().image().content());
        assertArrayEquals(
            QueryAttachmentAnswerFixture.attachment().content(),
            ranking.query.queryImages().getFirst().content());
        assertEquals(List.of(QUESTION, QUESTION), vision.proofQuestions);
        assertFalse(result.answer().contains("123"));
        assertEquals(1, context.scalar("SELECT COUNT(*) FROM documents"));
        assertArrayEquals(
            QueryAttachmentAnswerFixture.image("png").content(),
            answers.content(context.owner, result.answerId(), 1).content());
      }
    }
  }

  @Test
  void explicitEmptyScopeDoesNotCompileOrDiscloseQueryAttachments() {
    try (var context = new AnswerTestContext(directory, QueryAttachmentAnswerFixture.BUDGET, 1)) {
      var vision = new Vision();
      var ranking = new Ranking();
      try (var answers =
          answers(
              context, vision, QueryAttachmentAnswerFixture.queries(context, vision, ranking))) {
        var result =
            (VisualAnswerResult)
                answers
                    .answerAttached(
                        context.owner,
                        new AnswerCommand(QUESTION, DocumentSelection.selected(List.of())),
                        List.of(QueryAttachmentAnswerFixture.attachment()))
                    .result();
        assertEquals("abstained", result.status());
        assertEquals("empty_scope", result.reason());
        assertTrue(vision.calls.isEmpty());
        assertEquals(0, ranking.calls);
        assertTrue(context.models.calls.isEmpty());
      }
    }
  }

  @Test
  void rankingRevocationOfUncitedSelectedDocumentStopsOriginalProof() {
    try (var context = new AnswerTestContext(directory, QueryAttachmentAnswerFixture.BUDGET, 1)) {
      var vision = new Vision();
      String image = QueryAttachmentAnswerFixture.publishImage(context, vision.revision());
      String other = context.publish("other.txt", "Uncited but selected.");
      var ranking = new Ranking();
      ranking.afterRank = () -> context.revoke(other);
      try (var answers =
          answers(
              context, vision, QueryAttachmentAnswerFixture.queries(context, vision, ranking))) {
        var result =
            (VisualAnswerResult)
                answers
                    .answerAttached(
                        context.owner,
                        new AnswerCommand(
                            QUESTION, DocumentSelection.selected(List.of(image, other))),
                        List.of(QueryAttachmentAnswerFixture.attachment()))
                    .result();
        assertEquals("abstained", result.status());
        assertEquals("scope_changed", result.reason());
        assertEquals(List.of("describe"), vision.calls);
        assertTrue(result.citations().isEmpty());
        assertEquals(2, context.scalar("SELECT COUNT(*) FROM query_trace_documents"));
      }
    }
  }

  @Test
  void existingTextOnlyEntryDoesNotDependOnUnusedQueryProfileOrCallPreparation() {
    try (var context = new AnswerTestContext(directory, QueryAttachmentAnswerFixture.BUDGET, 1)) {
      var vision = new Vision();
      String doc = QueryAttachmentAnswerFixture.publishImage(context, vision.revision());
      var ranking = new Ranking();
      var queries = QueryAttachmentAnswerFixture.queries(context, vision, ranking);
      ranking.revision = "changed-but-unused-v2";
      try (var answers = answers(context, vision, queries)) {
        var result =
            answers.answer(
                context.owner,
                new AnswerCommand(QUESTION, DocumentSelection.selected(List.of(doc))));
        assertEquals("answered", result.status(), result.reason());
        assertEquals(List.of("draft", "verify"), vision.calls);
        assertEquals(0, ranking.calls);
        assertEquals(List.of("embed", "rerank"), context.models.calls);
      }
    }
  }

  @Test
  void unsupportedLibraryClaimKeepsPreparedNoticeButDoesNotUseAttachmentAsProof() {
    try (var context = new AnswerTestContext(directory, QueryAttachmentAnswerFixture.BUDGET, 1)) {
      var vision = new Vision();
      vision.supported = List.of(true, false);
      String doc = QueryAttachmentAnswerFixture.publishImage(context, vision.revision());
      var ranking = new Ranking();
      try (var answers =
          answers(
              context, vision, QueryAttachmentAnswerFixture.queries(context, vision, ranking))) {
        var attached =
            answers.answerAttached(
                context.owner,
                new AnswerCommand(QUESTION, DocumentSelection.selected(List.of(doc))),
                List.of(QueryAttachmentAnswerFixture.attachment()));
        var result = (VisualAnswerResult) attached.result();
        assertEquals("abstained", result.status());
        assertEquals("unsupported_claims", result.reason());
        assertEquals(1, attached.queryAttachments().size());
        assertEquals("prepared", attached.queryAttachments().getFirst().status());
        assertEquals(0, context.scalar("SELECT COUNT(*) FROM image_trace_evidence"));
        assertTrue(result.citations().isEmpty());
        assertEquals(List.of(QUESTION, QUESTION), vision.proofQuestions);
      }
    }
  }

  @Test
  void invalidAttachmentGetsFailedNoticeWithoutCallingModelsOrChangingLibrary() {
    try (var context = new AnswerTestContext(directory, QueryAttachmentAnswerFixture.BUDGET, 1)) {
      var vision = new Vision();
      String doc = QueryAttachmentAnswerFixture.publishImage(context, vision.revision());
      var ranking = new Ranking();
      try (var answers =
          answers(
              context, vision, QueryAttachmentAnswerFixture.queries(context, vision, ranking))) {
        var attached =
            answers.answerAttached(
                context.owner,
                new AnswerCommand(QUESTION, DocumentSelection.selected(List.of(doc))),
                List.of(new QueryAttachment("bad.png", "image/png", new byte[] {1, 2, 3})));
        var result = (VisualAnswerResult) attached.result();
        assertEquals("abstained", result.status());
        assertEquals("unsupported_document", result.reason());
        assertEquals("failed", attached.queryAttachments().getFirst().status());
        assertEquals("unsupported_document", attached.queryAttachments().getFirst().reason());
        assertTrue(vision.calls.isEmpty());
        assertEquals(0, ranking.calls);
        assertTrue(context.models.calls.isEmpty());
        assertEquals(1, context.scalar("SELECT COUNT(*) FROM documents"));
      }
    }
  }

  @Test
  void rankingProfileChangeStopsProofAndRetainsPreparedAttachmentManifest() {
    try (var context = new AnswerTestContext(directory, QueryAttachmentAnswerFixture.BUDGET, 1)) {
      var vision = new Vision();
      String doc = QueryAttachmentAnswerFixture.publishImage(context, vision.revision());
      var ranking = new Ranking();
      var queries = QueryAttachmentAnswerFixture.queries(context, vision, ranking);
      ranking.afterRank = () -> ranking.revision = "changed-query-ranker-v2";
      try (var answers = answers(context, vision, queries)) {
        var attached =
            answers.answerAttached(
                context.owner,
                new AnswerCommand(QUESTION, DocumentSelection.selected(List.of(doc))),
                List.of(QueryAttachmentAnswerFixture.attachment()));
        var result = (VisualAnswerResult) attached.result();
        assertEquals("abstained", result.status());
        assertEquals("configuration_changed", result.reason());
        assertEquals(List.of("describe"), vision.calls);
        assertEquals("prepared", attached.queryAttachments().getFirst().status());
        assertTrue(result.citations().isEmpty());
      }
    }
  }

  private static VisualAnswerService answers(
      AnswerTestContext context, Vision vision, QueryAttachmentService queries) {
    return new VisualAnswerService(
        context.evidence,
        context.models,
        vision,
        context.projection,
        context.target,
        QueryAttachmentAnswerFixture.BUDGET,
        1,
        queries);
  }

  private static final class Vision implements VisionModels {
    final List<String> calls = new ArrayList<>();
    final List<String> proofQuestions = new ArrayList<>();
    List<Boolean> supported = List.of(true, true);

    public String revision() {
      return "query-proof-vision-v1";
    }

    public Description describe(VisualImage image) {
      calls.add("describe");
      assertArrayEquals(QueryAttachmentAnswerFixture.attachment().content(), image.content());
      return new Description(QueryAttachmentAnswerFixture.HINT);
    }

    public Draft draft(String question, VisualImage image) {
      calls.add("draft");
      proofQuestions.add(question);
      assertArrayEquals(QueryAttachmentAnswerFixture.image("png").content(), image.content());
      return new Draft(false, List.of("There is a blue circle.", "There is a red square."));
    }

    public Verification verify(String question, VisualImage image, List<String> claims) {
      calls.add("verify");
      proofQuestions.add(question);
      assertArrayEquals(QueryAttachmentAnswerFixture.image("png").content(), image.content());
      return new Verification(true, supported);
    }
  }

  private static final class Ranking implements QueryRankingModels {
    int calls;
    PreparedQuery query;
    List<QueryRankCandidate> candidates;
    Runnable afterRank = () -> {};
    String revision = "query-ranker-v1";

    public String revision() {
      return revision;
    }

    public List<TextModels.Ranked> rank(PreparedQuery input, List<QueryRankCandidate> values) {
      calls++;
      query = input;
      candidates = List.copyOf(values);
      afterRank.run();
      return java.util.stream.IntStream.range(0, values.size())
          .mapToObj(i -> new TextModels.Ranked(i, 0.9))
          .toList();
    }
  }
}
