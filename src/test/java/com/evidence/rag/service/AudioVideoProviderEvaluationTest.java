package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.AudioModels;
import com.evidence.rag.client.model.FactTextModels;
import com.evidence.rag.client.model.FactVisionModels;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.model.domain.QuestionFact;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.tool.answer.QuestionPlanning;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AudioVideoProviderEvaluationTest {
  @Test
  void completeTwoFactPlanAndEveryFrameAreReservedBeforeTheFirstRequest() {
    assertEquals(
        2,
        QuestionPlanning.plan(AudioVideoProviderEvaluation.VIDEO_QUESTION)
            .orElseThrow()
            .facts()
            .size());
    assertEquals(
        12,
        AudioVideoProviderEvaluation.preflight(
            12, 11_045, 6000, 3, AudioVideoProviderEvaluation.VIDEO_QUESTION));
    assertEquals(
        30,
        AudioVideoProviderEvaluation.preflight(
            30, 30_000, 30_000, 21, AudioVideoProviderEvaluation.VIDEO_QUESTION));
    assertThrows(
        AssertionError.class,
        () ->
            AudioVideoProviderEvaluation.preflight(
                11, 11_045, 6000, 3, AudioVideoProviderEvaluation.VIDEO_QUESTION));
    assertThrows(
        AssertionError.class,
        () ->
            AudioVideoProviderEvaluation.preflight(
                30, 30_001, 6000, 3, AudioVideoProviderEvaluation.VIDEO_QUESTION));
    assertThrows(
        AssertionError.class,
        () ->
            AudioVideoProviderEvaluation.preflight(
                30, 11_045, 30_001, 3, AudioVideoProviderEvaluation.VIDEO_QUESTION));
    assertThrows(
        AssertionError.class,
        () ->
            AudioVideoProviderEvaluation.preflight(
                30, 11_045, 6000, 22, AudioVideoProviderEvaluation.VIDEO_QUESTION));
    assertThrows(
        AssertionError.class,
        () ->
            AudioVideoProviderEvaluation.preflight(
                30, 11_045, 6000, 0, AudioVideoProviderEvaluation.VIDEO_QUESTION));
    assertThrows(
        AssertionError.class,
        () ->
            AudioVideoProviderEvaluation.preflight(
                30, 11_045, 6000, 3, AudioVideoProviderEvaluation.AUDIO_QUESTION));
  }

  @Test
  void freshGrantAndEveryPrivateConfigurationValueAreMandatoryAndRedacted() {
    Map<String, String> valid =
        Map.of(
            "RAG_AUDIO_VIDEO_IT_APPROVED_CALLS", "30",
            "RAG_AUDIO_VIDEO_IT_API_KEY", "synthetic-private-credential",
            "RAG_AUDIO_VIDEO_IT_ASR_MODEL", "synthetic-asr",
            "RAG_AUDIO_VIDEO_IT_VISION_MODEL", "synthetic-vision",
            "RAG_AUDIO_VIDEO_IT_GENERATION_MODEL", "synthetic-text",
            "RAG_VIDEO_DECODER_IT_FFMPEG", "/synthetic/ffmpeg",
            "RAG_VIDEO_DECODER_IT_FFPROBE", "/synthetic/ffprobe");
    for (String name : valid.keySet()) {
      var missing = new HashMap<>(valid);
      missing.remove(name);
      var failure =
          assertThrows(
              AssertionError.class, () -> AudioVideoProviderEvaluation.liveSettings(missing));
      assertEquals("eval_missing_configuration", failure.getMessage());
      assertNull(failure.getCause());
    }
    for (String value : List.of("0", "31", "-1", "not-a-number")) {
      var invalid = new HashMap<>(valid);
      invalid.put("RAG_AUDIO_VIDEO_IT_APPROVED_CALLS", value);
      assertThrows(AssertionError.class, () -> AudioVideoProviderEvaluation.liveSettings(invalid));
    }
    assertThrows(
        AssertionError.class,
        () ->
            AudioVideoProviderEvaluation.liveSettings(Map.of("RAG_TEXT_IT_APPROVED_CALLS", "30")));
    assertEquals(30, AudioVideoProviderEvaluation.liveSettings(valid).approved());
    assertEquals(
        "LiveSettings[redacted]", AudioVideoProviderEvaluation.liveSettings(valid).toString());
    assertEquals(60, AudioVideoProviderEvaluation.REQUEST_TIMEOUT.toSeconds());
  }

  @Test
  void allThreeClientWrappersChargeOneCommonBudgetBeforeDelegating() {
    var output = new ByteArrayOutputStream();
    var budget =
        new AudioVideoProviderEvaluation.Budget(
            5, new PrintStream(output, true, StandardCharsets.UTF_8));
    var delegate = new Models(budget);
    var audio = new AudioVideoProviderEvaluation.CountedAudio(delegate, budget);
    var text = new AudioVideoProviderEvaluation.CountedText(delegate, delegate, budget);
    var vision = new AudioVideoProviderEvaluation.CountedVision(delegate, delegate, budget);
    var fact =
        QuestionPlanning.plan(AudioVideoProviderEvaluation.VIDEO_QUESTION)
            .orElseThrow()
            .facts()
            .getFirst();
    audio.transcribe(new byte[] {1});
    text.extract("synthetic", List.of());
    vision.describe(null);
    vision.draftFact("synthetic", fact, null);
    text.extractFact("synthetic", fact, List.of());
    assertEquals(5, budget.calls());
    assertEquals(5, delegate.calls);
    assertThrows(AssertionError.class, () -> vision.verifyFact("synthetic", fact, null, List.of()));
    assertEquals(5, budget.calls());
    assertEquals(5, delegate.calls);
    assertFalse(output.toString(StandardCharsets.UTF_8).contains("synthetic"));
  }

  @Test
  void failedAttemptConsumesQuotaStopsOtherClientsAndNeverLeaksTheFailureBody() {
    var output = new ByteArrayOutputStream();
    var budget =
        new AudioVideoProviderEvaluation.Budget(
            30, new PrintStream(output, true, StandardCharsets.UTF_8));
    var delegate = new Models(budget);
    delegate.fail = true;
    var audio = new AudioVideoProviderEvaluation.CountedAudio(delegate, budget);
    var text = new AudioVideoProviderEvaluation.CountedText(delegate, delegate, budget);
    var error = assertThrows(AssertionError.class, () -> audio.transcribe(new byte[] {1}));
    assertEquals("eval_model_failure", error.getMessage());
    assertNull(error.getCause());
    assertThrows(AssertionError.class, () -> text.extract("synthetic", List.of()));
    assertThrows(AssertionError.class, () -> audio.transcribe(new byte[] {1}));
    assertEquals(1, budget.calls());
    assertEquals(1, delegate.calls);
    String log = output.toString(StandardCharsets.UTF_8);
    assertTrue(log.contains("request=1 phase=asr code=model_failure"));
    assertFalse(log.contains("private-response-body"));
  }

  @Test
  void goldenNotationToleranceDoesNotRewriteEvidenceOrAcceptDifferentIdentifiersAndTimes() {
    assertTrue(AudioVideoProviderEvaluation.audioGold("备用泵编号是 A U 七三一。"));
    assertTrue(AudioVideoProviderEvaluation.audioGold("备用泵编号是AU-731。"));
    assertFalse(AudioVideoProviderEvaluation.audioGold("备用泵编号是AU-7310。"));
    assertFalse(AudioVideoProviderEvaluation.audioGold("备用泵编号是AU-713。"));
    assertTrue(AudioVideoProviderEvaluation.identifierGold("设备识别码是 V-314。"));
    assertFalse(AudioVideoProviderEvaluation.identifierGold("设备识别码是 V-3140。"));
    assertTrue(AudioVideoProviderEvaluation.windowGold("巡检窗口是周二上午八点三十分。"));
    assertTrue(AudioVideoProviderEvaluation.windowGold("巡检窗口是周二08:30。"));
    assertFalse(AudioVideoProviderEvaluation.windowGold("巡检窗口是周三08:30。"));
    assertFalse(AudioVideoProviderEvaluation.windowGold("巡检窗口是周二08:300。"));
  }

  private static final class Models
      implements AudioModels, TextModels, FactTextModels, VisionModels, FactVisionModels {
    private final AudioVideoProviderEvaluation.Budget budget;
    private int calls;
    private boolean fail;

    private Models(AudioVideoProviderEvaluation.Budget budget) {
      this.budget = budget;
    }

    private void called() {
      calls++;
      assertEquals(calls, budget.calls(), "Quota is occupied before the actual delegate starts");
      if (fail) {
        throw new IllegalArgumentException("private-response-body");
      }
    }

    @Override
    public String revision() {
      return "synthetic-eval-v1";
    }

    @Override
    public Transcript transcribe(byte[] wav) {
      called();
      return new Transcript("synthetic");
    }

    @Override
    public void close() {}

    @Override
    public List<List<Double>> embed(List<String> texts) {
      throw new AssertionError();
    }

    @Override
    public List<Ranked> rerank(String question, List<String> texts) {
      throw new AssertionError();
    }

    @Override
    public Extraction extract(String question, List<Evidence> evidence) {
      called();
      return new Extraction(List.of(), true);
    }

    @Override
    public Extraction extractFact(String question, QuestionFact fact, List<Evidence> evidence) {
      called();
      return new Extraction(List.of(), true);
    }

    @Override
    public Description describe(VisualImage image) {
      called();
      return new Description("synthetic");
    }

    @Override
    public Draft draft(String question, VisualImage image) {
      called();
      return new Draft(true, List.of());
    }

    @Override
    public Verification verify(String question, VisualImage image, List<String> claims) {
      called();
      return new Verification(false, List.of());
    }

    @Override
    public Draft draftFact(String question, QuestionFact fact, VisualImage image) {
      called();
      return new Draft(true, List.of());
    }

    @Override
    public Verification verifyFact(
        String question, QuestionFact fact, VisualImage image, List<String> claims) {
      called();
      return new Verification(false, List.of());
    }
  }
}
