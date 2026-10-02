package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ImageOcrOptionsTest {
  @Test
  void rejectsImplicitPathsAndUnsafeLanguageOrRevisionConfiguration() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ImageOcrOptions(Path.of("tesseract"), "eng", "5.5.3"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ImageOcrOptions(Path.of("/synthetic/tesseract"), "../eng", "5.5.3"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ImageOcrOptions(Path.of("/synthetic/tesseract"), "eng", "latest"));
    var options = new ImageOcrOptions(Path.of("/synthetic/tesseract"), "chi_sim+eng", "5.5.3");
    assertEquals("java-image-ocr-v2-tsv:5.5.3:chi_sim+eng", options.parserRevision());
    assertEquals("ImageOcrOptions[redacted]", options.toString());
  }
}
