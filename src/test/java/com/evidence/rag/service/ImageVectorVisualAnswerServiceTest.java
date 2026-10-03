package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.ImageEmbeddingModels;
import com.evidence.rag.client.model.QueryRankingModels;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.ImageVectorPublication;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.PreparedQuery;
import com.evidence.rag.model.domain.QueryRankCandidate;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.model.dto.VisualAnswerResult;
import com.evidence.rag.support.AnswerTestContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageVectorVisualAnswerServiceTest {
  private static final String QUESTION = "Name the shape and its color.";
  private static final IndexTarget IMAGE_TARGET =
      new IndexTarget("image-profile-v1", "e".repeat(64), "image-profile-v1", 2);
  @TempDir Path directory;

  @Test
  void imageRecallUsesVectorGenerationThenOriginalProofAndRestartedSourceUsesNoModels() {
    String answerId;
    try (var context = context()) {
      var vision = new Vision();
      var models = new Images();
      var projection = new Projection();
      var ranking = new Ranking();
      String image = QueryAttachmentAnswerFixture.publishImage(context, vision.revision());
      String text = context.publish("selected.txt", "Uncited selected document.");
      var command = command(image, text);
      var scope = context.evidence.snapshot(context.owner, command.selection(), context.target);
      var receipt = ImageVectorEvidenceServiceTest.publishVector(context, scope, image);
      projection.receipts = List.of(receipt);
      try (var answers =
          answers(context, vision, queries(context, vision, ranking, models, projection))) {
        var result = attached(answers, context, command);
        assertEquals("answered", result.status(), result.reason());
        answerId = result.answerId();
        assertEquals(
            Map.of(image, receipt.vectorGenerationId()),
            projection.query.scope().documentRevisions());
        assertEquals(RetrievalProjection.SearchMode.DENSE_ONLY, projection.query.mode());
        assertEquals(List.of("describe", "draft", "verify"), vision.calls);
        assertEquals(List.of(QUESTION, QUESTION), vision.questions);
        assertEquals(1, models.received.size());
        assertArrayEquals(
            QueryAttachmentAnswerFixture.attachment().content(),
            models.received.getFirst().content());
        assertEquals(1, ranking.calls);
        assertTrue(
            context.models.calls.isEmpty(), "No text embedding or reranking on image recall");
        assertEquals(2, context.scalar("SELECT COUNT(*) FROM query_trace_documents"));
        assertEquals(
            receipt.basePublication().sourceSha256(), result.citations().getFirst().sourceSha256());
        assertArrayEquals(
            QueryAttachmentAnswerFixture.image("png").content(),
            answers.content(context.owner, answerId, 1).content());
      }
    }
    try (var context = context()) {
      var vision = new Vision();
      var models = new Images();
      var projection = new Projection();
      var ranking = new Ranking();
      try (var answers =
          answers(context, vision, queries(context, vision, ranking, models, projection))) {
        assertArrayEquals(
            QueryAttachmentAnswerFixture.image("png").content(),
            answers.content(context.owner, answerId, 1).content());
        answers.source(context.owner, answerId, 1);
        assertTrue(vision.calls.isEmpty());
        assertTrue(models.received.isEmpty());
        assertEquals(0, projection.searches);
        assertEquals(0, ranking.calls);
        assertTrue(context.models.calls.isEmpty());
      }
    }
  }

  @Test
  void missingUncitedImageReceiptRefusesWholeScopeBeforeEmbeddingWithoutCaptionFallback() {
    try (var context = context()) {
      var vision = new Vision();
      var images = new Images();
      var projection = new Projection();
      var ranking = new Ranking();
      String first = QueryAttachmentAnswerFixture.publishImage(context, vision.revision());
      String second = QueryAttachmentAnswerFixture.publishImage(context, vision.revision());
      var command = command(first, second);
      var scope = context.evidence.snapshot(context.owner, command.selection(), context.target);
      projection.receipts =
          List.of(ImageVectorEvidenceServiceTest.publishVector(context, scope, first));
      try (var answers =
          answers(context, vision, queries(context, vision, ranking, images, projection))) {
        var result = attached(answers, context, command);
        assertEquals("abstained", result.status());
        assertEquals("image_vector_required", result.reason());
        assertEquals("请为当前范围的全部图片建立原图向量。", result.answer());
        assertTrue(result.citations().isEmpty());
        assertTrue(images.received.isEmpty());
        assertEquals(0, projection.searches);
        assertEquals(0, ranking.calls);
        assertEquals(List.of("describe"), vision.calls);
        assertTrue(context.models.calls.isEmpty());
        assertEquals(2, context.scalar("SELECT COUNT(*) FROM query_trace_documents"));
        assertEquals(0, context.scalar("SELECT COUNT(*) FROM image_trace_evidence"));
      }
    }
  }

  @Test
  void embeddingRevocationOfUncitedTextPreventsProjectionAndProof() {
    try (var context = context()) {
      var vision = new Vision();
      var images = new Images();
      var projection = new Projection();
      var ranking = new Ranking();
      String image = QueryAttachmentAnswerFixture.publishImage(context, vision.revision());
      String text =
          context.publish("uncited.txt", "Keep full authority through every model request.");
      var command = command(image, text);
      var scope = context.evidence.snapshot(context.owner, command.selection(), context.target);
      projection.receipts =
          List.of(ImageVectorEvidenceServiceTest.publishVector(context, scope, image));
      images.afterEmbed = () -> context.revoke(text);
      try (var answers =
          answers(context, vision, queries(context, vision, ranking, images, projection))) {
        var result = attached(answers, context, command);
        assertEquals("abstained", result.status());
        assertEquals("scope_changed", result.reason());
        assertEquals(1, images.received.size());
        assertEquals(0, projection.searches);
        assertEquals(0, ranking.calls);
        assertEquals(List.of("describe"), vision.calls);
        assertTrue(result.citations().isEmpty());
        assertEquals(2, context.scalar("SELECT COUNT(*) FROM query_trace_documents"));
      }
    }
  }

  @Test
  void noReferenceImageUsesExistingTextRouteDespiteUnusedImageProfileDrift() {
    try (var context = context()) {
      var vision = new Vision();
      var images = new Images();
      var projection = new Projection();
      var ranking = new Ranking();
      String image = QueryAttachmentAnswerFixture.publishImage(context, vision.revision());
      var queries = queries(context, vision, ranking, images, projection);
      images.revision = "changed-unused-image-profile";
      try (var answers = answers(context, vision, queries)) {
        var result = answers.answer(context.owner, command(image));
        assertEquals("answered", result.status(), result.reason());
        assertEquals(List.of("embed", "rerank"), context.models.calls);
        assertEquals(List.of("draft", "verify"), vision.calls);
        assertTrue(images.received.isEmpty());
        assertEquals(0, projection.searches);
        assertEquals(0, ranking.calls);
      }
    }
  }

  private AnswerTestContext context() {
    return new AnswerTestContext(directory, QueryAttachmentAnswerFixture.BUDGET, 1);
  }

  private static AnswerCommand command(String... documents) {
    return new AnswerCommand(QUESTION, DocumentSelection.selected(List.of(documents)));
  }

  private static VisualAnswerResult attached(
      VisualAnswerService answers, AnswerTestContext context, AnswerCommand command) {
    return (VisualAnswerResult)
        answers
            .answerAttached(
                context.owner, command, List.of(QueryAttachmentAnswerFixture.attachment()))
            .result();
  }

  private static QueryAttachmentService queries(
      AnswerTestContext context,
      Vision vision,
      Ranking ranking,
      Images images,
      Projection projection) {
    return new QueryAttachmentService(
        QueryAttachmentAnswerFixture.preparation(vision),
        ranking,
        context.models,
        context.projection,
        context.target,
        images,
        projection,
        IMAGE_TARGET);
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

  private static final class Images implements ImageEmbeddingModels {
    private String revision = "image-profile-v1";
    private final List<VisualImage> received = new ArrayList<>();
    private Runnable afterEmbed = () -> {};

    public String revision() {
      return revision;
    }

    public int dimensions() {
      return 2;
    }

    public List<Double> embed(VisualImage image) {
      received.add(image);
      afterEmbed.run();
      return List.of(1.0, 0.0);
    }
  }

  private static final class Projection implements RetrievalProjection {
    private List<ImageVectorPublication> receipts = List.of();
    private Query query;
    private int searches;

    public String identity() {
      return "e".repeat(64);
    }

    public void initialize() {
      throw new AssertionError("No write in recall");
    }

    public void prepareSearch() {}

    public void upsert(List<Entry> entries) {
      throw new AssertionError("No write in recall");
    }

    public VerifiedRevision verify(RevisionManifest manifest) {
      throw new AssertionError("No write in recall");
    }

    public List<Candidate> search(Query value) {
      query = value;
      searches++;
      return receipts.stream()
          .map(receipt -> new Candidate(receipt.vectorPhysicalSegmentId(), .5))
          .toList();
    }
  }

  private static final class Ranking implements QueryRankingModels {
    private int calls;

    public String revision() {
      return "image-ranking-v1";
    }

    public List<TextModels.Ranked> rank(PreparedQuery query, List<QueryRankCandidate> candidates) {
      calls++;
      assertEquals(QUESTION, query.originalQuestion());
      assertArrayEquals(
          QueryAttachmentAnswerFixture.image("png").content(),
          candidates.getFirst().image().content());
      return List.of(new TextModels.Ranked(0, .9));
    }
  }

  private static final class Vision implements VisionModels {
    private final List<String> calls = new ArrayList<>();
    private final List<String> questions = new ArrayList<>();

    public String revision() {
      return "image-proof-v1";
    }

    public Description describe(VisualImage image) {
      calls.add("describe");
      assertArrayEquals(QueryAttachmentAnswerFixture.attachment().content(), image.content());
      return new Description(QueryAttachmentAnswerFixture.HINT);
    }

    public Draft draft(String question, VisualImage image) {
      calls.add("draft");
      questions.add(question);
      assertArrayEquals(QueryAttachmentAnswerFixture.image("png").content(), image.content());
      return new Draft(false, List.of("There is a blue circle."));
    }

    public Verification verify(String question, VisualImage image, List<String> claims) {
      calls.add("verify");
      questions.add(question);
      assertArrayEquals(QueryAttachmentAnswerFixture.image("png").content(), image.content());
      return new Verification(true, List.of(true));
    }
  }
}
