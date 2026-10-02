package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.evidence.rag.model.dto.VisualAnswerResult;
import com.evidence.rag.model.dto.VisualCitationResult;
import com.evidence.rag.model.dto.VisualSourceResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class VisualDtoTest {
  @Test
  void diagnosticsAreRedactedWhileWireContractRemainsExplicit() {
    var citation =
        new VisualCitationResult(
            1,
            "image_region",
            "doc",
            "rev",
            "a".repeat(64),
            "parser",
            "private-name.png",
            "image/png",
            640,
            320,
            List.of(0.0, 0.0, 1.0, 1.0),
            "normalized_xyxy",
            "model",
            "policy",
            "/v1/visual-sources/id/1",
            "/v1/visual-sources/id/1/content");
    var answer =
        new VisualAnswerResult("id", "answered", "private-answer", null, List.of(citation));
    var source = new VisualSourceResult("id", citation);
    assertFalse(answer.toString().contains("private-answer"));
    assertFalse(citation.toString().contains("private-name"));
    assertFalse(source.toString().contains("private-name"));
    var json = JsonMapper.builder().build().valueToTree(answer);
    assertEquals("id", json.path("answer_id").asString());
    var wire = json.path("citations").get(0);
    assertEquals("doc", wire.path("document_id").asString());
    assertEquals("private-name.png", wire.path("filename").asString());
    for (String forbidden : List.of("page", "start", "end", "quote")) {
      assertFalse(wire.has(forbidden));
    }
  }
}
