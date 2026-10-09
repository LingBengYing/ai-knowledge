package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PdfOcrOptionsTest {
  @Test
  void pipelineProfileIsIndependentPinnedAndKeepsOldFamilyRecognition() {
    var pipeline = new PdfOcrOptions(null, Path.of("/synthetic/ocr.sock"), "fixture-v1");
    assertTrue(pipeline.pipeline());
    assertTrue(pipeline.parserRevision().matches("java-pdf-ocr-v1:[0-9a-f]{64}"));
    assertNotEquals(
        pipeline.parserRevision(),
        options("/synthetic/pdf-ocr", "eng", "fixture-v1").parserRevision());
    assertNotEquals(
        pipeline.parserRevision(),
        new PdfOcrOptions(null, Path.of("/synthetic/other.sock"), "fixture-v1").parserRevision());
    assertNotEquals(
        pipeline.parserRevision(),
        new PdfOcrOptions(null, Path.of("/synthetic/ocr.sock"), "fixture-v2").parserRevision());
    for (String revision : new String[] {"", "latest", "default", "unknown", "bad/revision"}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new PdfOcrOptions(null, Path.of("/synthetic/ocr.sock"), revision));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> new PdfOcrOptions(null, Path.of("relative.sock"), "fixture-v1"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PdfOcrOptions(null, Path.of("/synthetic/../ocr.sock"), "fixture-v1"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PdfOcrOptions(
                new ImageOcrOptions(Path.of("/synthetic/exe"), "eng", "v1"),
                Path.of("/synthetic/ocr.sock"),
                "v1"));
  }

  @Test
  void textFirstProfileCannotReuseThePreviousFullPageAlgorithmIdentity() {
    var options = options("/synthetic/pdf-ocr", "eng", "fixture-v1");
    String previous =
        "java-pdf-ocr-v1\0pdfbox-rgb-144dpi\0full-page-tsv-compile-v1\0"
            + "java-text-parser-v2-monotonic-codepoints\0"
            + options.ocr().parserRevision()
            + "\0"
            + options.ocr().executable();
    assertNotEquals(
        "java-pdf-ocr-v1:" + ModelValues.sha256(previous.getBytes(StandardCharsets.UTF_8)),
        options.parserRevision());
  }

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
