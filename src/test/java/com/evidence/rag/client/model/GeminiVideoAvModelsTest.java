package com.evidence.rag.client.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.OpenAiCompatibleModels.Endpoint;
import com.evidence.rag.model.domain.VideoAvFact;
import com.evidence.rag.model.domain.VideoAvMode;
import com.evidence.rag.model.domain.VideoAvRequirement;
import com.evidence.rag.model.domain.VideoAvWindow;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeminiVideoAvModelsTest {
  @Test
  void independentStagesSendActualCompleteMp4WavQuestionAndExactEpoch() throws Exception {
    try (var f = new VideoAvClientFixture()) {
      var window = VideoAvClientFixture.window();
      assertEquals(0, f.calls.get());
      assertTrue(
          f.models
              .draft(
                  "Does the bell move and ring?",
                  window,
                  VideoAvClientFixture.EPOCH,
                  VideoAvMode.JOINT)
              .complete());
      var input = f.request.get().path("input");
      assertEquals(3, input.size());
      assertEquals(
          Base64.getEncoder().encodeToString(window.video().content()),
          input.get(0).path("data").asString());
      assertEquals(
          Base64.getEncoder().encodeToString(window.audio().wav()),
          input.get(1).path("data").asString());
      assertEquals("static", input.get(0).path("processing").path("type").asString());
      assertEquals(1, input.get(0).path("processing").path("fps").asInt());
      assertFalse(input.get(0).path("processing").has("start_offset"));
      var data = VideoAvClientFixture.JSON.readTree(input.get(2).path("text").asString());
      assertEquals("Does the bell move and ring?", data.path("question").asString());
      assertEquals("200", data.path("epoch").path("pts").asString());
      assertEquals("16000", data.path("epoch").path("ticks_per_second").asString());
      assertEquals("0", data.path("audio").path("first_local_tick").asString());
      assertFalse(f.request.get().path("store").asBoolean());
      assertFalse(f.request.get().has("previous_interaction_id"));
      assertEquals("synthetic-key", f.key.get());
      assertNull(f.bearer.get());
      f.result.set(
          "{\"complete\":true,\"support\":[{\"id\":\""
              + "c".repeat(64)
              + "\",\"supported\":true,\"visual_contribution\":true,\"audio_contribution\":true}]}");
      var facts =
          List.of(
              new VideoAvFact(
                  "c".repeat(64),
                  "A bell moves and rings.",
                  VideoAvRequirement.JOINT,
                  false,
                  false));
      assertTrue(
          f.models
              .verify(
                  "Does the bell move and ring?",
                  window,
                  VideoAvClientFixture.EPOCH,
                  VideoAvMode.JOINT,
                  facts)
              .complete());
      assertEquals(2, f.calls.get());
      assertEquals(
          Base64.getEncoder().encodeToString(window.video().content()),
          f.request.get().path("input").get(0).path("data").asString());
      assertEquals(
          Base64.getEncoder().encodeToString(window.audio().wav()),
          f.request.get().path("input").get(1).path("data").asString());
    }
  }

  @Test
  void singleModesOnlyTransmitTheirOwnRealMediaAndMissingMediaNeverDispatches() throws Exception {
    try (var f = new VideoAvClientFixture()) {
      var window = VideoAvClientFixture.window();
      for (var mode : List.of(VideoAvMode.VISUAL, VideoAvMode.AUDIO)) {
        f.result.set(
            "{\"complete\":true,\"claims\":[{\"text\":\"An observation.\",\"requirement\":\""
                + mode.name()
                + "\"}]}");
        f.models.draft("What?", window, VideoAvClientFixture.EPOCH, mode);
        assertEquals(2, f.request.get().path("input").size());
        assertEquals(
            mode == VideoAvMode.VISUAL ? "video" : "audio",
            f.request.get().path("input").get(0).path("type").asString());
      }
      var silent =
          new VideoAvWindow(
              window.id(), 0, window.startTick(), window.endTick(), window.video(), null);
      failure(
          "model_invalid_input",
          () -> f.models.draft("What?", silent, VideoAvClientFixture.EPOCH, VideoAvMode.JOINT));
      failure(
          "model_invalid_input",
          () -> f.models.draft("What?", silent, VideoAvClientFixture.EPOCH, VideoAvMode.AUDIO));
      assertEquals(2, f.calls.get());
    }
  }

  @Test
  void supportsOptionalObjectAndOnlyPairedNonsemanticMediaSteps() throws Exception {
    try (var f = new VideoAvClientFixture()) {
      f.object.set("\"object\":\"interaction\",");
      f.prefix.set(
          "{\"type\":\"thought\",\"summary\":[]},{\"type\":\"processing_call\",\"id\":\"p\"},{\"type\":\"processing_result\",\"call_id\":\"p\"},");
      assertTrue(
          f.models
              .draft(
                  "What?",
                  VideoAvClientFixture.window(),
                  VideoAvClientFixture.EPOCH,
                  VideoAvMode.JOINT)
              .complete());
      for (String prefix :
          List.of(
              "{\"type\":\"processing_call\",\"id\":\"p\"},",
              "{\"type\":\"processing_result\",\"call_id\":\"p\"},",
              "{\"type\":\"function_call\",\"name\":\"tool\"},",
              "{\"type\":\"thought\",\"summary\":[{\"type\":\"text\",\"text\":\"claim\"}]},")) {
        f.prefix.set(prefix);
        failure(
            "model_invalid_response",
            () ->
                f.models.draft(
                    "What?",
                    VideoAvClientFixture.window(),
                    VideoAvClientFixture.EPOCH,
                    VideoAvMode.JOINT));
      }
    }
  }

  @Test
  void rejectsIncompleteEnvelopesWrongModelAndUnknownOutputFields() throws Exception {
    try (var f = new VideoAvClientFixture()) {
      for (String status : List.of("incomplete", "requires_action", "failed", "queued")) {
        f.status.set(status);
        failure(
            "model_invalid_response",
            () ->
                f.models.draft(
                    "What?",
                    VideoAvClientFixture.window(),
                    VideoAvClientFixture.EPOCH,
                    VideoAvMode.JOINT));
      }
      f.status.set("completed");
      f.model.set("other-model");
      failure(
          "model_invalid_response",
          () ->
              f.models.draft(
                  "What?",
                  VideoAvClientFixture.window(),
                  VideoAvClientFixture.EPOCH,
                  VideoAvMode.JOINT));
      f.model.set("av-fixture");
      f.result.set(
          "{\"complete\":true,\"claims\":[{\"id\":\"arbitrary\",\"text\":\"bell\",\"requirement\":\"JOINT\"}]}");
      failure(
          "model_invalid_response",
          () ->
              f.models.draft(
                  "What?",
                  VideoAvClientFixture.window(),
                  VideoAvClientFixture.EPOCH,
                  VideoAvMode.JOINT));
    }
  }

  @Test
  void modeRequirementsAndExactVerifyIdsCannotBeReplacedByPartialSuccess() throws Exception {
    try (var f = new VideoAvClientFixture()) {
      failure(
          "model_invalid_response",
          () ->
              f.models.draft(
                  "What?",
                  VideoAvClientFixture.window(),
                  VideoAvClientFixture.EPOCH,
                  VideoAvMode.VISUAL));
      var facts =
          List.of(new VideoAvFact("c".repeat(64), "bell", VideoAvRequirement.JOINT, false, false));
      f.result.set(
          "{\"complete\":true,\"support\":[{\"id\":\""
              + "d".repeat(64)
              + "\",\"supported\":true,\"visual_contribution\":true,\"audio_contribution\":true}]}");
      failure(
          "model_invalid_response",
          () ->
              f.models.verify(
                  "What?",
                  VideoAvClientFixture.window(),
                  VideoAvClientFixture.EPOCH,
                  VideoAvMode.JOINT,
                  facts));
      f.result.set("{\"complete\":false,\"claims\":[]}");
      assertFalse(
          f.models
              .draft(
                  "What?",
                  VideoAvClientFixture.window(),
                  VideoAvClientFixture.EPOCH,
                  VideoAvMode.JOINT)
              .complete());
    }
  }

  @Test
  void completeEightMiBClipAndThirtySecondWaveFitTheSerializedRequestBudget() throws Exception {
    try (var f = new VideoAvClientFixture()) {
      var clip = VideoAvClientFixture.clip(8 * 1024 * 1024);
      var audio =
          new com.evidence.rag.model.domain.AudioWaveform(
              "a".repeat(64), VideoAvClientFixture.DECODER, 0, 480000, new byte[960000]);
      var window = new VideoAvWindow("b".repeat(64), 0, 0, 480000, clip, audio);
      f.models.draft("Whole window?", window, VideoAvClientFixture.EPOCH, VideoAvMode.JOINT);
      assertEquals(
          Base64.getEncoder().encodeToString(clip.content()),
          f.request.get().path("input").get(0).path("data").asString());
      assertEquals(
          Base64.getEncoder().encodeToString(audio.wav()),
          f.request.get().path("input").get(1).path("data").asString());
    }
  }

  @Test
  void questionAndInputFailuresHaveNoDispatchAndClosedClientDoesNotRetry() throws Exception {
    try (var f = new VideoAvClientFixture()) {
      failure(
          "model_invalid_input",
          () ->
              f.models.draft(
                  "中".repeat(1366),
                  VideoAvClientFixture.window(),
                  VideoAvClientFixture.EPOCH,
                  VideoAvMode.JOINT));
      failure(
          "model_invalid_input",
          () -> f.models.draft("What?", null, VideoAvClientFixture.EPOCH, VideoAvMode.JOINT));
      failure(
          "model_invalid_input",
          () -> f.models.draft("What?", VideoAvClientFixture.window(), null, VideoAvMode.JOINT));
      assertEquals(0, f.calls.get());
      f.models.close();
      failure(
          "model_closed",
          () ->
              f.models.draft(
                  "What?",
                  VideoAvClientFixture.window(),
                  VideoAvClientFixture.EPOCH,
                  VideoAvMode.JOINT));
    }
  }

  @Test
  void pinnedProfileBindsProtocolModelAndVersionButNeverCredentialsOrBudgets() throws Exception {
    try (var f = new VideoAvClientFixture()) {
      var a =
          new GeminiVideoAvModels.Configuration(
              f.endpoint, "v1", Duration.ofSeconds(1), 65536, true);
      var b =
          new GeminiVideoAvModels.Configuration(
              new Endpoint(f.endpoint.baseUrl(), f.endpoint.model(), "different-key"),
              "v1",
              Duration.ofSeconds(2),
              131072,
              true);
      assertEquals(a.revision(), b.revision());
      assertNotEquals(
          a.revision(),
          new GeminiVideoAvModels.Configuration(
                  f.endpoint, "v2", Duration.ofSeconds(1), 65536, true)
              .revision());
      assertEquals("Configuration[redacted]", a.toString());
      failure(
          "model_invalid_configuration",
          () ->
              new GeminiVideoAvModels.Configuration(
                  f.endpoint, "latest", Duration.ofSeconds(1), 65536, true));
      assertEquals(0, f.calls.get());
    }
  }

  private static void failure(String code, Runnable call) {
    assertEquals(code, assertThrows(TextModels.Failure.class, call::run).code());
  }
}
