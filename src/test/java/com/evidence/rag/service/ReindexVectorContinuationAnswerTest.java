package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.ImageEmbeddingModels;
import com.evidence.rag.client.model.QueryRankingModels;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.ImageVectorPublication;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.PreparedQuery;
import com.evidence.rag.model.domain.QueryRankCandidate;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.model.dto.AudioAnswerResult;
import com.evidence.rag.model.dto.QueryAnswerMode;
import com.evidence.rag.model.dto.QueryAttachmentCommand;
import com.evidence.rag.model.dto.VisualAnswerResult;
import com.evidence.rag.support.AnswerTestContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReindexVectorContinuationAnswerTest {
  private static final String QUESTION = "Name the shape and its color.";
  private static final IndexTarget IMAGE_TARGET =
      new IndexTarget("image-profile-v1", "e".repeat(64), "image-profile-v1", 2);
  @TempDir Path directory;

  @Test
  void inheritedImageRecallMapsOldVectorToNewBaseThenProvesAndReopensCurrentSourceAfterRestart() {
    String answerId;
    try (var context = context()) {
      var vision = new Vision();
      var models = new Images();
      var projection = new Projection();
      var ranking = new Ranking();
      String image = QueryAttachmentAnswerFixture.publishImage(context, vision.revision());
      String text =
          context.publish("uncited.txt", "The complete selected scope remains authoritative.");
      var command = command(image, text);
      var old = context.evidence.snapshot(context.owner, command.selection(), context.target);
      var receipt = ImageVectorEvidenceServiceTest.publishVector(context, old, image);
      projection.receipts = List.of(receipt);
      try (var answers =
          answers(context, vision, queries(context, vision, ranking, models, projection))) {
        var before = attached(answers, context, command);
        assertEquals("answered", before.status(), before.reason());
        ReindexVectorTestSupport.rebuild(context, image);
        var current = context.evidence.snapshot(context.owner, command.selection(), context.target);
        var vectors = context.evidence.imageVectorScope(current, IMAGE_TARGET);
        assertEquals(List.of(receipt), vectors.publications());
        var hydrated =
            context
                .evidence
                .hydrateImageVectors(vectors, List.of(receipt.vectorPhysicalSegmentId()))
                .getFirst();
        assertNotEquals(receipt.basePublication(), hydrated.publication());
        assertNotEquals(receipt.basePhysicalSegmentId(), hydrated.physicalSegmentId());
        assertEquals(
            vectors.bindings().getFirst().currentBasePhysicalSegmentId(),
            hydrated.physicalSegmentId());
        assertThrows(
            ApplicationException.class, () -> answers.source(context.owner, before.answerId(), 1));
        var result = attached(answers, context, command);
        assertEquals("answered", result.status(), result.reason());
        answerId = result.answerId();
        assertEquals(
            Map.of(image, receipt.vectorGenerationId()),
            projection.query.scope().documentRevisions());
        assertEquals(RetrievalProjection.SearchMode.DENSE_ONLY, projection.query.mode());
        assertArrayEquals(
            QueryAttachmentAnswerFixture.image("png").content(),
            answers.content(context.owner, answerId, 1).content());
        assertEquals(
            1,
            context.scalar(
                "SELECT COUNT(*) FROM image_trace_evidence e JOIN active_corpus_publications p ON p.publication_id=e.publication_id"));
      }
    }
    try (var context = context()) {
      var vision = new Vision();
      var images = new Images();
      var projection = new Projection();
      var ranking = new Ranking();
      try (var answers =
          answers(context, vision, queries(context, vision, ranking, images, projection))) {
        answers.source(context.owner, answerId, 1);
        assertArrayEquals(
            QueryAttachmentAnswerFixture.image("png").content(),
            answers.content(context.owner, answerId, 1).content());
        assertTrue(vision.calls.isEmpty());
        assertTrue(images.received.isEmpty());
        assertEquals(0, projection.searches);
        assertEquals(0, ranking.calls);
      }
    }
  }

  @Test
  void inheritedAudioAllWaveRoutesStillCiteTheSavedTailAndRestartNeedsNoModels() {
    String answerId;
    byte[] original;
    try (var context = context()) {
      var published =
          AudioTestFixture.publish(context, List.of("会议开始。", "", AudioVectorQueryFixture.FACT));
      original = published.original();
      var selected = DocumentSelection.selected(List.of(published.documentId()));
      var scope = context.evidence.snapshot(context.owner, selected, context.target);
      var receipt = AudioVectorQueryFixture.publishVectors(context, scope, published);
      ReindexVectorTestSupport.rebuild(context, published.documentId());
      var current = context.evidence.snapshot(context.owner, selected, context.target);
      var bound =
          context.evidence.audioVectorScope(
              current, AudioVectorQueryFixture.TARGET, AudioVectorQueryFixture.DECODER);
      assertEquals(List.of(receipt), bound.publications());
      var tail =
          context
              .evidence
              .hydrateAudioVectors(
                  bound, List.of(receipt.entries().getLast().vectorPhysicalSegmentId()))
              .getFirst();
      assertNotEquals(receipt.basePublication(), tail.publication());
      assertNotEquals(
          receipt.entries().getLast().basePhysicalSegmentId(), tail.physicalSegmentId());
      var fixture = new AudioVectorQueryFixture();
      var models = new AudioVectorQueryFixture.Embeddings();
      var projection = new AudioVectorQueryFixture.Projection();
      projection.response =
          ignored ->
              List.of(
                  new RetrievalProjection.Candidate(
                      receipt.entries().getLast().vectorPhysicalSegmentId(), .8));
      try (var answers = audioAnswers(context, fixture.queries(context, models, projection))) {
        var result =
            (AudioAnswerResult)
                answers
                    .answerAttached(
                        context.owner,
                        new QueryAttachmentCommand(
                            new AnswerCommand(AudioVectorQueryFixture.QUESTION, selected),
                            QueryAnswerMode.AUDIO,
                            List.of(fixture.attachment())))
                    .result();
        assertEquals("answered", result.status(), result.reason());
        answerId = result.answerId();
        assertEquals(3, models.received.size());
        assertEquals(3, projection.queries.size());
        projection.queries.forEach(
            query -> {
              assertEquals(
                  Map.of(published.documentId(), receipt.vectorGenerationId()),
                  query.scope().documentRevisions());
              assertEquals(RetrievalProjection.SearchMode.DENSE_ONLY, query.mode());
            });
        assertEquals(2000, result.citations().getFirst().startMs());
        assertEquals(3000, result.citations().getFirst().endMs());
        assertEquals("星港项目的预算为47万元", result.citations().getFirst().quote());
        assertArrayEquals(original, answers.audioContent(context.owner, answerId, 1).content());
        assertEquals(
            1,
            context.scalar(
                "SELECT COUNT(*) FROM audio_trace_evidence e JOIN active_corpus_publications p ON p.publication_id=e.publication_id"));
      }
    }
    try (var context = context()) {
      var fixture = new AudioVectorQueryFixture();
      var models = new AudioVectorQueryFixture.Embeddings();
      var projection = new AudioVectorQueryFixture.Projection();
      try (var answers = audioAnswers(context, fixture.queries(context, models, projection))) {
        answers.audioSource(context.owner, answerId, 1);
        assertArrayEquals(original, answers.audioContent(context.owner, answerId, 1).content());
        assertTrue(models.received.isEmpty());
        assertTrue(projection.queries.isEmpty());
        assertTrue(fixture.asr.isEmpty());
        assertTrue(context.models.calls.isEmpty());
      }
    }
  }

  @Test
  void anUncitedInheritedImageChangingDuringProofPreventsTheFinalCitation() {
    try (var context = context()) {
      var vision = new Vision();
      var models = new Images();
      var projection = new Projection();
      var ranking = new Ranking();
      String first = QueryAttachmentAnswerFixture.publishImage(context, vision.revision());
      String uncited = QueryAttachmentAnswerFixture.publishImage(context, vision.revision());
      var command = command(first, uncited);
      var scope = context.evidence.snapshot(context.owner, command.selection(), context.target);
      var firstReceipt = ImageVectorEvidenceServiceTest.publishVector(context, scope, first);
      ImageVectorEvidenceServiceTest.publishVector(context, scope, uncited);
      ReindexVectorTestSupport.rebuild(context, first);
      ReindexVectorTestSupport.rebuild(context, uncited);
      projection.receipts = List.of(firstReceipt);
      vision.afterVerify = () -> ReindexVectorTestSupport.rebuild(context, uncited);
      try (var answers =
          answers(context, vision, queries(context, vision, ranking, models, projection))) {
        var result = attached(answers, context, command);
        assertEquals("abstained", result.status());
        assertEquals("scope_changed", result.reason());
        assertTrue(result.citations().isEmpty());
        assertEquals(List.of("describe", "draft", "verify"), vision.calls);
        assertEquals(0, context.scalar("SELECT COUNT(*) FROM image_trace_evidence"));
      }
    }
  }

  @Test
  void theEntireInheritedCandidateListMustMapBeforeAnyImageFactProof() {
    try (var context = context()) {
      var vision = new Vision();
      var models = new Images();
      var projection = new Projection();
      var ranking = new Ranking();
      String image = QueryAttachmentAnswerFixture.publishImage(context, vision.revision());
      var command = command(image);
      var scope = context.evidence.snapshot(context.owner, command.selection(), context.target);
      projection.receipts =
          List.of(ImageVectorEvidenceServiceTest.publishVector(context, scope, image));
      ReindexVectorTestSupport.rebuild(context, image);
      projection.foreignTail = true;
      try (var answers =
          answers(context, vision, queries(context, vision, ranking, models, projection))) {
        var result = attached(answers, context, command);
        assertFalse(result.status().equals("answered"));
        assertTrue(result.citations().isEmpty());
        assertEquals(0, ranking.calls);
        assertEquals(List.of("describe"), vision.calls);
        assertEquals(0, context.scalar("SELECT COUNT(*) FROM image_trace_evidence"));
      }
    }
  }

  private static AnswerService audioAnswers(
      AnswerTestContext context, QueryAttachmentService queries) {
    return new AnswerService(
        context.evidence,
        context.models,
        context.projection,
        context.target,
        AudioVectorQueryFixture.BUDGET,
        1,
        null,
        queries);
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
    private boolean foreignTail;

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
      var values =
          new ArrayList<>(
              receipts.stream()
                  .map(receipt -> new Candidate(receipt.vectorPhysicalSegmentId(), .5))
                  .toList());
      if (foreignTail) {
        values.add(new Candidate("unauthorized-vector-tail", .0001));
      }
      return List.copyOf(values);
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
    private Runnable afterVerify = () -> {};

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
      afterVerify.run();
      return new Verification(true, List.of(true));
    }
  }
}
