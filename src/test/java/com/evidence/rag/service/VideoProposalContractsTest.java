package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.FactTextModels;
import com.evidence.rag.client.model.FactVisionModels;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.EvidenceScope;
import com.evidence.rag.model.domain.QuestionFact;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.model.domain.VideoAnswerProposal;
import com.evidence.rag.model.domain.VideoAssessment;
import com.evidence.rag.model.domain.VideoTraceEvidence;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.support.AnswerTestContext;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Public proposal contracts using real authority and explicit local model/projection Adapters. */
class VideoProposalContractsTest {
  @TempDir Path directory;

  @Test
  void textOnlySnapshotRefusesWithoutPreparingVideoRetrievalOrCallingModels() {
    try (var context = context()) {
      context.publish("manual.txt", "重启等待时间是5秒。");
      var facts = new VideoAnswerFixture.Facts();
      var result =
          VideoAnswerFixture.proposals(context, facts)
              .propose(
                  scope(context),
                  VideoAnswerFixture.QUESTION,
                  VideoAssessment.Mode.JOINT,
                  () -> {});
      refused(result, "no_evidence");
      assertTrue(context.models.calls.isEmpty());
      assertTrue(context.projection.calls.isEmpty());
      assertTrue(facts.contexts.isEmpty());
    }
  }

  @Test
  void emptySearchRefusesBeforeRerankingOrProofAndDoesNotFallbackToAllEvidence() {
    try (var context = context()) {
      VideoAnswerFixture.publish(context, "");
      context.projection.results = candidates -> List.of();
      var facts = new VideoAnswerFixture.Facts();
      var result =
          VideoAnswerFixture.proposals(context, facts)
              .propose(
                  scope(context),
                  VideoAnswerFixture.QUESTION,
                  VideoAssessment.Mode.JOINT,
                  () -> {});
      refused(result, "no_evidence");
      assertEquals(List.of("embed"), context.models.calls);
      assertTrue(facts.contexts.isEmpty());
    }
  }

  @ParameterizedTest
  @EnumSource(VideoTraceEvidence.Kind.class)
  void eitherSingleModalityRecallHitExpandsOnlyItsActualJointGroup(
      VideoTraceEvidence.Kind hitKind) {
    try (var context = context()) {
      VideoAnswerFixture.publish(context, "");
      var scope = scope(context);
      context.projection.results =
          candidates -> {
            var ids = candidates.stream().map(RetrievalProjection.Candidate::segmentId).toList();
            var selected =
                context.evidence.hydrateVideo(scope, ids).stream()
                    .filter(c -> c.kind() == hitKind)
                    .findFirst()
                    .orElseThrow();
            return List.of(new RetrievalProjection.Candidate(selected.physicalSegmentId(), 0.75));
          };
      var result =
          VideoAnswerFixture.proposals(context, new VideoAnswerFixture.Facts())
              .propose(scope, VideoAnswerFixture.QUESTION, VideoAssessment.Mode.JOINT, () -> {});
      assertEquals("answered", result.trace().outcome(), result.trace().reasonCode());
      assertEquals(2, result.sources().size());
      assertEquals(
          1,
          result.sources().stream().map(s -> s.source().source().group().id()).distinct().count());
      assertEquals(
          List.of(VideoTraceEvidence.Kind.VISUAL, VideoTraceEvidence.Kind.TRANSCRIPT),
          result.trace().videoEvidence().stream().map(VideoTraceEvidence::kind).toList());
      assertTrue(
          result.trace().videoEvidence().stream()
              .allMatch(e -> e.retrievalScore() == 0.75 && e.rerankScore() == 100.0));
      assertFalse(result.answer().contains("99"));
    }
  }

  @ParameterizedTest
  @EnumSource(VideoAssessment.Mode.class)
  void transcriptOnlyGroupCannotBePromotedIntoAVisualOrJointGroup(VideoAssessment.Mode mode) {
    try (var context = context()) {
      VideoAnswerFixture.publish(context, "预算为7万元。");
      var scope = scope(context);
      context.projection.results =
          candidates -> {
            var ids = candidates.stream().map(RetrievalProjection.Candidate::segmentId).toList();
            var tail =
                context.evidence.hydrateVideo(scope, ids).stream()
                    .filter(c -> c.recallText().equals("预算为7万元。"))
                    .findFirst()
                    .orElseThrow();
            return List.of(new RetrievalProjection.Candidate(tail.physicalSegmentId(), 0.9));
          };
      var vision = new RecordingVision();
      var proposal = proposals(context, new SpanText(), vision, context.projection);
      var result = proposal.propose(scope, "预算是多少？", mode, () -> {});
      if (mode == VideoAssessment.Mode.TRANSCRIPT) {
        assertEquals("answered", result.trace().outcome(), result.trace().reasonCode());
        assertEquals("预算为7万元", result.answer());
        assertEquals(1, result.sources().size());
        assertNull(result.sources().getFirst().source().proofInput().frame());
        assertEquals(1000, result.sources().getFirst().source().transcriptSpan().span().startMs());
      } else {
        refused(result, "incomplete_evidence");
      }
      assertEquals(0, vision.drafts.get());
    }
  }

  @Test
  void incompleteFirstGroupAllowsTheNextRealGroupToProveTheWholeQuestion() {
    try (var context = context()) {
      VideoAnswerFixture.publish(context, "");
      VideoAnswerFixture.publish(context, "");
      var scope = scope(context);
      context.models.ranking =
          values ->
              IntStream.range(0, values.size()).mapToObj(i -> new TextModels.Ranked(i, 1)).toList();
      var vision = new RecordingVision();
      var colors = new AtomicInteger();
      vision.script =
          fact ->
              fact.requirement().contains("颜色") && colors.incrementAndGet() == 1
                  ? new VisionModels.Draft(true, List.of())
                  : null;
      var result =
          proposals(context, new VideoAnswerFixture.Facts(), vision, context.projection)
              .propose(scope, VideoAnswerFixture.QUESTION, VideoAssessment.Mode.JOINT, () -> {});
      assertEquals("answered", result.trace().outcome(), result.trace().reasonCode());
      assertEquals(2, colors.get());
      String firstPublication =
          scope.publications().stream()
              .map(p -> p.publicationId())
              .min(String::compareTo)
              .orElseThrow();
      assertNotEquals(firstPublication, result.trace().videoProof().publicationId());
      assertEquals(
          1,
          result.sources().stream().map(s -> s.source().source().group().id()).distinct().count());
      assertEquals(2, result.trace().videoProof().facts().size());
    }
  }

  @Test
  void unsupportedQuestionStopsWithoutAskingAnotherGroupOrModelToInventAnAnswer() {
    try (var context = context()) {
      VideoAnswerFixture.publish(context, "");
      VideoAnswerFixture.publish(context, "");
      var vision = new RecordingVision();
      var facts = new VideoAnswerFixture.Facts();
      var result =
          proposals(context, facts, vision, context.projection)
              .propose(scope(context), "试运行期间，A区和B区分别是什么颜色？", VideoAssessment.Mode.JOINT, () -> {});
      refused(result, "unsupported_question");
      assertEquals(0, vision.drafts.get());
      assertTrue(facts.contexts.isEmpty());
    }
  }

  @Test
  void providerFailureStopsInsteadOfTreatingLaterGroupsAsAnAutomaticRetryBudget() {
    try (var context = context()) {
      VideoAnswerFixture.publish(context, "");
      VideoAnswerFixture.publish(context, "");
      var vision = new RecordingVision();
      vision.script =
          fact -> {
            throw new TextModels.Failure("model_http_error");
          };
      var result =
          proposals(context, new VideoAnswerFixture.Facts(), vision, context.projection)
              .propose(
                  scope(context),
                  VideoAnswerFixture.QUESTION,
                  VideoAssessment.Mode.JOINT,
                  () -> {});
      refused(result, "model_failure");
      assertEquals(1, vision.drafts.get());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"retrieval_text", "projection", "fact_text", "vision"})
  void eachPinnedAdapterRevisionChangeInvalidatesProposalBeforeRemoteWork(String adapter) {
    try (var context = context()) {
      VideoAnswerFixture.publish(context, "");
      var text = new SpanText();
      var vision = new RecordingVision();
      var projection = new MutableProjection(context.projection);
      var service = proposals(context, text, vision, projection);
      assertTrue(service.configurationCurrent());
      String frozen = service.modelRevision();
      switch (adapter) {
        case "retrieval_text" -> context.models.modelRevision = "changed-text-v2";
        case "projection" -> projection.revision = "c".repeat(64);
        case "fact_text" -> text.revision = "changed-fact-v2";
        case "vision" -> vision.revision = "changed-vision-v2";
        default -> throw new AssertionError(adapter);
      }
      assertFalse(service.configurationCurrent());
      assertEquals(frozen, service.modelRevision());
      var error =
          assertThrows(
              ApplicationException.class,
              () ->
                  service.propose(
                      scope(context),
                      VideoAnswerFixture.QUESTION,
                      VideoAssessment.Mode.JOINT,
                      () -> {}));
      assertEquals("configuration_changed", error.code());
      assertTrue(context.models.calls.isEmpty());
      assertTrue(context.projection.calls.isEmpty());
      assertEquals(0, vision.drafts.get());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing_vector", "wrong_dimension", "nonfinite_vector"})
  void invalidEmbeddingCannotStartVectorSearchOrProduceEvidence(String response) {
    try (var context = context()) {
      VideoAnswerFixture.publish(context, "");
      context.models.embedding =
          values ->
              switch (response) {
                case "missing_vector" -> List.of();
                case "wrong_dimension" -> List.of(List.of(1.0, 0.0, 0.0));
                case "nonfinite_vector" -> List.of(List.of(1.0, Double.NaN));
                default -> throw new AssertionError(response);
              };
      var facts = new VideoAnswerFixture.Facts();
      var service = VideoAnswerFixture.proposals(context, facts);
      assertEquals(
          "invalid_request",
          assertThrows(
                  ApplicationException.class,
                  () ->
                      service.propose(
                          scope(context),
                          VideoAnswerFixture.QUESTION,
                          VideoAssessment.Mode.JOINT,
                          () -> {}))
              .code());
      assertEquals(List.of("prepare"), context.projection.calls);
      assertTrue(facts.contexts.isEmpty());
    }
  }

  @Test
  void duplicatePhysicalSearchHitCannotBeCountedAsTwoIndependentSources() {
    try (var context = context()) {
      VideoAnswerFixture.publish(context, "");
      context.projection.results =
          candidates -> List.of(candidates.getFirst(), candidates.getFirst());
      var facts = new VideoAnswerFixture.Facts();
      var service = VideoAnswerFixture.proposals(context, facts);
      assertEquals(
          "invalid_request",
          assertThrows(
                  ApplicationException.class,
                  () ->
                      service.propose(
                          scope(context),
                          VideoAnswerFixture.QUESTION,
                          VideoAssessment.Mode.JOINT,
                          () -> {}))
              .code());
      assertEquals(List.of("embed"), context.models.calls);
      assertTrue(facts.contexts.isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"incomplete", "duplicate_index"})
  void rerankerMustCoverEveryActualCandidateExactlyOnceBeforeProof(String response) {
    try (var context = context()) {
      VideoAnswerFixture.publish(context, "");
      context.models.ranking =
          values ->
              response.equals("incomplete")
                  ? List.of(new TextModels.Ranked(0, 1))
                  : List.of(new TextModels.Ranked(0, 2), new TextModels.Ranked(0, 1));
      var facts = new VideoAnswerFixture.Facts();
      var service = VideoAnswerFixture.proposals(context, facts);
      assertEquals(
          "invalid_request",
          assertThrows(
                  ApplicationException.class,
                  () ->
                      service.propose(
                          scope(context),
                          VideoAnswerFixture.QUESTION,
                          VideoAssessment.Mode.JOINT,
                          () -> {}))
              .code());
      assertTrue(facts.contexts.isEmpty());
    }
  }

  private AnswerTestContext context() {
    return new AnswerTestContext(directory, Duration.ofSeconds(10), 1);
  }

  private static EvidenceScope scope(AnswerTestContext context) {
    return context.evidence.snapshot(
        context.owner, DocumentSelection.allDocuments(), context.target);
  }

  private static VideoAnswerProposalService proposals(
      AnswerTestContext context,
      FactTextModels text,
      FactVisionModels vision,
      RetrievalProjection projection) {
    return new VideoAnswerProposalService(
        context.evidence,
        context.models,
        text,
        vision,
        projection,
        context.target,
        Duration.ofSeconds(10));
  }

  private static void refused(VideoAnswerProposal result, String reason) {
    assertEquals("abstained", result.trace().outcome());
    assertEquals(reason, result.trace().reasonCode());
    assertTrue(result.sources().isEmpty());
    assertTrue(result.trace().videoEvidence().isEmpty());
    assertNull(result.trace().videoProof());
    assertNull(result.trace().answerSha256());
  }

  private static final class SpanText implements FactTextModels {
    String revision = "span-text-v1";

    @Override
    public String revision() {
      return revision;
    }

    @Override
    public TextModels.Extraction extractFact(
        String question, QuestionFact fact, List<TextModels.Evidence> evidence) {
      return new TextModels.Extraction(
          List.of(new TextModels.Quote(evidence.getFirst().id(), evidence.getFirst().text())),
          false);
    }
  }

  private static final class RecordingVision implements FactVisionModels {
    final VideoAnswerFixture.Facts delegate = new VideoAnswerFixture.Facts();
    final AtomicInteger drafts = new AtomicInteger();
    String revision = "recording-vision-v1";
    Function<QuestionFact, VisionModels.Draft> script = fact -> null;

    @Override
    public String revision() {
      return revision;
    }

    @Override
    public VisionModels.Draft draftFact(String question, QuestionFact fact, VisualImage image) {
      drafts.incrementAndGet();
      var scripted = script.apply(fact);
      return scripted == null ? delegate.draftFact(question, fact, image) : scripted;
    }

    @Override
    public VisionModels.Verification verifyFact(
        String question, QuestionFact fact, VisualImage image, List<String> claims) {
      return delegate.verifyFact(question, fact, image, claims);
    }
  }

  private static final class MutableProjection implements RetrievalProjection {
    final RetrievalProjection delegate;
    String revision;

    MutableProjection(RetrievalProjection delegate) {
      this.delegate = delegate;
      revision = delegate.identity();
    }

    @Override
    public String identity() {
      return revision;
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
