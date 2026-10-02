package com.evidence.rag.model.domain;

/** Machine description for retrieval only, never an OCR transcript or answer evidence. */
public record ImageRecall(String recallText, String modelRevision) {
  public ImageRecall {
    if (recallText == null
        || recallText.isBlank()
        || recallText.codePointCount(0, recallText.length()) > 4096
        || recallText.codePoints().anyMatch(point -> point >= 0xD800 && point <= 0xDFFF)) {
      throw ModelValues.invalid();
    }
    ModelValues.identifier(modelRevision, 128);
  }

  @Override
  public String toString() {
    return "ImageRecall[redacted]";
  }
}
