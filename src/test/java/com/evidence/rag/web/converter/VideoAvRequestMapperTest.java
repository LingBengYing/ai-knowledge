package com.evidence.rag.web.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.VideoAvMode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class VideoAvRequestMapperTest {
  @Test
  void keepsCompleteQuestionModeAndExplicitEmptySelection() {
    for (var mode : VideoAvMode.values()) {
      var request =
          VideoAvRequestMapper.command(
              bytes(
                  "{\"question\":\"画面是什么？\\n声音是什么？\",\"mode\":\""
                      + mode.name()
                      + "\",\"document_ids\":[]}"));
      assertEquals(mode, request.mode());
      assertEquals("画面是什么？\n声音是什么？", request.answer().question());
      assertFalse(request.answer().selection().all());
      assertTrue(request.answer().selection().documentIds().isEmpty());
    }
    assertTrue(
        VideoAvRequestMapper.command(bytes("{\"question\":\"What?\",\"mode\":\"JOINT\"}"))
            .answer()
            .selection()
            .all());
  }

  @Test
  void refusesImplicitModesAttachmentsAndAmbiguousJson() {
    for (String input :
        List.of(
            "{\"question\":\"What?\"}",
            "{\"question\":\"What?\",\"mode\":\"joint\"}",
            "{\"question\":\"What?\",\"mode\":\"SOUND\"}",
            "{\"question\":\"What?\",\"mode\":\"JOINT\",\"attachments\":[]}",
            "{\"question\":\"What?\",\"mode\":\"JOINT\",\"mode\":\"AUDIO\"}",
            "{\"question\":\"What?\",\"mode\":\"JOINT\",\"document_ids\":null}",
            "{\"question\":\"What?\",\"mode\":\"JOINT\"} {}")) {
      assertThrows(ApplicationException.class, () -> VideoAvRequestMapper.command(bytes(input)));
    }
  }

  @Test
  void rejectsMalformedUtf8AndOversizedQuestionWithoutTruncation() {
    assertThrows(
        ApplicationException.class,
        () -> VideoAvRequestMapper.command(new byte[] {(byte) 0xc3, 0x28}));
    assertThrows(ApplicationException.class, () -> VideoAvRequestMapper.command(new byte[65537]));
    assertThrows(
        ApplicationException.class,
        () ->
            VideoAvRequestMapper.command(
                bytes("{\"question\":\"" + "中".repeat(1366) + "\",\"mode\":\"VISUAL\"}")));
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
