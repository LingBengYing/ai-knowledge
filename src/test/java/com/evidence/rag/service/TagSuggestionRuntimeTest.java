package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.evidence.rag.controller.RuntimeController;
import java.util.List;
import org.junit.jupiter.api.Test;

class TagSuggestionRuntimeTest {
  @Test
  void suggestionsFollowSynopsisWithoutImplicitlyEnablingOtherConsumers() {
    for (boolean synopsis : List.of(false, true)) {
      var controller =
          new RuntimeController(
              new RuntimeService(
                  "development_headers",
                  "org-main",
                  false,
                  false,
                  false,
                  false,
                  false,
                  false,
                  false,
                  false,
                  synopsis));
      var response = controller.configuration();
      assertEquals(synopsis, response.capabilities().contains("tag_suggestions"));
      assertEquals(synopsis, response.capabilities().contains("file_synopsis"));
      assertEquals(synopsis, response.capabilities().contains("synopsis_sources"));
      assertFalse(response.capabilities().contains("answers"));
      assertFalse(response.capabilities().contains("text_upload"));
      assertFalse(response.capabilities().contains("text_index"));
      assertEquals("management_slice", response.migrationStage());
      assertEquals(503, controller.ready().getStatusCode().value());
    }
  }
}
