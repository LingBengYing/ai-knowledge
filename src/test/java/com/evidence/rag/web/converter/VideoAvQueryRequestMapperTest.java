package com.evidence.rag.web.converter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.VideoAvTestFixture;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.VideoAvMode;
import com.evidence.rag.model.domain.VideoAvQueryManifest;
import com.evidence.rag.model.dto.VideoAvAnswerResult;
import com.evidence.rag.model.dto.VideoAvQueryAnswerResult;
import com.evidence.rag.model.dto.VideoAvQueryAttachmentResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class VideoAvQueryRequestMapperTest {
  @Test
  void keepsEveryReferenceFullQuestionModeAndExplicitEmptyScope() {
    for (var mode : VideoAvMode.values()) {
      var command =
          VideoAvQueryRequestMapper.attached(
              bytes(request(mode.name(), attachment(), ",\"document_ids\":[]")));
      assertEquals(mode, command.answer().mode());
      assertEquals("画面和声音分别是什么？", command.answer().answer().question());
      assertFalse(command.answer().answer().selection().all());
      assertTrue(command.answer().answer().selection().documentIds().isEmpty());
      assertArrayEquals(VideoAvTestFixture.raw(), command.attachments().getFirst().content());
      assertThrows(UnsupportedOperationException.class, () -> command.attachments().clear());
    }
    var command =
        VideoAvQueryRequestMapper.attached(
            bytes(
                request("JOINT", String.join(",", attachment(), attachment(), attachment()), "")));
    assertEquals(3, command.attachments().size());
    assertTrue(command.answer().answer().selection().all());
    assertEquals(
        command.attachments().getFirst().sha256(), command.attachments().getLast().sha256());
    assertFalse(command.toString().contains("clip.mp4"));
  }

  @Test
  void rejectsAmbiguousJsonUnrecognizedFieldsModesAndPartialReferenceShapes() {
    String valid = request("VISUAL", attachment(), "");
    for (String invalid :
        List.of(
            valid + " {}",
            valid.replace("\"mode\":\"VISUAL\"", "\"mode\":\"VISUAL\",\"mode\":\"AUDIO\""),
            request("visual", attachment(), ""),
            request("SOUND", attachment(), ""),
            request("VISUAL", "", ""),
            request(
                "VISUAL",
                String.join(",", attachment(), attachment(), attachment(), attachment()),
                ""),
            request("VISUAL", attachment(), ",\"document_ids\":null"),
            request("VISUAL", attachment(), ",\"extra\":true"),
            valid.replace("\"filename\":\"clip.mp4\",", ""),
            valid.replace("\"filename\":\"clip.mp4\"", "\"filename\":42"),
            valid.replace("\"filename\":\"clip.mp4\"", "\"filename\":\"clip.mp4\",\"extra\":1"))) {
      assertThrows(
          ApplicationException.class, () -> VideoAvQueryRequestMapper.attached(bytes(invalid)));
    }
    assertThrows(
        ApplicationException.class,
        () -> VideoAvQueryRequestMapper.attached(new byte[] {(byte) 0xc3, 0x28}));
  }

  @Test
  void rejectsNoncanonicalBase64WrongContainerAndWholeBatchRawBudget() {
    String valid = request("JOINT", attachment(), "");
    String base64 = Base64.getEncoder().encodeToString(VideoAvTestFixture.raw());
    for (String invalid :
        List.of(
            valid.replace(base64, base64.replace("=", "")),
            valid.replace(base64, "!!!!"),
            valid.replace(base64, Base64.getEncoder().encodeToString(new byte[16])),
            valid.replace("video/mp4", "audio/mp4"),
            valid.replace("clip.mp4", "clip.webm"))) {
      assertThrows(
          ApplicationException.class, () -> VideoAvQueryRequestMapper.attached(bytes(invalid)));
    }
    byte[] large = new byte[10 * 1024 * 1024];
    System.arraycopy(VideoAvTestFixture.raw(), 0, large, 0, VideoAvTestFixture.raw().length);
    String item = attachment().replace(base64, Base64.getEncoder().encodeToString(large));
    assertEquals(
        "query_request_too_large",
        assertThrows(
                ApplicationException.class,
                () ->
                    VideoAvQueryRequestMapper.attached(
                        bytes(request("VISUAL", item + "," + item + "," + attachment(), ""))))
            .code());
    assertEquals(
        "query_request_too_large",
        assertThrows(
                ApplicationException.class,
                () -> VideoAvQueryRequestMapper.attached(new byte[28 * 1024 * 1024 + 1]))
            .code());
    assertThrows(
        ApplicationException.class,
        () ->
            VideoAvQueryRequestMapper.attached(
                bytes(valid.replace("画面和声音分别是什么？", "中".repeat(1366)))));
  }

  @Test
  void responseHasExactNestedOldAnswerAndElevenFieldHashOnlyReceipts() {
    var item =
        VideoAvQueryAttachmentResult.from(
            VideoAvQueryManifest.notPrepared(0, VideoAvMode.JOINT, "compiler-v1", "a".repeat(64)));
    var items = new ArrayList<>(List.of(item));
    var result =
        new VideoAvQueryAnswerResult(
            "JOINT",
            new VideoAvAnswerResult(
                "id", "abstained", "JOINT", null, "empty_scope", List.of(), "policy-v1"),
            items);
    items.clear();
    var json = JsonMapper.builder().build().valueToTree(result);
    assertEquals(
        Set.of("mode", "result", "query_attachments"), new HashSet<>(json.propertyNames()));
    assertEquals(7, json.path("result").size());
    var receipt = json.path("query_attachments").get(0);
    assertEquals(
        Set.of(
            "ordinal",
            "source_sha256",
            "media_kind",
            "compiler_revision",
            "content_sha256",
            "window_count",
            "visual_window_count",
            "audio_window_count",
            "audio_present",
            "used_mode",
            "status"),
        new HashSet<>(receipt.propertyNames()));
    for (String field :
        List.of(
            "content_sha256",
            "window_count",
            "visual_window_count",
            "audio_window_count",
            "audio_present")) {
      assertTrue(receipt.path(field).isNull());
    }
    assertEquals("not_prepared", receipt.path("status").stringValue());
    assertThrows(UnsupportedOperationException.class, () -> result.queryAttachments().clear());
    assertFalse(result.toString().contains("empty_scope"));
  }

  private static String request(String mode, String attachments, String extra) {
    return "{\"question\":\"画面和声音分别是什么？\",\"mode\":\""
        + mode
        + "\",\"attachments\":["
        + attachments
        + "]"
        + extra
        + "}";
  }

  private static String attachment() {
    return "{\"filename\":\"clip.mp4\",\"media_type\":\"video/mp4\",\"content_base64\":\""
        + Base64.getEncoder().encodeToString(VideoAvTestFixture.raw())
        + "\"}";
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
