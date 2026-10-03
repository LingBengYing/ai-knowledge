package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PdfOcrOptionsTest {
  @Test
  void immutableProfileBindsExecutableLanguageAndEngineWithoutReadingFiles() {
    var options = options("/synthetic/pdf-ocr", "eng", "fixture-v1");
    assertTrue(options.parserRevision().matches("java-pdf-ocr-v1:[0-9a-f]{64}"));
    assertEquals(
        options.parserRevision(),
        options("/synthetic/pdf-ocr", "eng", "fixture-v1").parserRevision());
    assertNotEquals(
        options.parserRevision(),
        options("/different/pdf-ocr", "eng", "fixture-v1").parserRevision());
    assertNotEquals(
        options.parserRevision(),
        options("/synthetic/pdf-ocr", "chi_sim+eng", "fixture-v1").parserRevision());
    assertNotEquals(
        options.parserRevision(),
        options("/synthetic/pdf-ocr", "eng", "fixture-v2").parserRevision());
    assertNotEquals(options.ocr().parserRevision(), options.parserRevision());
    assertEquals("PdfOcrOptions[redacted]", options.toString());
  }

  @Test
  void profileCannotOmitItsExplicitOcrConfiguration() {
    assertThrows(IllegalArgumentException.class, () -> new PdfOcrOptions(null));
  }

  private PdfOcrOptions options(String executable, String language, String revision) {
    return new PdfOcrOptions(new ImageOcrOptions(Path.of(executable), language, revision));
  }
}
