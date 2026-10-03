package com.evidence.rag.web.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.QueryAttachment;
import com.evidence.rag.model.domain.QueryAttachmentManifest;
import com.evidence.rag.model.dto.SoundQueryCommand;
import com.evidence.rag.model.dto.SoundQueryManifestResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SoundRequestBoundaryTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Map<String, Object> AUDIO =
      Map.of("filename", "tone.wav", "media_type", "audio/wav", "content_base64", "AQI=");

  @Test
  void plainRequestNeedsOneObjectStringQuestionAndNeverAcceptsEmptyJsonOrAlternateTypes() {
    for (String body :
        List.of("", "null", "[]", "true", "{\"question\":null}", "{\"question\":12}", "{}")) {
      assertThrows(
          ApplicationException.class,
          () -> SoundRequestMapper.command(body.getBytes(StandardCharsets.UTF_8)));
    }
  }

  @Test
  void attachmentsRequireTheExactSoundModeAndOneToThreeCompleteObjects() {
    for (Object mode : List.of(false, 1, List.of(), "AUDIO")) {
      var body = body(List.of(AUDIO));
      body.put("mode", mode);
      invalid(body);
    }
    for (Object attachments :
        List.of(
            "audio",
            List.of(),
            List.of(AUDIO, AUDIO, AUDIO, AUDIO),
            List.of("audio"),
            List.of(Map.of()))) {
      var body = body(List.of(AUDIO));
      body.put("attachments", attachments);
      invalid(body);
    }
    var missing = body(List.of(AUDIO));
    missing.remove("attachments");
    invalid(missing);
    var accepted =
        SoundRequestMapper.attached(JSON.writeValueAsBytes(body(List.of(AUDIO, AUDIO, AUDIO))));
    assertEquals(3, accepted.attachments().size());
  }

  @Test
  void filenameMimeAndBase64MustBeStringsAndBase64MustBeCanonical() {
    for (String field : List.of("filename", "media_type", "content_base64")) {
      var attachment = new LinkedHashMap<String, Object>(AUDIO);
      attachment.put(field, 1);
      invalid(body(List.of(attachment)));
    }
    for (String value : List.of("@@@=", "AQI", "AQJ=", "")) {
      var attachment = new LinkedHashMap<String, Object>(AUDIO);
      attachment.put("content_base64", value);
      invalid(body(List.of(attachment)));
    }
  }

  @Test
  void totalDecodedLimitRejectsBothEncodedExcessAndSameQuartetOverflow() {
    for (int excess : new int[] {1, 3}) {
      var attachment = new LinkedHashMap<String, Object>(AUDIO);
      attachment.put(
          "content_base64",
          Base64.getEncoder().encodeToString(new byte[20 * 1024 * 1024 + excess]));
      byte[] bytes = JSON.writeValueAsBytes(body(List.of(attachment)));
      var failure =
          assertThrows(ApplicationException.class, () -> SoundRequestMapper.attached(bytes));
      assertEquals("query_request_too_large", failure.code());
    }
  }

  @Test
  void directSoundQueryCommandEnforcesCompleteAudioOnlyImmutableBatchAndTotalBytes() {
    var answer =
        SoundRequestMapper.command("{\"question\":\"What?\"}".getBytes(StandardCharsets.UTF_8));
    var audio = new QueryAttachment("tone.wav", "audio/wav", new byte[] {1});
    for (List<QueryAttachment> attachments :
        Arrays.<List<QueryAttachment>>asList(
            null,
            List.of(),
            List.of(audio, audio, audio, audio),
            Arrays.asList(audio, null),
            List.of(new QueryAttachment("image.png", "image/png", new byte[] {1})))) {
      assertThrows(ApplicationException.class, () -> new SoundQueryCommand(answer, attachments));
    }
    assertThrows(ApplicationException.class, () -> new SoundQueryCommand(null, List.of(audio)));
    var large = new QueryAttachment("tone.wav", "audio/wav", new byte[10 * 1024 * 1024 + 1]);
    assertThrows(
        ApplicationException.class, () -> new SoundQueryCommand(answer, List.of(large, large)));
    var mutable = new ArrayList<>(List.of(audio));
    var command = new SoundQueryCommand(answer, mutable);
    mutable.clear();
    assertEquals(1, command.attachments().size());
    assertEquals("SoundQueryCommand[redacted]", command.toString());
  }

  @Test
  void soundManifestCannotBeCreatedFromAnImageOrMissingPreparation() {
    assertThrows(ApplicationException.class, () -> SoundQueryManifestResult.from(null));
    var image =
        new QueryAttachmentManifest(
            0,
            "a".repeat(64),
            QueryAttachment.Kind.IMAGE,
            "image-v1",
            "b".repeat(64),
            0,
            1,
            List.of("c".repeat(64)),
            false);
    assertThrows(ApplicationException.class, () -> SoundQueryManifestResult.from(image));
  }

  private static Map<String, Object> body(List<?> attachments) {
    var result = new LinkedHashMap<String, Object>();
    result.put("question", "What?");
    result.put("mode", "SOUND");
    result.put("attachments", attachments);
    return result;
  }

  private static void invalid(Map<String, Object> body) {
    byte[] bytes = JSON.writeValueAsBytes(body);
    assertThrows(ApplicationException.class, () -> SoundRequestMapper.attached(bytes));
  }
}
