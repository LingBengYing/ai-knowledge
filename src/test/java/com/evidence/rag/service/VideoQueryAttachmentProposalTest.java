package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.QueryRankingModels;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.PreparedQuery;
import com.evidence.rag.model.domain.QueryAttachment;
import com.evidence.rag.model.domain.QueryAttachmentManifest;
import com.evidence.rag.model.domain.QueryRankCandidate;
import com.evidence.rag.model.domain.VideoAssessment;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.support.AnswerTestContext;
import com.evidence.rag.support.VideoCompilationFixture;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VideoQueryAttachmentProposalTest {
  @TempDir Path directory;

  @Test
  void queryImageAndPublishedFrameAffectMatchingButEveryJointFactUsesOriginalQuestion() {
    try (var context = new AnswerTestContext(directory, QueryAttachmentAnswerFixture.BUDGET, 1)) {
      VideoAnswerFixture.publish(context, "");
      var facts = new VideoAnswerFixture.Facts();
      var ranking = new Ranking();
      var query = QueryAttachmentAnswerFixture.prepared(VideoAnswerFixture.QUESTION);
      var queries = queries(context, ranking);
      context.models.embedding =
          values -> {
            assertTrue(
                values.stream()
                    .anyMatch(value -> value.contains(QueryAttachmentAnswerFixture.HINT)));
            return values.stream().map(value -> List.of(1.0, 0.0)).toList();
          };
      var scope =
          context.evidence.snapshot(
              context.owner, DocumentSelection.allDocuments(), context.target);
      var result =
          VideoAnswerFixture.proposals(context, facts)
              .propose(
                  scope,
                  query,
                  VideoAssessment.Mode.JOINT,
                  queries,
                  () -> context.evidence.hydrate(scope, List.of()));
      assertEquals("answered", result.trace().outcome(), result.trace().reasonCode());
      assertEquals(1, ranking.calls);
      assertEquals(query, ranking.query);
      assertEquals(2, ranking.candidates.size());
      assertEquals(1, ranking.candidates.stream().filter(item -> item.image() != null).count());
      assertArrayEquals(
          VideoCompilationFixture.image().content(),
          ranking.candidates.stream()
              .filter(item -> item.image() != null)
              .findFirst()
              .orElseThrow()
              .image()
              .content());
      assertTrue(facts.contexts.stream().allMatch(VideoAnswerFixture.QUESTION::equals));
      assertEquals(2, result.trace().videoProof().facts().size());
      assertEquals(2, result.sources().size());
      assertFalse(result.answer().contains("123"));
    }
  }

  @Test
  void attachmentOnlyFinalFactCannotRepairMissingLibraryJointEvidence() {
    try (var context = new AnswerTestContext(directory, QueryAttachmentAnswerFixture.BUDGET, 1)) {
      VideoAnswerFixture.publish(context, "");
      String question = VideoAnswerFixture.QUESTION + "密码是多少？";
      var facts = new VideoAnswerFixture.Facts();
      var ranking = new Ranking();
      var scope =
          context.evidence.snapshot(
              context.owner, DocumentSelection.allDocuments(), context.target);
      var result =
          VideoAnswerFixture.proposals(context, facts)
              .propose(
                  scope,
                  QueryAttachmentAnswerFixture.prepared(question),
                  VideoAssessment.Mode.JOINT,
                  queries(context, ranking),
                  () -> context.evidence.hydrate(scope, List.of()));
      assertEquals(1, ranking.calls);
      assertEquals("abstained", result.trace().outcome());
      assertEquals("incomplete_evidence", result.trace().reasonCode());
      assertTrue(result.sources().isEmpty());
      assertTrue(facts.contexts.stream().allMatch(question::equals));
    }
  }

  @Test
  void audioOnlyHintsUseTextRankingWithoutAttachingLibraryFramesToQueryImageProtocol() {
    try (var context = new AnswerTestContext(directory, QueryAttachmentAnswerFixture.BUDGET, 1)) {
      VideoAnswerFixture.publish(context, "");
      var facts = new VideoAnswerFixture.Facts();
      var ranking = new Ranking();
      var query =
          new PreparedQuery(
              VideoAnswerFixture.QUESTION,
              VideoAnswerFixture.QUESTION + "\n" + QueryAttachmentAnswerFixture.HINT,
              List.of(),
              List.of(
                  new QueryAttachmentManifest(
                      0,
                      "a".repeat(64),
                      QueryAttachment.Kind.AUDIO,
                      "query-asr-fixture-v1",
                      "b".repeat(64),
                      QueryAttachmentAnswerFixture.HINT.length(),
                      0,
                      List.of(),
                      false)),
              "query-fixture-v1");
      context.models.embedding =
          values -> {
            assertTrue(
                values.stream()
                    .anyMatch(value -> value.contains(QueryAttachmentAnswerFixture.HINT)));
            return values.stream().map(value -> List.of(1.0, 0.0)).toList();
          };
      var scope =
          context.evidence.snapshot(
              context.owner, DocumentSelection.allDocuments(), context.target);
      var result =
          VideoAnswerFixture.proposals(context, facts)
              .propose(
                  scope, query, VideoAssessment.Mode.JOINT, queries(context, ranking), () -> {});
      assertEquals("answered", result.trace().outcome(), result.trace().reasonCode());
      assertEquals(0, ranking.calls);
      assertEquals(List.of("embed", "rerank"), context.models.calls);
      assertTrue(facts.contexts.stream().allMatch(VideoAnswerFixture.QUESTION::equals));
    }
  }

  @Test
  void preparedTextOnlyCompatibilityUsesOldRankingEvenWithChangedUnusedQueryProfile() {
    try (var context = new AnswerTestContext(directory, QueryAttachmentAnswerFixture.BUDGET, 1)) {
      VideoAnswerFixture.publish(context, "");
      var ranking = new Ranking();
      var queries = queries(context, ranking);
      ranking.revision = "unused-profile-changed-v2";
      var scope =
          context.evidence.snapshot(
              context.owner, DocumentSelection.allDocuments(), context.target);
      var result =
          VideoAnswerFixture.proposals(context, new VideoAnswerFixture.Facts())
              .propose(
                  scope,
                  PreparedQuery.text(VideoAnswerFixture.QUESTION),
                  VideoAssessment.Mode.JOINT,
                  queries,
                  () -> {});
      assertEquals("answered", result.trace().outcome(), result.trace().reasonCode());
      assertEquals(0, ranking.calls);
      assertEquals(List.of("embed", "rerank"), context.models.calls);
      assertTrue(
          result.trace().videoEvidence().stream().allMatch(item -> item.rerankScore() == 100));
    }
  }

  @Test
  void rankingCannotHideRevocationOfAnUncitedSelectedDocument() {
    try (var context = new AnswerTestContext(directory, QueryAttachmentAnswerFixture.BUDGET, 1)) {
      String video = VideoAnswerFixture.publish(context, "");
      String other = context.publish("other.txt", "uncited selected evidence");
      var ranking = new Ranking();
      ranking.afterRank = () -> context.revoke(other);
      var facts = new VideoAnswerFixture.Facts();
      var scope =
          context.evidence.snapshot(
              context.owner, DocumentSelection.selected(List.of(video, other)), context.target);
      var failure =
          assertThrows(
              ApplicationException.class,
              () ->
                  VideoAnswerFixture.proposals(context, facts)
                      .propose(
                          scope,
                          QueryAttachmentAnswerFixture.prepared(VideoAnswerFixture.QUESTION),
                          VideoAssessment.Mode.JOINT,
                          queries(context, ranking),
                          () -> context.evidence.hydrate(scope, List.of())));
      assertEquals("scope_changed", failure.code());
      assertEquals(1, ranking.calls);
      assertTrue(facts.contexts.isEmpty());
    }
  }

  private static QueryAttachmentService queries(AnswerTestContext context, Ranking ranking) {
    var vision =
        new VisionModels() {
          public String revision() {
            return "unused-query-vision-v1";
          }

          public Description describe(VisualImage image) {
            throw new AssertionError("Already prepared");
          }

          public Draft draft(String question, VisualImage image) {
            throw new AssertionError("Not proof");
          }

          public Verification verify(String question, VisualImage image, List<String> claims) {
            throw new AssertionError("Not proof");
          }
        };
    return QueryAttachmentAnswerFixture.queries(context, vision, ranking);
  }

  private static final class Ranking implements QueryRankingModels {
    int calls;
    PreparedQuery query;
    final List<QueryRankCandidate> candidates = new ArrayList<>();
    String revision = "query-ranker-v1";
    Runnable afterRank = () -> {};

    public String revision() {
      return revision;
    }

    public List<TextModels.Ranked> rank(PreparedQuery input, List<QueryRankCandidate> values) {
      calls++;
      query = input;
      candidates.addAll(values);
      afterRank.run();
      return IntStream.range(0, values.size())
          .mapToObj(i -> new TextModels.Ranked(i, 0.8))
          .toList();
    }
  }
}
