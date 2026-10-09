package com.evidence.rag.web.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.dto.ProductHelpCommand;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductHelpRequestMapperTest {
  @Test
  void legacyDefaultsRemainFiveAndRerankEnabled() {
    var command = request("{\"question\":\"灯塔\"}");
    assertEquals("灯塔", command.answer().question());
    assertEquals(5, command.topK());
    assertTrue(command.rerank());
    assertTrue(command.answer().selection().all());
  }

  @Test
  void partialLegacyOptionsApplyOnlyToTheirOwnField() {
    var topK = request("{\"question\":\"q\",\"top_k\":10}");
    assertEquals(10, topK.topK());
    assertTrue(topK.rerank());
    var rerank = request("{\"question\":\"q\",\"rerank\":false}");
    assertEquals(5, rerank.topK());
    assertFalse(rerank.rerank());
    var both = request("{\"question\":\"q\",\"top_k\":1,\"rerank\":false}");
    assertEquals(1, both.topK());
    assertFalse(both.rerank());
  }

  @Test
  void legacyPerCategoryLimitDoesNotExpandToTheRetrievalTestLimit() {
    for (String topK : List.of("0", "11", "20", "21", "null", "1.0", "\"5\"")) {
      assertThrows(
          ApplicationException.class, () -> request("{\"question\":\"q\",\"top_k\":" + topK + "}"));
    }
  }

  @Test
  void newSettingsAreRejectedInsteadOfPretendingToApplyThemToTheLegacyEndpoint() {
    String settings =
        "{\"search_method\":\"hybrid\",\"ranking_mode\":\"rerank\","
            + "\"dense_weight\":0.5,\"top_k\":1,\"score_threshold_enabled\":true,\"score_threshold\":0.9}";
    assertThrows(
        ApplicationException.class,
        () -> request("{\"question\":\"q\",\"retrieval_settings\":" + settings + "}"));
    assertThrows(
        ApplicationException.class,
        () -> request("{\"question\":\"q\",\"retrieval_settings\":null}"));
  }

  @Test
  void strictShapeAndSharedScopeCompatibilityRemainUnchanged() {
    assertTrue(request("{\"question\":\"q\",\"document_ids\":[]}").answer().selection().all());
    for (String body :
        List.of(
            "{}",
            "{\"question\":\"q\",\"rerank\":null}",
            "{\"question\":\"q\",\"top_k\":1,\"top_k\":2}",
            "{\"question\":\"q\",\"unknown\":true}",
            "{\"question\":\"q\"}{}")) {
      assertThrows(ApplicationException.class, () -> request(body));
    }
  }

  private static ProductHelpCommand request(String body) {
    return ProductHelpRequestMapper.command(body.getBytes(StandardCharsets.UTF_8));
  }
}
