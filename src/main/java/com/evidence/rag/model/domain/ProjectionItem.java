package com.evidence.rag.model.domain;

import java.nio.charset.StandardCharsets;

/** Immutable recall content; source locators remain in their authority-owned evidence type. */
public record ProjectionItem(
    String evidenceId, int ordinal, String recallText, String recallTextSha256) {
  public static final int MAX_CODE_POINTS = 4096;
  public static final int MAX_TEXT_BYTES = 16384;

  public ProjectionItem {
    ModelValues.identifier(evidenceId, 128);
    if (ordinal < 0
        || ordinal >= 4096
        || recallText == null
        || recallText.isBlank()
        || recallText.length() > MAX_TEXT_BYTES
        || recallText.codePointCount(0, recallText.length()) > MAX_CODE_POINTS
        || recallText.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES
        || recallText.codePoints().anyMatch(c -> c == 0 || (c >= 0xD800 && c <= 0xDFFF))
        || !ModelValues.sha256(recallText.getBytes(StandardCharsets.UTF_8))
            .equals(recallTextSha256)) {
      throw ModelValues.invalid();
    }
  }

  public static ProjectionItem fromText(IndexSegment segment) {
    if (segment == null) {
      throw ModelValues.invalid();
    }
    return new ProjectionItem(
        segment.segmentId(), segment.ordinal(), segment.text(), segment.textSha256());
  }

  @Override
  public String toString() {
    return "ProjectionItem[redacted]";
  }
}
