package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.config.RagProperties;
import com.evidence.rag.controller.RuntimeController;
import com.evidence.rag.service.RuntimeService;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuntimeControllerTest {
  @Test
  void eachCapabilityIsExplicitAndNeverEnablesAnswersOrProduction() {
    var properties =
        new RagProperties(
            "test",
            "org-main",
            "development_headers",
            "",
            "issuer",
            "audience",
            Path.of("unused-runtime-data"));
    for (boolean ingest : List.of(false, true)) {
      for (boolean index : List.of(false, true)) {
        var controller =
            new RuntimeController(
                new RuntimeService(properties.authMode(), properties.workspaceId(), ingest, index));
        var config = controller.configuration();
        var capabilities = (List<?>) config.capabilities();
        var unavailable = (List<?>) config.unavailable();
        assertEquals(
            index ? "text_indexing" : ingest ? "text_ingestion" : "management_slice",
            config.migrationStage());
        assertEquals(ingest, capabilities.contains("text_upload"));
        assertEquals(ingest, capabilities.contains("ingestions"));
        assertEquals(index, capabilities.contains("text_index"));
        assertEquals(index, capabilities.contains("indexings"));
        assertEquals(!index, unavailable.contains("text_index"));
        assertEquals(!index, unavailable.contains("indexings"));
        assertEquals(!ingest, unavailable.contains("upload"));
        assertEquals(!ingest, unavailable.contains("ingestions"));
        for (String missing : List.of("answers", "sources", "reindex", "document_delete")) {
          assertFalse(capabilities.contains(missing));
          assertTrue(unavailable.contains(missing));
        }
        assertEquals(503, controller.ready().getStatusCode().value());
        assertEquals("ok", controller.live().status());
      }
    }
  }
}
