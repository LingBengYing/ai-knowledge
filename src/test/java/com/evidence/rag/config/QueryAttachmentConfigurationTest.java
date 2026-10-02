package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class QueryAttachmentConfigurationTest {
  @Test
  void explicitLocalProfileConstructsRankingClientWithoutNetwork() {
    try (var ranking = new QueryAttachmentConfiguration().queryRankingModels(local())) {
      assertFalse(ranking.revision().isBlank());
      assertFalse(ranking.toString().contains("synthetic-ranking-credential"));
    }
  }

  @Test
  void rejectsMissingMediaDependenciesAndUnsafeBindingWithSanitizedError() {
    for (String key :
        new String[] {
          "rag.answers.enabled",
          "rag.visual.enabled",
          "rag.audio.enabled",
          "rag.video.enabled",
          "rag.image-ocr.enabled"
        }) {
      assertInvalid(local().withProperty(key, "false"));
    }
    assertInvalid(local().withProperty("server.address", "0.0.0.0"));
    assertInvalid(local().withProperty("rag.environment", "production"));
    assertInvalid(
        local().withProperty("rag.query-attachments.ranking.base-url", "private invalid endpoint"));
  }

  @Test
  void explicitCapabilityDoesNotChangeExistingMigrationStageOrOldConstructorDefaults() {
    var old =
        new RuntimeService(
            "development_headers", "org", true, true, true, false, true, true, true, true, false);
    var enabled =
        new RuntimeService(
            "development_headers",
            "org",
            true,
            true,
            true,
            false,
            true,
            true,
            true,
            true,
            false,
            true);
    assertFalse(old.capabilities().capabilities().contains("query_attachments"));
    assertTrue(enabled.capabilities().capabilities().contains("query_attachments"));
    assertEquals(old.capabilities().migrationStage(), enabled.capabilities().migrationStage());
    assertThrows(IllegalArgumentException.class, () -> new QueryAttachmentSettings(true, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> new QueryAttachmentSettings(true, 1000, 9));
    assertEquals(2, new QueryAttachmentSettings(true, 1000, 2).maxConcurrent());
  }

  private static void assertInvalid(MockEnvironment environment) {
    var failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> new QueryAttachmentConfiguration().queryRankingModels(environment));
    assertEquals("Invalid local query attachment configuration", failure.getMessage());
    assertNull(failure.getCause());
  }

  @Test
  void transportLimitsRemainExplicitAtBothEndsEvenWhenTheFeatureIsDisabled() {
    assertThrows(
        IllegalArgumentException.class, () -> new QueryAttachmentSettings(false, 60001, 1));
    assertThrows(IllegalArgumentException.class, () -> new QueryAttachmentSettings(false, 1000, 0));
    assertEquals(10, new QueryAttachmentSettings(true, 10, 1).receiveTimeoutMs());
    assertEquals(60000, new QueryAttachmentSettings(false, 60000, 8).receiveTimeoutMs());
    assertEquals(8, new QueryAttachmentSettings(false, 60000, 8).maxConcurrent());
  }

  @Test
  void literalIpv6DevelopmentBindingAndOcrConstructionNeedNoModelOrNativeExecution() {
    var configuration = new QueryAttachmentConfiguration();
    var environment =
        local()
            .withProperty("server.address", "::1")
            .withProperty("rag.environment", "development")
            .withProperty(
                "rag.image-ocr.executable",
                java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString())
            .withProperty("rag.image-ocr.revision", "synthetic-construction-v1");
    try (var ranking = configuration.queryRankingModels(environment);
        var ocr = configuration.queryImageOcr(environment)) {
      assertFalse(ranking.revision().isBlank());
      assertTrue(ocr.revision().contains("synthetic-construction-v1"));
    }
    var failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                configuration.queryImageOcr(
                    environment.withProperty("rag.image-ocr.executable", "relative-private-path")));
    assertEquals("Invalid local query attachment configuration", failure.getMessage());
    assertNull(failure.getCause());
  }

  @Test
  void runtimeCannotAdvertiseAttachmentAnswersWithoutEveryRequiredMediaCapability() {
    for (int missing = 0; missing < 5; missing++) {
      var runtime =
          new RuntimeService(
              "development_headers",
              "org",
              true,
              true,
              missing != 0,
              false,
              missing != 1,
              missing != 2,
              missing != 3,
              missing != 4,
              false,
              true);
      assertFalse(runtime.capabilities().capabilities().contains("query_attachments"));
    }
  }

  private static MockEnvironment local() {
    return new MockEnvironment()
        .withProperty("server.address", "127.0.0.1")
        .withProperty("rag.environment", "test")
        .withProperty("rag.answers.enabled", "true")
        .withProperty("rag.visual.enabled", "true")
        .withProperty("rag.audio.enabled", "true")
        .withProperty("rag.video.enabled", "true")
        .withProperty("rag.image-ocr.enabled", "true")
        .withProperty("rag.query-attachments.ranking.base-url", "http://127.0.0.1:1/v1")
        .withProperty("rag.query-attachments.ranking.model", "synthetic-ranking")
        .withProperty("rag.query-attachments.ranking.api-key", "synthetic-ranking-credential")
        .withProperty("rag.query-attachments.ranking.allow-loopback-http", "true");
  }
}
