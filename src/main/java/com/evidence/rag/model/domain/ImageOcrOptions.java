package com.evidence.rag.model.domain;

import java.nio.file.Path;

/** Explicit OCR runtime identity; never reads environment variables or launches a process. */
public record ImageOcrOptions(Path executable, String language, String revision) {
  public ImageOcrOptions {
    if (executable == null
        || !executable.isAbsolute()
        || !executable.normalize().equals(executable)
        || executable.toString().codePoints().anyMatch(Character::isISOControl)
        || language == null
        || language.length() > 40
        || !language.matches("[A-Za-z0-9_]+(?:\\+[A-Za-z0-9_]+)*")
        || revision == null
        || !revision.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}")
        || revision.equalsIgnoreCase("latest")
        || revision.equalsIgnoreCase("default")
        || revision.equalsIgnoreCase("unknown")) {
      throw new IllegalArgumentException("Invalid image OCR configuration");
    }
  }

  public String parserRevision() {
    return "java-image-ocr-v2-tsv:" + revision + ":" + language;
  }

  @Override
  public String toString() {
    return "ImageOcrOptions[redacted]";
  }
}
