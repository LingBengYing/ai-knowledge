package com.evidence.rag.config;

import com.evidence.rag.client.ocr.SocketPageOcr;
import com.evidence.rag.model.domain.ImageOcrOptions;
import com.evidence.rag.model.domain.PdfOcrOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.core.env.Environment;

/** Independent opt-in configuration for the local whole-page PDF compiler. */
public final class PdfOcrConfiguration {
  private PdfOcrConfiguration() {}

  public static PdfOcrOptions options(Environment environment) {
    if (!environment.getProperty("rag.pdf-ocr.enabled", Boolean.class, false)) {
      return null;
    }
    String address = environment.getProperty("server.address");
    String mode = environment.getProperty("rag.environment", "development");
    if (!("127.0.0.1".equals(address) || "::1".equals(address))
        || !("development".equals(mode) || "test".equals(mode))) {
      throw new IllegalArgumentException("PDF OCR requires a local development or test runtime");
    }
    String backend = environment.getProperty("rag.pdf-ocr.backend", "tesseract");
    if ("paddle-vl".equals(backend)) {
      var options =
          new PdfOcrOptions(
              null,
              Path.of(environment.getProperty("rag.pdf-ocr.socket", "")),
              environment.getProperty("rag.pdf-ocr.revision", ""));
      if (!SocketPageOcr.isNativeSocket(options.socket())) {
        throw new IllegalArgumentException("An explicit local page OCR socket is required");
      }
      return options;
    }
    if (!"tesseract".equals(backend)) {
      throw new IllegalArgumentException("Invalid PDF OCR backend");
    }
    Path executable = Path.of(environment.getProperty("rag.pdf-ocr.executable", ""));
    if (!executable.isAbsolute()
        || !Files.isRegularFile(executable)
        || !Files.isExecutable(executable)) {
      throw new IllegalArgumentException("An explicit local PDF OCR executable is required");
    }
    return new PdfOcrOptions(
        new ImageOcrOptions(
            executable,
            environment.getProperty("rag.pdf-ocr.language", "eng"),
            environment.getProperty("rag.pdf-ocr.revision", "")));
  }
}
