package com.evidence.rag.web.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.exception.ApplicationException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VideoAvRequestBoundaryTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        " ",
        "null",
        "[]",
        "42",
        "{",
        "{\"question\":42,\"mode\":\"JOINT\"}",
        "{\"question\":\"Question?\",\"mode\":42}",
        "{\"question\":\"Question?\",\"mode\":\"JOINT\",\"document_ids\":[42]}",
        "{\"question\":\"Question?\",\"mode\":\"JOINT\",\"document_ids\":[\"a\",\"a\"]}"
      })
  void untypedOrAmbiguousRequestNeverCreatesAnAnswerCommand(String body) {
    assertEquals(
        "invalid_request",
        assertThrows(
                ApplicationException.class,
                () -> VideoAvRequestMapper.command(body.getBytes(StandardCharsets.UTF_8)))
            .code());
  }

  @Test
  void absentBodyAndOversizedEnvelopeRetainDistinctRequestErrors() {
    assertEquals(
        "invalid_request",
        assertThrows(ApplicationException.class, () -> VideoAvRequestMapper.command(null)).code());
    assertEquals(
        "query_request_too_large",
        assertThrows(
                ApplicationException.class,
                () ->
                    VideoAvRequestMapper.command(
                        new byte[VideoAvRequestMapper.MAX_REQUEST_BYTES + 1]))
            .code());
    assertEquals(
        "invalid_request",
        assertThrows(
                ApplicationException.class,
                () ->
                    VideoAvRequestMapper.command(new byte[VideoAvRequestMapper.MAX_REQUEST_BYTES]))
            .code());
  }

  @Test
  void wholeUtf8QuestionAndExplicitScopeSurviveWithoutTruncationOrFallback() {
    String question = "x".repeat(4090) + "\n\t🔔";
    String escaped = question.replace("\n", "\\n").replace("\t", "\\t");
    var command =
        VideoAvRequestMapper.command(
            ("{\"question\":\""
                    + escaped
                    + "\",\"mode\":\"AUDIO\",\"document_ids\":[\"raw-video\",\"uncited-video\"]}")
                .getBytes(StandardCharsets.UTF_8));
    assertEquals(question, command.answer().question());
    assertFalse(command.answer().selection().all());
    assertEquals(List.of("raw-video", "uncited-video"), command.answer().selection().documentIds());
  }
}
