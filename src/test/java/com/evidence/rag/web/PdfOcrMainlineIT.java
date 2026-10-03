package com.evidence.rag.web;

import java.nio.file.Files;
import java.nio.file.Path;

/** Explicit real native OCR; never implicitly selected by the default test suite. */
class PdfOcrMainlineIT extends PdfOcrMainlineHttpTest {
  @Override
  protected Path ocrExecutable() {
    String value = System.getenv("RAG_PDF_OCR_IT_EXECUTABLE");
    if (value == null || !Path.of(value).isAbsolute() || !Files.isExecutable(Path.of(value))) {
      throw new IllegalArgumentException("Explicit native PDF OCR executable required");
    }
    return Path.of(value);
  }

  @Override
  protected String ocrRevision() {
    String value = System.getenv("RAG_PDF_OCR_IT_REVISION");
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Explicit native PDF OCR revision required");
    }
    return value;
  }
}
