package com.evidence.rag.web;

import java.nio.file.Files;
import java.nio.file.Path;

/** Explicit native OCR acceptance; model/vector servers remain declared local protocol fixtures. */
class ImageOcrMainlineIT extends ImageMainlineHttpTest {
  @Override
  protected String ocrRevision() {
    String revision = System.getenv("RAG_IMAGE_OCR_IT_REVISION");
    if (revision == null || revision.isBlank()) {
      throw new IllegalArgumentException(
          "Explicit native OCR runtime revision is required for this IT");
    }
    return revision;
  }

  @Override
  protected Path ocrExecutable() {
    String executable = System.getenv("RAG_IMAGE_OCR_IT_EXECUTABLE");
    if (executable == null
        || !Path.of(executable).isAbsolute()
        || !Files.isExecutable(Path.of(executable))) {
      throw new IllegalArgumentException("Explicit native OCR executable is required for this IT");
    }
    return Path.of(executable);
  }
}
