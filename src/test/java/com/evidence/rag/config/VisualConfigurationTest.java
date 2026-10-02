package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class VisualConfigurationTest {
  @Test
  void localOptInConstructsModelWithoutCallingItAndFreezesPreparationRevision() {
    var configuration = new VisualConfiguration();
    try (var models = configuration.visionModels(local())) {
      assertEquals(
          "java-image-visual-v1:" + models.revision(),
          configuration.visualIngestionOptions(models).parserRevision());
      assertFalse(models.toString().contains("synthetic-visual-credential"));
    }
  }

  @Test
  void invalidOptInFailsWithOnlySanitizedConfigurationError() {
    var configuration = new VisualConfiguration();
    for (var environment :
        new MockEnvironment[] {
          local().withProperty("server.address", "0.0.0.0"),
          local().withProperty("rag.environment", "production"),
          local().withProperty("rag.answers.enabled", "false"),
          local().withProperty("rag.visual.base-url", "bad private endpoint"),
          local().withProperty("rag.visual.deadline-ms", "60001")
        }) {
      var failure =
          assertThrows(
              IllegalArgumentException.class, () -> configuration.visionModels(environment));
      assertEquals("Invalid local visual model configuration", failure.getMessage());
      assertNull(failure.getCause());
    }
  }

  @Test
  void visualUploadIsAdvertisedWithoutAdvertisingNewOcrUploads() {
    var runtime =
        new RuntimeService("development_headers", "org", true, true, true, false, true, true);
    assertEquals("visual_answers", runtime.capabilities().migrationStage());
    assertTrue(runtime.capabilities().capabilities().contains("visual_image_upload"));
    assertFalse(runtime.capabilities().capabilities().contains("image_text_upload"));
    assertTrue(runtime.capabilities().capabilities().contains("source_image_content"));
  }

  private static MockEnvironment local() {
    return new MockEnvironment()
        .withProperty("server.address", "127.0.0.1")
        .withProperty("rag.environment", "test")
        .withProperty("rag.answers.enabled", "true")
        .withProperty("rag.visual.base-url", "http://127.0.0.1:1/v1")
        .withProperty("rag.visual.model", "synthetic-vision")
        .withProperty("rag.visual.api-key", "synthetic-visual-credential")
        .withProperty("rag.visual.allow-loopback-http", "true");
  }
}
