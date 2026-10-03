package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.controller.RuntimeController;
import com.evidence.rag.service.RuntimeService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class PdfOcrRuntimeTest {
  @TempDir Path directory;

  @Test
  void configuredPdfCapabilityRequiresIngestionAndKeepsExistingStagesUnchanged() throws Exception {
    Path executable = directory.resolve("ocr-fixture");
    Files.writeString(executable, "synthetic executable, never launched");
    assertTrue(executable.toFile().setExecutable(true));
    for (boolean pdfOcr : List.of(false, true)) {
      for (boolean ingestion : List.of(false, true)) {
        for (boolean indexing : List.of(false, true)) {
          for (boolean answers : List.of(false, true)) {
            var environment =
                new MockEnvironment()
                    .withProperty("rag.pdf-ocr.enabled", Boolean.toString(pdfOcr))
                    .withProperty("server.address", "127.0.0.1")
                    .withProperty("rag.environment", "test")
                    .withProperty("rag.pdf-ocr.executable", executable.toString())
                    .withProperty("rag.pdf-ocr.revision", "fixture-v1");
            var service = configured(environment, ingestion, indexing, answers);
            var actual = service.capabilities();
            var baseline =
                new RuntimeService("development_headers", "org-main", ingestion, indexing, answers)
                    .capabilities();
            assertEquals(pdfOcr && ingestion, actual.capabilities().contains("pdf_ocr_upload"));
            assertEquals(
                baseline.capabilities(),
                actual.capabilities().stream()
                    .filter(name -> !name.equals("pdf_ocr_upload"))
                    .toList());
            assertEquals(baseline.unavailable(), actual.unavailable());
            assertEquals(baseline.migrationStage(), actual.migrationStage());
            assertFalse(actual.capabilities().contains("image_text_upload"));
            assertFalse(actual.capabilities().contains("source_image_content"));
            assertEquals(503, new RuntimeController(service).ready().getStatusCode().value());
          }
        }
      }
    }
  }

  @Test
  void enabledPdfFlagCannotAdvertiseWithoutValidConfiguration() {
    var environment =
        new MockEnvironment()
            .withProperty("rag.pdf-ocr.enabled", "true")
            .withProperty("server.address", "127.0.0.1")
            .withProperty("rag.environment", "test");
    assertThrows(IllegalArgumentException.class, () -> configured(environment, true, false, false));
  }

  @Test
  void oldFullConstructorRemainsPdfDisabled() {
    var legacy =
        new RuntimeService(
                "development_headers",
                "org-main",
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true)
            .capabilities();
    var pdfDisabled =
        new RuntimeService(
                "development_headers",
                "org-main",
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                false)
            .capabilities();
    assertEquals(legacy, pdfDisabled);
    assertFalse(legacy.capabilities().contains("pdf_ocr_upload"));
    assertTrue(legacy.capabilities().contains("query_attachments"));
  }

  private RuntimeService configured(
      MockEnvironment environment, boolean ingestion, boolean indexing, boolean answers) {
    return new RuntimeConfiguration()
        .runtimeService(
            new RagProperties("test", "org-main", "development_headers", "", "", "", directory),
            new IngestionSettings(ingestion, 30000, 30000),
            new IndexingSettings(indexing, 60000),
            new AnswersSettings(answers, 60000, 2),
            new DocumentRemovalSettings(false),
            environment);
  }
}
