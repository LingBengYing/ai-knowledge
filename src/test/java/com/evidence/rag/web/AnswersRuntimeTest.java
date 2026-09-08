package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.controller.RuntimeController;
import com.evidence.rag.service.RuntimeService;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnswersRuntimeTest {
  @Test
  void answerCapabilityRequiresItsOwnFlagAndNeverMeansProductionReadiness() {
    for (boolean ingestion : List.of(false, true)) {
      for (boolean indexing : List.of(false, true)) {
        for (boolean answers : List.of(false, true)) {
          var controller =
              new RuntimeController(
                  new RuntimeService("jwt", "org-main", ingestion, indexing, answers));
          var config = controller.configuration();
          for (String name : List.of("answers", "sources")) {
            assertEquals(answers, config.capabilities().contains(name));
            assertEquals(!answers, config.unavailable().contains(name));
          }
          assertEquals(
              answers
                  ? "text_answers"
                  : indexing ? "text_indexing" : ingestion ? "text_ingestion" : "management_slice",
              config.migrationStage());
          assertEquals(503, controller.ready().getStatusCode().value());
          assertFalse(config.capabilities().contains("reindex"));
          assertTrue(config.unavailable().contains("document_delete"));
        }
      }
    }
  }
}
