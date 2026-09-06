package com.evidence.rag.ingestion;

import static org.junit.jupiter.api.Assertions.*;

import com.evidence.rag.config.RagProperties;
import com.evidence.rag.management.ManagementModule;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class IngestionSettingsTest {
  @TempDir Path directory;

  @Test
  void limitsAreBoundedEvenWhenDisabled() {
    for (int invalid : new int[] {0, 9, 60001, Integer.MAX_VALUE})
      assertThrows(
          IllegalArgumentException.class, () -> new IngestionSettings(false, invalid, 30000));
    for (int invalid : new int[] {0, 9, 30001, Integer.MAX_VALUE})
      assertThrows(
          IllegalArgumentException.class, () -> new IngestionSettings(false, 30000, invalid));
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
    try (var authority = new ManagementModule(directory)) {
      var configuration = new IngestionConfiguration();
      for (String bind : new String[] {"0.0.0.0", "localhost", "[::]"}) {
        assertThrows(
            IllegalArgumentException.class,
            () ->
                configuration.ingestionRuntime(
                    authority,
                    properties,
                    new IngestionSettings(true, 30000, 30000),
                    new MockEnvironment().withProperty("server.address", bind)));
      }
    }
  }
}
