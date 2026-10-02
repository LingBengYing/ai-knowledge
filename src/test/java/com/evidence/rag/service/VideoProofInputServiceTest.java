package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.FactTextModels;
import com.evidence.rag.client.model.FactVisionModels;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.model.domain.AudioTranscriptSpan;
import com.evidence.rag.model.domain.AudioTranscription;
import com.evidence.rag.model.domain.GroundingText;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.QuestionFact;
import com.evidence.rag.model.domain.VideoAssessment;
import com.evidence.rag.model.domain.VideoCompilation;
import com.evidence.rag.model.domain.VideoEvidence;
import com.evidence.rag.model.domain.VideoProofInput;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.support.VideoCompilationFixture;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class VideoProofInputServiceTest {
  private static final String REVISION = "authority-video-revision";
  private static final String QUESTION = "指示灯的颜色是什么？重启等待时间是多少秒？";

  @Test
  void selectedOriginalFrameAndFullTranscriptMatchExistingCompilationEntryPoint() {
    var compilation = compilation("重启等待时间是5秒。");
    var input = input(compilation, "重启等待时间是5秒。", "重启等待时间是5秒。");
    var models = new Models();
    var service = new VideoAssessmentService(models, models, Duration.ofSeconds(5));
    var selected = service.assess(QUESTION, input, VideoAssessment.Mode.JOINT, () -> true);
    var compiled =
        service.assess(
            QUESTION,
            REVISION,
            compilation,
            input.group().id(),
            VideoAssessment.Mode.JOINT,
            () -> true);
    assertTrue(selected.supported());
    assertEquals(compiled, selected);
    assertEquals(VideoAssessmentService.POLICY_REVISION, selected.policyRevision());
    assertEquals(input.sourceSha256(), selected.sourceSha256());
    assertEquals(input.manifestSha256(), selected.manifestSha256());
  }

  @Test
  void preservesCompleteTranscriptCounterevidenceOutsideSelectedSpan() {
    var compilation = compilation("重启等待时间是5秒。");
    var input = input(compilation, "重启等待时间是5秒。\n指示灯的颜色是红色。", "重启等待时间是5秒。");
    var models = new Models();
    var result =
        new VideoAssessmentService(models, models, Duration.ofSeconds(5))
            .assess(QUESTION, input, VideoAssessment.Mode.JOINT, () -> true);
    assertFalse(result.supported());
    assertEquals("conflicting_evidence", result.refusalReason());
    assertTrue(result.proofs().isEmpty());
  }

  @Test
  void checksCurrentAuthorityBeforeAnySelectedMaterialEntersAModel() {
    var compilation = compilation("重启等待时间是5秒。");
    var models = new Models();
    var result =
        new VideoAssessmentService(models, models, Duration.ofSeconds(5))
            .assess(
                QUESTION,
                input(compilation, "重启等待时间是5秒。", "重启等待时间是5秒。"),
                VideoAssessment.Mode.JOINT,
                () -> false);
    assertEquals("scope_changed", result.refusalReason());
    assertEquals(0, models.calls);
  }

  @Test
  void blankPairedTranscriptDoesNotDisableVisualOnlyAssessment() {
    var compilation = VideoCompilationFixture.compilation(true);
    var material = VideoEvidence.fromCompilation(REVISION, compilation);
    var group =
        material.groups().stream()
            .filter(
                value ->
                    VideoEvidence.transcriptIdentity(REVISION, 1).equals(value.transcriptSpanId()))
            .findFirst()
            .orElseThrow();
    var input =
        new VideoProofInput(
            compilation.sourceSha256(),
            material.manifestSha256(),
            group,
            compilation.frames().get(1).frame(),
            null);
    var models = new Models();
    var service = new VideoAssessmentService(models, models, Duration.ofSeconds(5));
    var selected = service.assess("指示灯的颜色是什么？", input, VideoAssessment.Mode.VISUAL, () -> true);
    assertTrue(selected.supported());
    assertEquals(
        service.assess(
            "指示灯的颜色是什么？",
            REVISION,
            compilation,
            group.id(),
            VideoAssessment.Mode.VISUAL,
            () -> true),
        selected);
    assertTrue(selected.proofs().stream().allMatch(proof -> proof.transcriptQuotes().isEmpty()));
  }

  private static VideoCompilation compilation(String transcript) {
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

  private static VideoProofInput input(
      VideoCompilation compilation, String context, String snippet) {
    var material = VideoEvidence.fromCompilation(REVISION, compilation);
    var group = material.groups().getFirst();
    int start = context.codePointCount(0, context.indexOf(snippet));
    return new VideoProofInput(
        compilation.sourceSha256(),
        material.manifestSha256(),
        group,
        compilation.frames().getFirst().frame(),
        new GroundingText(
            group.transcriptSpanId(),
            "video-transcript:" + REVISION,
            context,
            ModelValues.sha256(context.getBytes(StandardCharsets.UTF_8)),
            start,
            start + snippet.codePointCount(0, snippet.length())));
  }

  private static final class Models implements FactTextModels, FactVisionModels {
    private int calls;

    @Override
    public VisionModels.Draft draftFact(String question, QuestionFact fact, VisualImage image) {
      calls++;
      return fact.requirement().contains("颜色")
          ? new VisionModels.Draft(false, List.of("指示灯的颜色是蓝色"))
          : new VisionModels.Draft(true, List.of());
    }

    @Override
    public VisionModels.Verification verifyFact(
        String question, QuestionFact fact, VisualImage image, List<String> claims) {
      calls++;
      return new VisionModels.Verification(true, List.of(true));
    }

    @Override
    public TextModels.Extraction extractFact(
        String question, QuestionFact fact, List<TextModels.Evidence> evidence) {
      calls++;
      return fact.requirement().contains("重启等待时间")
          ? new TextModels.Extraction(
              List.of(new TextModels.Quote(evidence.getFirst().id(), "重启等待时间是5秒")), false)
          : new TextModels.Extraction(List.of(), true);
    }

    @Override
    public String revision() {
      return "selected-video-fact-models-v1";
    }
  }
}
