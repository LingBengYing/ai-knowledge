package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.evidence.rag.controller.RuntimeController;
import java.util.List;
import org.junit.jupiter.api.Test;

class SynopsisRuntimeTest {
  @Test
  void summaryAvailabilityDoesNotImplicitlyEnableAnswerOrIngestionConsumers() {
    for (boolean enabled : List.of(false, true)) {
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
                  enabled));
      var response = controller.configuration();
      assertEquals(enabled, response.capabilities().contains("file_synopsis"));
      assertEquals(enabled, response.capabilities().contains("synopsis_sources"));
      assertEquals(!enabled, response.unavailable().contains("file_synopsis"));
      assertFalse(response.capabilities().contains("answers"));
      assertFalse(response.capabilities().contains("text_upload"));
      assertEquals(503, controller.ready().getStatusCode().value());
    }
  }
}
