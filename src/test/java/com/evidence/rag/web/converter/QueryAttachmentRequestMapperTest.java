package com.evidence.rag.web.converter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.dto.QueryAnswerMode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class QueryAttachmentRequestMapperTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void preservesOriginalQuestionSelectionAndAllThreeMediaPayloads() {
    String question = " 原问题\n尾部条件？";
    var command =
        QueryAttachmentRequestMapper.command(
            JSON.writeValueAsBytes(
                Map.of(
                    "question",
                    question,
                    "document_ids",
                    List.of("doc-1"),
                    "mode",
                    "video_joint",
                    "attachments",
                    List.of(
                        asset("q.png", "image/png"),
                        asset("q.wav", "audio/wav"),
                        asset("q.mp4", "video/mp4")))));
    assertEquals(question, command.answer().question());
    assertEquals(QueryAnswerMode.VIDEO_JOINT, command.mode());
    assertEquals(3, command.attachments().size());
    assertArrayEquals(new byte[] {1, 2, 3}, command.attachments().getLast().content());
    assertEquals("q.mp4", command.attachments().getLast().filename());
  }

  @Test
  void acceptsEveryExplicitModeAndEmptyAttachmentsWithoutRequiringSelectedScope() {
    for (var mode : QueryAnswerMode.values()) {
      var command =
          QueryAttachmentRequestMapper.command(
              JSON.writeValueAsBytes(
                  Map.of(
                      "question",
                      "question",
                      "mode",
                      mode.name().toLowerCase(java.util.Locale.ROOT),
                      "attachments",
                      List.of())));
      assertEquals(mode, command.mode());
      assertTrue(command.attachments().isEmpty());
    }
  }

  @Test
  void strictIndependentParserRejectsDuplicatesTrailingTokensUnknownFieldsAndBadBase64() {
    for (String value :
        List.of(
            "null",
            "[]",
            "{}",
            "{",
            "{\"question\":\"x\",\"question\":\"y\",\"mode\":\"text\",\"attachments\":[]}",
            "{\"question\":\"x\",\"mode\":\"text\",\"attachments\":[]} {}",
            "{\"question\":\"x\",\"mode\":\"text\",\"attachments\":[],\"role\":\"owner\"}",
            "{\"question\":\"x\",\"mode\":\"TEXT\",\"attachments\":[]}",
            "{\"question\":\"x\",\"mode\":\"text\",\"attachments\":null}",
            "{\"question\":\"x\",\"mode\":\"text\",\"attachments\":[{\"filename\":\"x.png\",\"media_type\":\"image/png\",\"content_base64\":\"%bad\"}]}")) {
      assertEquals(
          "invalid_request",
          assertThrows(
                  ApplicationException.class,
                  () ->
                      QueryAttachmentRequestMapper.command(value.getBytes(StandardCharsets.UTF_8)))
              .code());
    }
  }

  @Test
  void largeValidBase64UsesIndependentLimitWhileDecodedTotalRemainsBounded() {
    byte[] bytes = new byte[30000];
    var request =
        JSON.writeValueAsBytes(
            Map.of(
                "question",
                "question",
                "mode",
                "audio",
                "attachments",
                List.of(
                    Map.of(
                        "filename",
                        "q.wav",
                        "media_type",
                        "audio/wav",
                        "content_base64",
                        Base64.getEncoder().encodeToString(bytes)))));
    assertArrayEquals(
        bytes, QueryAttachmentRequestMapper.command(request).attachments().getFirst().content());
    var many =
        JSON.writeValueAsBytes(
            Map.of(
                "question",
                "q",
                "mode",
                "text",
                "attachments",
                List.of(
                    asset("q.png", "image/png"),
                    asset("q.png", "image/png"),
                    asset("q.png", "image/png"),
                    asset("q.png", "image/png"))));
    assertThrows(ApplicationException.class, () -> QueryAttachmentRequestMapper.command(many));
    assertEquals(
        "query_request_too_large",
        assertThrows(
                ApplicationException.class,
                () ->
                    QueryAttachmentRequestMapper.command(
                        new byte[QueryAttachmentRequestMapper.MAX_REQUEST_BYTES + 1]))
            .code());
  }

  private static Map<String, String> asset(String filename, String type) {
    return Map.of("filename", filename, "media_type", type, "content_base64", "AQID");
  }

  @Test
  void absentEmptyAndMalformedUtf8BodiesNeverBecomeAQuestion() {
    for (byte[] body :
        java.util.Arrays.asList(
            null,
            new byte[0],
            new byte[] {(byte) 0xc3, 0x28},
            " \n\t".getBytes(StandardCharsets.UTF_8))) {
      assertEquals(
          "invalid_request",
          assertThrows(ApplicationException.class, () -> QueryAttachmentRequestMapper.command(body))
              .code());
    }
  }

  @Test
  void requiredFieldsHaveExactJsonTypesAndModeNeverDefaults() {
    for (String body :
        List.of(
            "{\"question\":\"q\"}",
            "{\"question\":\"q\",\"mode\":\"text\"}",
            "{\"question\":1,\"mode\":\"text\",\"attachments\":[]}",
            "{\"question\":\"q\",\"mode\":1,\"attachments\":[]}",
            "{\"question\":\"q\",\"mode\":\"unsupported\",\"attachments\":[]}",
            "{\"question\":\"q\",\"mode\":\"text\",\"attachments\":{}}")) {
      assertEquals(
          "invalid_request",
          assertThrows(
                  ApplicationException.class,
                  () -> QueryAttachmentRequestMapper.command(body.getBytes(StandardCharsets.UTF_8)))
              .code());
    }
  }

  @Test
  void attachmentFieldsCannotHideMissingUnknownOrNonStringValues() {
    for (String attachment :
        List.of(
            "null",
            "[]",
            "1",
            "{\"filename\":\"a.png\",\"media_type\":\"image/png\"}",
            "{\"filename\":\"a.png\",\"media_type\":\"image/png\",\"bytes\":\"AQID\"}",
            "{\"filename\":1,\"media_type\":\"image/png\",\"content_base64\":\"AQID\"}",
            "{\"filename\":\"a.png\",\"media_type\":1,\"content_base64\":\"AQID\"}",
            "{\"filename\":\"a.png\",\"media_type\":\"image/png\",\"content_base64\":1}")) {
      String body = "{\"question\":\"q\",\"mode\":\"text\",\"attachments\":[" + attachment + "]}";
      assertEquals(
          "invalid_request",
          assertThrows(
                  ApplicationException.class,
                  () -> QueryAttachmentRequestMapper.command(body.getBytes(StandardCharsets.UTF_8)))
              .code());
    }
  }

  @Test
  void canonicalBase64MimeAndFilenameAreValidatedBeforeCompilation() {
    for (Map<String, String> attachment :
        List.of(
            Map.of("filename", "a.png", "media_type", "image/png", "content_base64", "AQ"),
            Map.of("filename", "a.png", "media_type", "image/png", "content_base64", "AB=="),
            Map.of("filename", "a.png", "media_type", "image/png", "content_base64", ""),
            asset("../a.png", "image/png"),
            asset("a.gif", "image/gif"))) {
      byte[] body =
          JSON.writeValueAsBytes(
              Map.of("question", "q", "mode", "text", "attachments", List.of(attachment)));
      assertEquals(
          "invalid_request",
          assertThrows(ApplicationException.class, () -> QueryAttachmentRequestMapper.command(body))
              .code());
    }
  }

  @Test
  void explicitEmptyDocumentSelectionStaysEmptyAndMalformedSelectionNeverBecomesAll() {
    var selected =
        QueryAttachmentRequestMapper.command(
            JSON.writeValueAsBytes(
                Map.of(
                    "question",
                    "q",
                    "mode",
                    "text",
                    "attachments",
                    List.of(),
                    "document_ids",
                    List.of())));
    assertEquals(false, selected.answer().selection().all());
    assertTrue(selected.answer().selection().documentIds().isEmpty());
    var all =
        QueryAttachmentRequestMapper.command(
            JSON.writeValueAsBytes(
                Map.of("question", "q", "mode", "text", "attachments", List.of())));
    assertTrue(all.answer().selection().all());
    for (String scope : List.of("null", "1", "{}", "[1]", "[\"doc\",\"doc\"]")) {
      String body =
          "{\"question\":\"q\",\"mode\":\"text\",\"attachments\":[],\"document_ids\":"
              + scope
              + "}";
      assertEquals(
          "invalid_request",
          assertThrows(
                  ApplicationException.class,
                  () -> QueryAttachmentRequestMapper.command(body.getBytes(StandardCharsets.UTF_8)))
              .code());
    }
  }

  @Test
  void aggregateDecodedBytesAreCheckedEvenWhenJsonFitsItsIndependentTransportLimit() {
    String first = Base64.getEncoder().encodeToString(new byte[10 * 1024 * 1024]);
    String second = Base64.getEncoder().encodeToString(new byte[10 * 1024 * 1024 + 1]);
    var request =
        JSON.writeValueAsBytes(
            Map.of(
                "question",
                "q",
                "mode",
                "audio",
                "attachments",
                List.of(
                    Map.of("filename", "a.wav", "media_type", "audio/wav", "content_base64", first),
                    Map.of(
                        "filename",
                        "b.wav",
                        "media_type",
                        "audio/wav",
                        "content_base64",
                        second))));
    assertTrue(request.length < QueryAttachmentRequestMapper.MAX_REQUEST_BYTES);
    assertEquals(
        "query_request_too_large",
        assertThrows(
                ApplicationException.class, () -> QueryAttachmentRequestMapper.command(request))
            .code());
  }
}
