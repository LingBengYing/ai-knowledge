package com.evidence.rag.config;

import com.evidence.rag.client.ocr.SocketPageOcr;
import com.evidence.rag.model.domain.ImageOcrOptions;
import com.evidence.rag.model.domain.PdfOcrOptions;
import com.evidence.rag.worker.parser.ProcessTextParser;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.core.env.Environment;

/** Independent opt-in configuration for the local whole-page PDF compiler. */
public final class PdfOcrConfiguration {
  static final long DEFAULT_TIMEOUT_MS = 900_000;

  private PdfOcrConfiguration() {}

  /** Whole-document PDF parse deadline; scanned pages each need a remote OCR call. */
  public static Duration timeout(Environment environment) {
    Long value = environment.getProperty("rag.pdf-ocr.timeout-ms", Long.class, DEFAULT_TIMEOUT_MS);
    if (value == null || value < 10 || value > ProcessTextParser.MAX_PDF_DEADLINE.toMillis()) {
      throw new IllegalArgumentException("Invalid PDF OCR timeout");
    }
    return Duration.ofMillis(value);
  }

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
    // An explicitly configured Tesseract executable keeps its old default backend.
    String backend =
        environment.getProperty(
            "rag.pdf-ocr.backend",
            environment.getProperty("rag.pdf-ocr.executable", "").isEmpty()
                ? "siliconflow"
                : "tesseract");
    if ("siliconflow".equals(backend)) {
      // Same provider address as the managed model settings; the key is read from those settings.
      return PdfOcrOptions.remote(
          URI.create(
              environment.getProperty(
                  "rag.model-configuration.provider-base-url", "https://api.siliconflow.cn/v1")));
    }
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
