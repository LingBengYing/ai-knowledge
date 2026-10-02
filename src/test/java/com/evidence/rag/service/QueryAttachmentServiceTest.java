package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.QueryRankingModels;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.PreparedQuery;
import com.evidence.rag.model.domain.QueryRankCandidate;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.support.AnswerTestContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QueryAttachmentServiceTest {
  @TempDir Path directory;

  @Test
  void completeUnicodeMaterialIsRetrievedInBoundedPartsBeforeCandidateFusion() {
    try (var context = context()) {
      var queries = QueryAttachmentAnswerFixture.queries(context, new Vision(), new Ranking());
      var base = QueryAttachmentAnswerFixture.prepared("原问题？");
      String text = base.originalQuestion() + "😀中".repeat(2600) + "最后完整线索";
      var prepared =
          new PreparedQuery(
              base.originalQuestion(),
              text,
              base.queryImages(),
              base.attachments(),
              base.preparationRevision());
      var embedded = new ArrayList<String>();
      context.models.embedding =
          values -> {
            embedded.addAll(values);
            assertTrue(
                values.stream().allMatch(s -> s.getBytes(StandardCharsets.UTF_8).length <= 4096));
            return values.stream().map(s -> List.of(1.0, 0.0)).toList();
          };
      int[] searches = {0};
      context.projection.data.initialize();
      context.projection.results =
          ignored ->
              List.of(new RetrievalProjection.Candidate("candidate-" + ++searches[0], searches[0]));
      var validated = new ArrayList<String>();
      var scope = new RetrievalProjection.AuthorizedScope("org-main", Map.of("doc", "generation"));
      var found = queries.search(prepared, scope, () -> {}, validated::addAll);
      assertEquals(text, String.join("", embedded));
      assertEquals(embedded.size(), searches[0]);
      assertEquals(searches[0], validated.size());
      assertEquals(scope, context.projection.lastScope);
      assertEquals("candidate-" + searches[0], found.getFirst().segmentId());
    }
  }

  @Test
  void allFortyFiveCandidatesReachImageRankingIncludingTheFinalBatch() {
    try (var context = context()) {
      var ranker = new Ranking();
      var queries = QueryAttachmentAnswerFixture.queries(context, new Vision(), ranker);
      var candidates =
          IntStream.range(0, 45)
              .mapToObj(i -> new QueryRankCandidate("candidate-" + i, null))
              .toList();
      var result = queries.rank(QueryAttachmentAnswerFixture.prepared("问题？"), candidates, () -> {});
      assertEquals(List.of(20, 20, 5), ranker.batches);
      assertEquals(45, result.size());
      assertEquals(
          IntStream.range(0, 45).boxed().toList(),
          result.stream().map(TextModels.Ranked::index).sorted().toList());
      assertEquals(44, result.getLast().index());
    }
  }

  @Test
  void missingOrInvalidImageRankFailsTheWholeMatchingResult() {
    try (var context = context()) {
      var ranker = new Ranking();
      ranker.invalid = true;
      var queries = QueryAttachmentAnswerFixture.queries(context, new Vision(), ranker);
      assertThrows(
          TextModels.Failure.class,
          () ->
              queries.rank(
                  QueryAttachmentAnswerFixture.prepared("问题？"),
                  List.of(new QueryRankCandidate("library fact", null)),
                  () -> {}));
    }
  }

  @Test
  void emptyImagesUseLegacyTextScoresAndOriginalNoAttachmentPreparationUsesNoModels() {
    try (var context = context()) {
      var ranker = new Ranking();
      var vision = new Vision();
      var queries = QueryAttachmentAnswerFixture.queries(context, vision, ranker);
      var query = queries.prepare("原始问题？", List.of(), () -> {});
      assertEquals("原始问题？", query.retrievalText());
      assertEquals(0, vision.calls);
      assertTrue(context.models.calls.isEmpty());
      var result = queries.rank(query, List.of(new QueryRankCandidate("library", null)), () -> {});
      assertEquals(100.0, result.getFirst().score());
      assertTrue(ranker.batches.isEmpty());
    }
  }

  @Test
  void liveProfileChangeAndScopeFailureCannotBeHiddenByPreparationAdapter() {
    try (var context = context()) {
      var vision = new Vision();
      var queries = QueryAttachmentAnswerFixture.queries(context, vision, new Ranking());
      assertTrue(queries.configurationCurrent());
      var failure =
          assertThrows(
              ApplicationException.class,
              () ->
                  queries.prepare(
                      "问题？",
                      List.of(QueryAttachmentAnswerFixture.attachment()),
                      () -> {
                        throw new ApplicationException(
                            FailureKind.CONFLICT, "scope_changed", "范围已变化。");
                      }));
      assertEquals("scope_changed", failure.code());
      vision.revision = "changed";
      assertFalse(queries.configurationCurrent());
      assertEquals(0, vision.calls);
    }
  }

  @Test
  void incompleteEmbeddingOrUnvalidatedCandidateBatchesNeverReachFusion() {
    try (var context = context()) {
      context.projection.data.initialize();
      var queries = QueryAttachmentAnswerFixture.queries(context, new Vision(), new Ranking());
      var query = QueryAttachmentAnswerFixture.prepared("问题？");
      var scope = new RetrievalProjection.AuthorizedScope("org-main", Map.of("doc", "generation"));
      List<List<List<Double>>> badVectors =
          Arrays.asList(null, List.of(), Collections.singletonList(null), List.of(List.of(1.0)));
      for (var vectors : badVectors) {
        context.models.embedding = ignored -> vectors;
        assertThrows(
            TextModels.Failure.class,
            () ->
                queries.search(
                    query,
                    scope,
                    () -> {},
                    ids -> {
                      throw new AssertionError("Invalid embeddings");
                    }));
      }
      context.models.embedding = values -> values.stream().map(s -> List.of(1.0, 0.0)).toList();
      var first = new RetrievalProjection.Candidate("first", 1);
      List<List<RetrievalProjection.Candidate>> badCandidates =
          Arrays.asList(
              null,
              Collections.singletonList(null),
              List.of(first, first),
              IntStream.range(0, 65)
                  .mapToObj(i -> new RetrievalProjection.Candidate("id-" + i, 1))
                  .toList());
      for (var candidates : badCandidates) {
        context.projection.results = ignored -> candidates;
        assertThrows(
            TextModels.Failure.class,
            () ->
                queries.search(
                    query,
                    scope,
                    () -> {},
                    ids -> {
                      throw new AssertionError("Invalid candidate batch");
                    }));
      }
    }
  }

  @Test
  void rankingRequiresEveryUniqueInRangeIndexAndActualImageScoreBounds() {
    try (var context = context()) {
      var ranked = new ArrayList<List<TextModels.Ranked>>();
      ranked.add(null);
      ranked.add(List.of());
      ranked.add(Collections.singletonList(null));
      for (var invalid :
          List.of(
              new TextModels.Ranked(-1, 0.5),
              new TextModels.Ranked(1, 0.5),
              new TextModels.Ranked(0, -0.1),
              new TextModels.Ranked(0, 1.1))) {
        ranked.add(List.of(invalid));
      }
      for (var response : ranked) {
        var ranker = new Ranking();
        ranker.response = ignored -> response;
        var queries = QueryAttachmentAnswerFixture.queries(context, new Vision(), ranker);
        assertThrows(
            TextModels.Failure.class,
            () ->
                queries.rank(
                    QueryAttachmentAnswerFixture.prepared("问题？"),
                    List.of(new QueryRankCandidate("authorized", null)),
                    () -> {}));
      }
      var duplicate = new Ranking();
      duplicate.response =
          ignored -> List.of(new TextModels.Ranked(0, 0.7), new TextModels.Ranked(0, 0.8));
      var queries = QueryAttachmentAnswerFixture.queries(context, new Vision(), duplicate);
      assertThrows(
          TextModels.Failure.class,
          () ->
              queries.rank(
                  QueryAttachmentAnswerFixture.prepared("问题？"),
                  List.of(
                      new QueryRankCandidate("first", null), new QueryRankCandidate("last", null)),
                  () -> {}));
    }
  }

  @Test
  void completeFusionChecksEveryPartBeforeKeepingTheBestDuplicateScore() {
    try (var context = context()) {
      context.projection.data.initialize();
      var queries = QueryAttachmentAnswerFixture.queries(context, new Vision(), new Ranking());
      var base = QueryAttachmentAnswerFixture.prepared("问题？");
      var query =
          new PreparedQuery(
              base.originalQuestion(),
              "a".repeat(4096) + "b".repeat(4096),
              base.queryImages(),
              base.attachments(),
              base.preparationRevision());
      int[] calls = {0};
      context.projection.results =
          ignored ->
              List.of(
                  new RetrievalProjection.Candidate("same", ++calls[0]),
                  new RetrievalProjection.Candidate("steady", 2.0 / calls[0]));
      var validated = new ArrayList<String>();
      var result =
          queries.search(
              query,
              new RetrievalProjection.AuthorizedScope("org-main", Map.of("doc", "generation")),
              () -> {},
              validated::addAll);
      assertEquals(List.of("same", "steady", "same", "steady"), validated);
      assertEquals(
          List.of(
              new RetrievalProjection.Candidate("same", 2),
              new RetrievalProjection.Candidate("steady", 2)),
          result);
    }
  }

  @Test
  void callbackFailureAfterDescriptionRestoresScopeReasonInsteadOfParserCancellation() {
    try (var context = context()) {
      var vision = new Vision();
      var queries = QueryAttachmentAnswerFixture.queries(context, vision, new Ranking());
      var failure =
          assertThrows(
              ApplicationException.class,
              () ->
                  queries.prepare(
                      "问题？",
                      List.of(QueryAttachmentAnswerFixture.attachment()),
                      () -> {
                        if (vision.calls > 0) {
                          throw new ApplicationException(
                              FailureKind.CONFLICT, "scope_changed", "范围已变化。");
                        }
                      }));
      assertEquals("scope_changed", failure.code());
      assertEquals(1, vision.calls);
      context.models.onRevision =
          () -> {
            throw new TextModels.Failure("unavailable");
          };
      assertFalse(queries.configurationCurrent());
    }
  }

  private AnswerTestContext context() {
    return new AnswerTestContext(directory, QueryAttachmentAnswerFixture.BUDGET, 1);
  }

  static final class Vision implements VisionModels {
    String revision = "query-test-vision-v1";
    int calls;

    public String revision() {
      return revision;
    }

    public Description describe(VisualImage image) {
      calls++;
      return new Description(QueryAttachmentAnswerFixture.HINT);
    }

    public Draft draft(String question, VisualImage image) {
      throw new AssertionError("No query proof");
    }

    public Verification verify(String question, VisualImage image, List<String> claims) {
      throw new AssertionError("No query proof");
    }
  }

  static final class Ranking implements QueryRankingModels {
    final List<Integer> batches = new ArrayList<>();
    boolean invalid;
    java.util.function.Function<List<QueryRankCandidate>, List<TextModels.Ranked>> response =
        values ->
            IntStream.range(0, values.size()).mapToObj(i -> new TextModels.Ranked(i, 0.9)).toList();

    public String revision() {
      return "query-test-ranking-v1";
    }

    public List<TextModels.Ranked> rank(PreparedQuery query, List<QueryRankCandidate> values) {
      batches.add(values.size());
      if (invalid) {
        return List.of(new TextModels.Ranked(0, Double.NaN));
      }
      return response.apply(values);
    }
  }
}
