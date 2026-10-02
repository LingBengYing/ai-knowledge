package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.FactTextModels;
import com.evidence.rag.client.model.FactVisionModels;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.AudioTranscriptSpan;
import com.evidence.rag.model.domain.AudioTranscription;
import com.evidence.rag.model.domain.QuestionFact;
import com.evidence.rag.model.domain.VideoAssessment;
import com.evidence.rag.model.domain.VideoCompilation;
import com.evidence.rag.model.domain.VideoEvidence;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.support.VideoCompilationFixture;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class VideoAssessmentServiceTest {
  private static final String REVISION = "video-revision";
  private static final String QUESTION = "指示灯的颜色是什么？重启等待时间是多少秒？";

  @Test
  void requiresExplicitModelsAndABoundedTotalProcessingBudget() {
    var models = new Models();
    assertThrows(
        ApplicationException.class,
        () -> new VideoAssessmentService(null, models, Duration.ofSeconds(1)));
    assertThrows(
        ApplicationException.class,
        () -> new VideoAssessmentService(models, null, Duration.ofSeconds(1)));
    assertThrows(
        ApplicationException.class, () -> new VideoAssessmentService(models, models, null));
    assertThrows(
        ApplicationException.class,
        () -> new VideoAssessmentService(models, models, Duration.ofMillis(9)));
    assertThrows(
        ApplicationException.class,
        () -> new VideoAssessmentService(models, models, Duration.ofMinutes(10).plusNanos(1)));
    var service = new VideoAssessmentService(models, models, Duration.ofMinutes(10));
    var compilation = video("重启等待时间是5秒。");
    var group = VideoEvidence.fromCompilation(REVISION, compilation).groups().getFirst();
    assertThrows(
        ApplicationException.class,
        () ->
            service.assess(
                null, REVISION, compilation, group.id(), VideoAssessment.Mode.JOINT, () -> true));
    assertThrows(
        ApplicationException.class,
        () -> service.assess(QUESTION, REVISION, compilation, group.id(), null, () -> true));
    assertThrows(
        ApplicationException.class,
        () ->
            service.assess(
                QUESTION, REVISION, compilation, group.id(), VideoAssessment.Mode.JOINT, null));
    assertTrue(models.visualQuestions.isEmpty());
    assertTrue(models.textQuestions.isEmpty());
  }

  @Test
  void provesJointFactsWithOneRealPairAndServerFactIdentities() {
    var models = new Models();
    var video = video("重启等待时间是5秒。");
    var result = assess(models, video, QUESTION, VideoAssessment.Mode.JOINT, () -> true);
    assertTrue(result.supported());
    assertEquals(2, result.proofs().size());
    assertEquals(1, result.proofs().getFirst().visualSupport());
    assertEquals(0, result.proofs().getFirst().transcriptSupport());
    assertEquals(1, result.proofs().getLast().transcriptSupport());
    assertEquals(
        result.factIds().getLast(),
        result.proofs().getLast().transcriptQuotes().getFirst().factHashes().getFirst());
    assertEquals(VideoCompilationFixture.SOURCE, result.sourceSha256());
    assertEquals(2, models.visualQuestions.size());
    assertTrue(models.visualQuestions.stream().allMatch(QUESTION::equals));
    assertTrue(models.textQuestions.stream().allMatch(QUESTION::equals));
    assertFalse(result.toString().contains("指示灯"));
  }

  @Test
  void supportsVisualOnlyWithoutInventingAnAudioTrack() {
    var models = new Models();
    var result =
        assess(
            models,
            VideoCompilationFixture.compilation(false),
            "指示灯的颜色是什么？",
            VideoAssessment.Mode.VISUAL,
            () -> true);
    assertTrue(result.supported());
    assertEquals(0, models.textQuestions.size());
  }

  @Test
  void supportsTranscriptOnlyWithoutCallingVision() {
    var models = new Models();
    var result =
        assess(
            models,
            video("重启等待时间是5秒。"),
            "重启等待时间是多少秒？",
            VideoAssessment.Mode.TRANSCRIPT,
            () -> true);
    assertTrue(result.supported());
    assertEquals(0, models.visualQuestions.size());
  }

  @Test
  void doesNotDropAnUnsupportedTrailingFact() {
    var result =
        assess(
            new Models(),
            video("重启等待时间是5秒。"),
            QUESTION + "复位口令是什么？",
            VideoAssessment.Mode.JOINT,
            () -> true);
    assertFalse(result.supported());
    assertEquals("incomplete_evidence", result.refusalReason());
    assertEquals(3, result.factIds().size());
    assertTrue(result.proofs().isEmpty());
  }

  @Test
  void refusesJointWhenOnlyOneModalityContributes() {
    var result =
        assess(
            new Models(),
            video("重启等待时间是5秒。"),
            "指示灯的颜色是什么？",
            VideoAssessment.Mode.JOINT,
            () -> true);
    assertFalse(result.supported());
    assertEquals("incomplete_evidence", result.refusalReason());
  }

  @Test
  void refusesOnRevocationBetweenDraftAndVerification() {
    var current = new AtomicBoolean(true);
    var models = new Models();
    models.afterDraft = () -> current.set(false);
    var result =
        assess(models, video("重启等待时间是5秒。"), QUESTION, VideoAssessment.Mode.JOINT, current::get);
    assertFalse(result.supported());
    assertEquals("scope_changed", result.refusalReason());
    assertEquals(0, models.verifications);
    assertEquals(0, models.textQuestions.size());
  }

  @Test
  void refusesConflictingTranscriptOutsideTheChosenGroup() {
    var base = video("重启等待时间是5秒。");
    var video =
        new VideoCompilation(
            base.sourceSha256(),
            base.decoderRevision(),
            base.compilerRevision(),
            0,
            2_000_000,
            base.frames(),
            new AudioTranscription(
                base.sourceSha256(),
                base.decoderRevision(),
                "asr-v1",
                "transcription-v1",
                32_000,
                List.of(
                    new AudioTranscriptSpan(0, 0, 1000, "重启等待时间是5秒。"),
                    new AudioTranscriptSpan(1, 1000, 2000, "重启等待时间是9秒。"))));
    var result = assess(new Models(), video, QUESTION, VideoAssessment.Mode.JOINT, () -> true);
    assertEquals("conflicting_evidence", result.refusalReason());
    assertTrue(result.proofs().isEmpty());
  }

  @Test
  void doesNotBorrowAnAnswerFromANonOverlappingSpan() {
    var base = video("介绍结束。");
    var video =
        new VideoCompilation(
            base.sourceSha256(),
            base.decoderRevision(),
            base.compilerRevision(),
            0,
            2_000_000,
            base.frames(),
            new AudioTranscription(
                base.sourceSha256(),
                base.decoderRevision(),
                "asr-v1",
                "transcription-v1",
                32_000,
                List.of(
                    new AudioTranscriptSpan(0, 0, 1000, "介绍结束。"),
                    new AudioTranscriptSpan(1, 1000, 2000, "重启等待时间是5秒。"))));
    var result = assess(new Models(), video, QUESTION, VideoAssessment.Mode.JOINT, () -> true);
    assertFalse(result.supported());
    assertTrue(result.proofs().isEmpty());
  }

  @Test
  void refusesWhenTwoModalitiesAssertDifferentValuesForTheSameFact() {
    var models = new Models();
    models.transcriptColor = true;
    var result =
        assess(
            models,
            video("指示灯的颜色是红色。重启等待时间是5秒。"),
            QUESTION,
            VideoAssessment.Mode.JOINT,
            () -> true);
    assertEquals("conflicting_evidence", result.refusalReason());
  }

  @Test
  void cannotHideAConflictingTranscriptByRefusingItsExtraction() {
    var result =
        assess(
            new Models(),
            video("指示灯的颜色是红色。重启等待时间是5秒。"),
            QUESTION,
            VideoAssessment.Mode.JOINT,
            () -> true);
    assertFalse(result.supported());
    assertEquals("conflicting_evidence", result.refusalReason());
    assertTrue(result.proofs().isEmpty());
  }

  @Test
  void retainsEnglishCompleteQuestionAcrossBothModalities() {
    var result =
        assess(
            new Models(),
            video("wait time is 5 seconds."),
            "What is the indicator color? What is the wait time?",
            VideoAssessment.Mode.JOINT,
            () -> true);
    assertTrue(result.supported());
    assertEquals(2, result.proofs().size());
  }

  @Test
  void permitsTheSameFactToBeIndependentlySupportedByBothModalities() {
    var models = new Models();
    models.transcriptColor = true;
    var result =
        assess(models, video("指示灯的颜色是蓝色。"), "指示灯的颜色是什么？", VideoAssessment.Mode.JOINT, () -> true);
    assertTrue(result.supported());
    assertEquals(1, result.proofs().getFirst().visualSupport());
    assertEquals(1, result.proofs().getFirst().transcriptSupport());
  }

  @Test
  void cannotHideAConflictingValueInAnUnselectedTranscriptSpan() {
    var base = video("重启等待时间是5秒。");
    var compilation =
        new VideoCompilation(
            base.sourceSha256(),
            base.decoderRevision(),
            base.compilerRevision(),
            0,
            2_000_000,
            base.frames(),
            new AudioTranscription(
                base.sourceSha256(),
                base.decoderRevision(),
                "asr-v1",
                "transcription-v1",
                32_000,
                List.of(
                    new AudioTranscriptSpan(0, 0, 1000, "重启等待时间是5秒。"),
                    new AudioTranscriptSpan(1, 1000, 2000, "指示灯的颜色是红色。"))));
    var result =
        assess(new Models(), compilation, QUESTION, VideoAssessment.Mode.JOINT, () -> true);
    assertEquals("conflicting_evidence", result.refusalReason());
    assertTrue(result.proofs().isEmpty());
  }

  @Test
  void emptyTranscriptCannotBePromotedToJointEvidence() {
    var models = new Models();
    var result = assess(models, video(" "), QUESTION, VideoAssessment.Mode.JOINT, () -> true);
    assertEquals("incomplete_evidence", result.refusalReason());
    assertTrue(models.visualQuestions.isEmpty());
    assertTrue(models.textQuestions.isEmpty());
  }

  @Test
  void modelRevisionChangeInvalidatesTheWholeProof() {
    var models = new Models();
    models.afterDraft = () -> models.revision = "changed";
    var result =
        assess(models, video("重启等待时间是5秒。"), QUESTION, VideoAssessment.Mode.JOINT, () -> true);
    assertEquals("configuration_changed", result.refusalReason());
    assertTrue(result.proofs().isEmpty());
  }

  @Test
  void unsupportedVisualJudgmentDoesNotCountAsProof() {
    var models = new Models();
    models.visualSupported = false;
    var result =
        assess(models, video("重启等待时间是5秒。"), QUESTION, VideoAssessment.Mode.JOINT, () -> true);
    assertEquals("incomplete_evidence", result.refusalReason());
  }

  @Test
  void unknownGroupCannotBorrowMembersFromAnotherCompilation() {
    var models = new Models();
    assertThrows(
        ApplicationException.class,
        () ->
            new VideoAssessmentService(models, models, Duration.ofSeconds(1))
                .assess(
                    QUESTION,
                    REVISION,
                    video("重启等待时间是5秒。"),
                    "video-group-" + "a".repeat(64),
                    VideoAssessment.Mode.JOINT,
                    () -> true));
    assertTrue(models.visualQuestions.isEmpty());
  }

  @Test
  void refusesUnsupportedPlanWithoutCallingModels() {
    var models = new Models();
    var result =
        assess(models, video("重启等待时间是5秒。"), "为什么？", VideoAssessment.Mode.JOINT, () -> true);
    assertEquals("unsupported_question", result.refusalReason());
    assertTrue(models.visualQuestions.isEmpty());
    assertTrue(models.textQuestions.isEmpty());
  }

  @Test
  void interruptionAndScopeFailureNeverReturnPartialProof() {
    var models = new Models();
    try {
      Thread.currentThread().interrupt();
      assertEquals(
          "processing_interrupted",
          assess(models, video("重启等待时间是5秒。"), QUESTION, VideoAssessment.Mode.JOINT, () -> true)
              .refusalReason());
    } finally {
      Thread.interrupted();
    }
    assertEquals(
        "scope_changed",
        assess(
                models,
                video("重启等待时间是5秒。"),
                QUESTION,
                VideoAssessment.Mode.JOINT,
                () -> {
                  throw new IllegalStateException("must not appear in the result");
                })
            .refusalReason());
  }

  @Test
  void deadlineIncludesTimeSpentCheckingCurrentAuthority() {
    var models = new Models();
    var video = video("重启等待时间是5秒。");
    var group = VideoEvidence.fromCompilation(REVISION, video).groups().getFirst();
    var result =
        new VideoAssessmentService(models, models, Duration.ofMillis(10))
            .assess(
                QUESTION,
                REVISION,
                video,
                group.id(),
                VideoAssessment.Mode.JOINT,
                () -> {
                  try {
                    Thread.sleep(30);
                  } catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                  }
                  return true;
                });
    assertEquals("processing_timeout", result.refusalReason());
    assertTrue(models.visualQuestions.isEmpty());
  }

  @Test
  void providerFailureDoesNotReturnTheEarlierSuccessfulFact() {
    var models = new Models();
    models.afterDraft =
        () -> {
          throw new TextModels.Failure("upstream_unavailable");
        };
    var result =
        assess(models, video("重启等待时间是5秒。"), QUESTION, VideoAssessment.Mode.JOINT, () -> true);
    assertEquals("model_failure", result.refusalReason());
    assertTrue(result.proofs().isEmpty());
  }

  private static VideoCompilation video(String transcript) {
    return new VideoCompilation(
        VideoCompilationFixture.SOURCE,
        "video-decoder-v1",
        VideoCompilationFixture.COMPILER,
        0,
        1_000_000,
        List.of(VideoCompilationFixture.frame(0, 0, 200_000)),
        new AudioTranscription(
            VideoCompilationFixture.SOURCE,
            "video-decoder-v1",
            "asr-v1",
            "transcription-v1",
            16_000,
            List.of(new AudioTranscriptSpan(0, 0, 1000, transcript))));
  }

  private static VideoAssessment assess(
      Models models,
      VideoCompilation video,
      String question,
      VideoAssessment.Mode mode,
      BooleanSupplier current) {
    var group = VideoEvidence.fromCompilation(REVISION, video).groups().getFirst();
    return new VideoAssessmentService(models, models, Duration.ofSeconds(5))
        .assess(question, REVISION, video, group.id(), mode, current);
  }

  private static final class Models implements FactTextModels, FactVisionModels {
    final List<String> visualQuestions = new ArrayList<>();
    final List<String> textQuestions = new ArrayList<>();
    Runnable afterDraft = () -> {};
    int verifications;
    boolean transcriptColor;
    boolean visualSupported = true;
    String revision = "fact-models-v1";

    @Override
    public VisionModels.Draft draftFact(String question, QuestionFact fact, VisualImage image) {
      assertEquals(VideoCompilationFixture.image().sha256(), image.sha256());
      visualQuestions.add(question);
      afterDraft.run();
      return fact.requirement().contains("颜色") || fact.requirement().contains("indicator color")
          ? new VisionModels.Draft(
              false,
              List.of(
                  fact.requirement().contains("indicator color")
                      ? "indicator color is blue."
                      : "指示灯的颜色是蓝色。"))
          : new VisionModels.Draft(true, List.of());
    }

    @Override
    public VisionModels.Verification verifyFact(
        String question, QuestionFact fact, VisualImage image, List<String> claims) {
      verifications++;
      return new VisionModels.Verification(true, List.of(visualSupported));
    }

    @Override
    public TextModels.Extraction extractFact(
        String question, QuestionFact fact, List<TextModels.Evidence> evidence) {
      textQuestions.add(question);
      if (fact.requirement().contains("重启等待时间")
          || fact.requirement().contains("wait time")
          || (transcriptColor && fact.requirement().contains("颜色"))) {
        return new TextModels.Extraction(
            List.of(new TextModels.Quote(evidence.getFirst().id(), evidence.getFirst().text())),
            false);
      }
      return new TextModels.Extraction(List.of(), true);
    }

    @Override
    public String revision() {
      return revision;
    }
  }
}
