package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.RetrievalSettings;
import com.evidence.rag.web.converter.RetrievalSettingsRequestMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RetrievalSettingsRequestMapperTest {
  private static final String BODY =
      """
      {"version":0,"search_method":"hybrid","ranking_mode":"rerank","dense_weight":0.5,
      "top_k":5,"score_threshold_enabled":false,"score_threshold":0.5}
      """;

  @Test
  void saveAndCompleteTemporaryOverrideShareValuesWithoutClientVersion() {
    assertEquals(RetrievalSettings.defaults(), parse(BODY));
    var override = JsonMapper.builder().build().readTree(BODY.replace("\"version\":0,", ""));
    assertEquals(
        new RetrievalSettings(9, "hybrid", "rerank", 0.5, 5, false, 0.5),
        RetrievalSettingsRequestMapper.override(override, 9));
    assertEquals(
        -1.5,
        parse(BODY.replace("\"score_threshold\":0.5", "\"score_threshold\":-1.5"))
            .scoreThreshold());
    assertEquals(20, parse(BODY.replace("\"top_k\":5", "\"top_k\":20")).topK());
    assertEquals(1, parse(BODY.replace("\"top_k\":5", "\"top_k\":1")).topK());
  }

  @Test
  void invalidTypesMissingFieldsUnknownFieldsAndDuplicateKeysFailExplicitly() {
    for (String body :
        List.of(
            "",
            "null",
            "[]",
            "{}",
            BODY + "{}",
            BODY.replace("\"version\":0,", ""),
            BODY.replace("\"top_k\":5,", ""),
            BODY.replace("\"version\":0", "\"version\":0,\"version\":1"),
            BODY.replace("\"version\":0", "\"version\":0,\"unknown\":1"),
            BODY.replace("\"version\":0", "\"version\":9007199254740992"),
            BODY.replace("\"top_k\":5", "\"top_k\":5.0"),
            BODY.replace("\"top_k\":5", "\"top_k\":\"5\""),
            BODY.replace("\"top_k\":5", "\"top_k\":21"),
            BODY.replace("\"dense_weight\":0.5", "\"dense_weight\":2"),
            BODY.replace(
                "\"score_threshold_enabled\":false", "\"score_threshold_enabled\":\"false\""),
            BODY.replace("\"score_threshold\":0.5", "\"score_threshold\":1e999"))) {
      assertEquals(
          FailureKind.INVALID_REQUEST,
          assertThrows(ApplicationException.class, () -> parse(body)).kind());
    }
    assertEquals(
        FailureKind.INVALID_REQUEST,
        assertThrows(
                ApplicationException.class,
                () -> RetrievalSettingsRequestMapper.save(new byte[] {(byte) 0xc3, 0x28}))
            .kind());
    assertEquals(
        FailureKind.PAYLOAD_TOO_LARGE,
        assertThrows(
                ApplicationException.class,
                () ->
                    RetrievalSettingsRequestMapper.save(
                        new byte[RetrievalSettingsRequestMapper.MAX_BYTES + 1]))
            .kind());
  }

  @Test
  void temporaryOverrideCannotBePartialOrSupplyItsOwnVersion() {
    var json = JsonMapper.builder().build();
    for (String body :
        List.of(
            BODY,
            "{}",
            "null",
            "[]",
            BODY.replace("\"version\":0,", "").replace("\"top_k\":5,", ""))) {
      assertEquals(
          FailureKind.INVALID_REQUEST,
          assertThrows(
                  ApplicationException.class,
                  () -> RetrievalSettingsRequestMapper.override(json.readTree(body), 3))
              .kind());
    }
  }

  private static RetrievalSettings parse(String body) {
    return RetrievalSettingsRequestMapper.save(body.getBytes(StandardCharsets.UTF_8));
  }
}
