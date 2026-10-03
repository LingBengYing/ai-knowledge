package com.evidence.rag.web.converter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class VoiceQuestionRequestMapperTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void acceptsEveryExistingAudioMimeAndPreservesCompleteBytesWithoutAQuestionOrScope() {
    for (String mime :
        List.of("audio/wav", "audio/mpeg", "audio/flac", "audio/ogg", "audio/mp4", "audio/webm")) {
      byte[] source = new byte[30001];
      source[source.length - 1] = 99;
      var command = VoiceQuestionRequestMapper.command(body("用户问题.wav", mime, source));
      assertEquals("用户问题.wav", command.audio().filename());
      assertEquals(mime, command.audio().mediaType());
      assertArrayEquals(source, command.audio().content());
      assertFalse(command.toString().contains("用户问题"));
    }
  }

  @Test
  void requiresOneExactObjectWithStringFieldsAndRejectsDuplicateOrTrailingJson() {
    for (String input :
        List.of(
            "null",
            "[]",
            "{}",
            "{",
            "{\"filename\":\"q.wav\",\"media_type\":\"audio/wav\"}",
            "{\"filename\":1,\"media_type\":\"audio/wav\",\"content_base64\":\"AQID\"}",
            "{\"filename\":\"q.wav\",\"media_type\":1,\"content_base64\":\"AQID\"}",
            "{\"filename\":\"q.wav\",\"media_type\":\"audio/wav\",\"content_base64\":1}",
            "{\"filename\":\"q.wav\",\"filename\":\"r.wav\",\"media_type\":\"audio/wav\",\"content_base64\":\"AQID\"}",
            "{\"filename\":\"q.wav\",\"media_type\":\"audio/wav\",\"content_base64\":\"AQID\",\"question\":\"q\"}",
            "{\"filename\":\"q.wav\",\"media_type\":\"audio/wav\",\"content_base64\":\"AQID\",\"document_ids\":[]}",
            "{\"filename\":\"q.wav\",\"media_type\":\"audio/wav\",\"content_base64\":\"AQID\",\"mode\":\"text\"}",
            "{\"filename\":\"q.wav\",\"media_type\":\"audio/wav\",\"content_base64\":\"AQID\"} {}")) {
      invalid(input.getBytes(StandardCharsets.UTF_8));
    }
    for (byte[] input : Arrays.asList(null, new byte[0], new byte[] {(byte) 0xc3, 0x28})) {
      invalid(input);
    }
  }

  @Test
  void canonicalBase64AndOnlyNonemptyAudioWithSafeFilenamesAreRequired() {
    for (String encoded : List.of("", "AQ", "AB==", "AQ==\n", "%bad")) {
      invalid(
          JSON.writeValueAsBytes(
              Map.of("filename", "q.wav", "media_type", "audio/wav", "content_base64", encoded)));
    }
    for (String filename : List.of("", "../q.wav", "a\\q.wav", "q\n.wav")) {
      invalid(body(filename, "audio/wav", new byte[] {1}));
    }
    for (String mime : List.of("image/png", "video/mp4", "application/octet-stream", "audio/aac")) {
      invalid(body("q.wav", mime, new byte[] {1}));
    }
  }

  @Test
  void decodedAudioAndJsonHaveSeparateExactBounds() {
    byte[] maximum = new byte[20 * 1024 * 1024];
    var accepted = body("q.wav", "audio/wav", maximum);
    assertTrue(accepted.length < VoiceQuestionRequestMapper.MAX_REQUEST_BYTES);
    assertArrayEquals(maximum, VoiceQuestionRequestMapper.command(accepted).audio().content());
    var oversized = body("q.wav", "audio/wav", new byte[maximum.length + 1]);
    assertTrue(oversized.length < VoiceQuestionRequestMapper.MAX_REQUEST_BYTES);
    assertEquals(
        "query_request_too_large",
        assertThrows(
                ApplicationException.class, () -> VoiceQuestionRequestMapper.command(oversized))
            .code());
    assertEquals(
        "query_request_too_large",
        assertThrows(
                ApplicationException.class,
                () ->
                    VoiceQuestionRequestMapper.command(
                        new byte[VoiceQuestionRequestMapper.MAX_REQUEST_BYTES + 1]))
            .code());
  }

  private static byte[] body(String filename, String mime, byte[] source) {
    return JSON.writeValueAsBytes(
        Map.of(
            "filename", filename,
            "media_type", mime,
            "content_base64", Base64.getEncoder().encodeToString(source)));
  }

  private static void invalid(byte[] body) {
    assertEquals(
        "invalid_request",
        assertThrows(ApplicationException.class, () -> VoiceQuestionRequestMapper.command(body))
            .code());
  }
}
