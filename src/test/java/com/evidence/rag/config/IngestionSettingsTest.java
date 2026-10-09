package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.support.AuthorityTestContext;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class IngestionSettingsTest {
  @TempDir Path directory;

  @Test
  void boundedFiveMinuteParsingCanBeConfiguredForMultiPageScans() {
    assertTrue(new IngestionSettings(true, 300000, 30000).enabled());
  }

  @Test
  void limitsAreBoundedEvenWhenDisabled() {
    for (int invalid : new int[] {0, 9, 300001, Integer.MAX_VALUE}) {
      assertThrows(
          IllegalArgumentException.class, () -> new IngestionSettings(false, invalid, 30000));
    }
    for (int invalid : new int[] {0, 9, 30001, Integer.MAX_VALUE}) {
      assertThrows(
          IllegalArgumentException.class, () -> new IngestionSettings(false, 30000, invalid));
    }
    assertTrue(new IngestionSettings(true, 10, 10).enabled());
    assertFalse(new IngestionSettings(false, 60000, 30000).enabled());
  }

  @Test
  void optInCannotExposeNonSandboxedParserOnPublicBinding() {
    var properties =
        new RagProperties(
            "test",
            "org-main",
            "development_headers",
            "",
            "evidence-rag",
            "evidence-rag-web",
            directory);
    try (var authority = new AuthorityTestContext(directory)) {
      var configuration = new IngestionConfiguration();
      for (String bind : new String[] {"0.0.0.0", "localhost", "[::]"}) {
        assertThrows(
            IllegalArgumentException.class,
            () ->
                configuration.ingestionTaskProcessor(
                    authority.ingestion(),
                    properties,
                    new IngestionSettings(true, 30000, 30000),
                    new MockEnvironment().withProperty("server.address", bind),
                    new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                        .getBeanProvider(com.evidence.rag.client.model.VisionModels.class),
                    new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                        .getBeanProvider(com.evidence.rag.service.AudioCompilationService.class),
                    new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                        .getBeanProvider(com.evidence.rag.service.VideoCompilationService.class)));
      }
    }
  }
}
