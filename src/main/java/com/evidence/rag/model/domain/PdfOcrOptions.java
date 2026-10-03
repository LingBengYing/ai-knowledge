package com.evidence.rag.model.domain;

import java.nio.charset.StandardCharsets;

/** Immutable identity of the whole-page PDF OCR profile; no filesystem access or processes. */
public record PdfOcrOptions(ImageOcrOptions ocr) {
  public PdfOcrOptions {
    if (ocr == null) {
      throw new IllegalArgumentException("Explicit PDF OCR options are required");
    }
  }

  public String parserRevision() {
    String profile =
        "java-pdf-ocr-v1\0pdfbox-rgb-144dpi\0full-page-tsv-compile-v1\0"
            + "java-text-parser-v2-monotonic-codepoints\0"
            + ocr.parserRevision()
            + "\0"
            + ocr.executable();
    return "java-pdf-ocr-v1:" + ModelValues.sha256(profile.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public String toString() {
    return "PdfOcrOptions[redacted]";
  }
}
