package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class ImageOcrConfigurationTest {
  @TempDir Path directory;

  @Test
  void defaultsOffAndLoadsOnlyExplicitLocalRuntime() throws Exception {
    assertNull(ImageOcrConfiguration.options(new MockEnvironment()));
    Path executable = directory.resolve("ocr-fixture");
    Files.writeString(executable, "synthetic executable, not launched");
    assertTrue(executable.toFile().setExecutable(true));
    var environment = enabled(executable);
    var options = ImageOcrConfiguration.options(environment);
    assertNotNull(options);
    assertEquals(executable, options.executable());
    assertEquals("eng", options.language());
    assertTrue(options.parserRevision().contains("fixture-v1"));
    assertFalse(options.toString().contains(executable.toString()));
  }

  @Test
  void enabledSettingsRejectMissingOrPublicRuntime() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ImageOcrConfiguration.options(enabled(directory.resolve("missing"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> ImageOcrConfiguration.options(enabled(Path.of("relative-ocr"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ImageOcrConfiguration.options(
                enabled(directory).withProperty("server.address", "0.0.0.0")));
  }

  private MockEnvironment enabled(Path executable) {
    return new MockEnvironment()
        .withProperty("rag.image-ocr.enabled", "true")
        .withProperty("server.address", "127.0.0.1")
        .withProperty("rag.environment", "test")
        .withProperty("rag.image-ocr.executable", executable.toString())
        .withProperty("rag.image-ocr.language", "eng")
        .withProperty("rag.image-ocr.revision", "fixture-v1");
  }
}
