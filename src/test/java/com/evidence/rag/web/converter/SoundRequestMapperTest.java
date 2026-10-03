package com.evidence.rag.web.converter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.QueryAttachment;
import com.evidence.rag.tool.parser.AudioPcm;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class SoundRequestMapperTest {
  @Test
  void fullQuestionAndExplicitEmptySelectionArePreserved() {
    String question = "声音是什么？\n也请说明末尾是否仍有声音。";
    var all =
        SoundRequestMapper.command(
            bytes("{\"question\":\"" + question.replace("\n", "\\n") + "\"}"));
    assertEquals(question, all.question());
    assertTrue(all.selection().all());
    var empty = SoundRequestMapper.command(bytes("{\"question\":\"问题\",\"document_ids\":[]}"));
    assertFalse(empty.selection().all());
    assertTrue(empty.selection().documentIds().isEmpty());
  }

  @Test
  void duplicateUnknownTrailingInvalidUtf8AndOverlongQuestionsFailClosed() {
    for (String json :
        List.of(
            "{\"question\":\"a\",\"question\":\"b\"}",
            "{\"question\":\"a\",\"mode\":\"SOUND\"}",
            "{\"question\":\"a\"} {}",
            "{\"question\":\"a\",\"document_ids\":null}",
            "{\"question\":\"a\",\"document_ids\":[1]}",
            "{\"question\":\"a\",\"document_ids\":[\"same\",\"same\"]}",
            "{\"question\":\"" + "问".repeat(1366) + "\"}")) {
      assertThrows(ApplicationException.class, () -> SoundRequestMapper.command(bytes(json)));
    }
    assertThrows(
        ApplicationException.class, () -> SoundRequestMapper.command(new byte[] {(byte) 0xff}));
    assertThrows(ApplicationException.class, () -> SoundRequestMapper.command(null));
    assertEquals(
        "query_request_too_large",
        assertThrows(ApplicationException.class, () -> SoundRequestMapper.command(new byte[65537]))
            .code());
  }

  @Test
  void attachedSoundRetainsCompleteCanonicalBytesWithoutTranscript() {
    byte[] wav = AudioPcm.wav(new byte[32002], 0, 32002);
    String json = attached(Base64.getEncoder().encodeToString(wav));
    var command = SoundRequestMapper.attached(bytes(json));
    assertEquals("完整问题", command.answer().question());
    assertEquals(List.of("doc"), command.answer().selection().documentIds());
    assertEquals(QueryAttachment.Kind.AUDIO, command.attachments().getFirst().kind());
    assertArrayEquals(wav, command.attachments().getFirst().content());
    assertThrows(UnsupportedOperationException.class, () -> command.attachments().clear());
  }

  @Test
  void attachedSoundRejectsOtherModesKindsNoncanonicalEncodingAndExtraFields() {
    String encoded = Base64.getEncoder().encodeToString(AudioPcm.wav(new byte[2], 0, 2));
    String valid = attached(encoded);
    for (String json :
        List.of(
            valid.replace("SOUND", "sound"),
            valid.replace("SOUND", "AUDIO"),
            valid.replace("\"filename\":", "\"extra\":true,\"filename\":"),
            valid.replace(encoded, encoded.substring(0, encoded.length() - 2)),
            valid.replace("audio/wav", "text/plain").replace("声音.wav", "note.txt"),
            valid.replace("\"attachments\":[", "\"attachments\":[] ,\"unused\":["))) {
      assertThrows(ApplicationException.class, () -> SoundRequestMapper.attached(bytes(json)));
    }
    assertEquals(
        "query_request_too_large",
        assertThrows(
                ApplicationException.class,
                () ->
                    SoundRequestMapper.attached(new byte[SoundRequestMapper.MAX_REQUEST_BYTES + 1]))
            .code());
  }

  private static String attached(String encoded) {
    return "{\"mode\":\"SOUND\",\"question\":\"完整问题\",\"document_ids\":[\"doc\"],"
        + "\"attachments\":[{\"filename\":\"声音.wav\",\"media_type\":\"audio/wav\",\"content_base64\":\""
        + encoded
        + "\"}]}";
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
