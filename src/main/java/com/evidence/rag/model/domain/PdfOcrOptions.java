package com.evidence.rag.model.domain;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Immutable identity of the text-first PDF/OCR profile; no filesystem access or processes. */
public record PdfOcrOptions(ImageOcrOptions ocr, Path socket, String revision) {
  public static final String PIPELINE_PROFILE = "paddleocr-vl-1.5-pipeline-v1";

  public PdfOcrOptions(ImageOcrOptions ocr) {
    this(ocr, null, null);
  }

  public PdfOcrOptions {
    if (ocr == null
        ? socket == null
            || !socket.isAbsolute()
            || !socket.normalize().equals(socket)
            || socket.toString().codePoints().anyMatch(Character::isISOControl)
            || revision == null
            || !revision.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}")
            || revision.equalsIgnoreCase("latest")
            || revision.equalsIgnoreCase("default")
            || revision.equalsIgnoreCase("unknown")
        : socket != null || revision != null) {
      throw new IllegalArgumentException("Explicit PDF OCR options are required");
    }
  }

  public String parserRevision() {
    if (socket != null) {
      String profile =
          "java-pdf-ocr-v1\0pdfbox-rgb-144dpi\0text-first-mixed-image-fallback-v2\0"
              + "java-text-parser-v2-monotonic-codepoints\0unix-page-ocr-v1\0"
              + PIPELINE_PROFILE
              + "\0"
              + revision
              + "\0"
              + socket;
      return "java-pdf-ocr-v1:" + ModelValues.sha256(profile.getBytes(StandardCharsets.UTF_8));
    }
    String profile =
        "java-pdf-ocr-v1\0pdfbox-rgb-144dpi\0text-first-mixed-image-fallback-v2\0"
            + "java-text-parser-v2-monotonic-codepoints\0"
            + ocr.parserRevision()
            + "\0"
            + ocr.executable();
    return "java-pdf-ocr-v1:" + ModelValues.sha256(profile.getBytes(StandardCharsets.UTF_8));
  }

  public boolean pipeline() {
    return socket != null;
  }

  @Override
  public String toString() {
    return "PdfOcrOptions[redacted]";
  }
}
