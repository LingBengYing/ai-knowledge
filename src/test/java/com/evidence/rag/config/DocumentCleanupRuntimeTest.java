package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.controller.RuntimeController;
import com.evidence.rag.service.RuntimeService;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class DocumentCleanupRuntimeTest {
  @TempDir Path directory;

  @Test
  void oldConstructorAndFlagWithoutActualGraphNeverAdvertiseCleanup() {
    var legacy = new RuntimeService("development_headers", "org-main", false, false, false, true);
    assertFalse(legacy.capabilities().capabilities().contains("document_cleanup"));
    var configured =
        new RuntimeConfiguration()
            .runtimeService(
                new RagProperties("test", "org-main", "development_headers", "", "", "", directory),
                new IngestionSettings(false, 30000, 30000),
                new IndexingSettings(false, 60000),
                new AnswersSettings(false, 60000, 2),
                new DocumentRemovalSettings(true),
                new MockEnvironment().withProperty("rag.document-cleanup.enabled", "true"));
    assertFalse(configured.capabilities().capabilities().contains("document_cleanup"));
    assertTrue(configured.capabilities().capabilities().contains("document_removal"));
  }

  @Test
  void actualCleanupCapabilityLeavesGeneralDeleteReindexAndReadinessUnchanged() {
    var enabled =
        new RuntimeService(
            "development_headers",
            "org-main",
            false,
            false,
            false,
            true,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            true);
    assertTrue(enabled.capabilities().capabilities().contains("document_cleanup"));
    assertTrue(enabled.capabilities().unavailable().contains("document_delete"));
    assertTrue(enabled.capabilities().unavailable().contains("reindex"));
    assertEquals(503, new RuntimeController(enabled).ready().getStatusCode().value());
  }
}
