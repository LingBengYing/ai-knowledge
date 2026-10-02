package com.evidence.rag.config;

import com.evidence.rag.model.domain.ImageOcrOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.core.env.Environment;

/** Centralized opt-in settings for the local image compiler. */
public final class ImageOcrConfiguration {
  private ImageOcrConfiguration() {}

  public static ImageOcrOptions options(Environment environment) {
    if (!environment.getProperty("rag.image-ocr.enabled", Boolean.class, false)) {
      return null;
    }
    String address = environment.getProperty("server.address");
    String mode = environment.getProperty("rag.environment", "development");
    if (!("127.0.0.1".equals(address) || "::1".equals(address))
        || !("development".equals(mode) || "test".equals(mode))) {
      throw new IllegalArgumentException("Image OCR requires a local development or test runtime");
    }
    String executable = environment.getProperty("rag.image-ocr.executable", "");
    Path path = Path.of(executable);
    if (!path.isAbsolute() || !Files.isRegularFile(path) || !Files.isExecutable(path)) {
      throw new IllegalArgumentException("An explicit local OCR executable is required");
    }
    return new ImageOcrOptions(
        path,
        environment.getProperty("rag.image-ocr.language", "eng"),
        environment.getProperty("rag.image-ocr.revision", ""));
  }
}
