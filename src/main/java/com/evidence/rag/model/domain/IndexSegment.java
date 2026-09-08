package com.evidence.rag.model.domain;

import static com.evidence.rag.model.domain.ModelValues.identifier;
import static com.evidence.rag.model.domain.ModelValues.invalid;
import static com.evidence.rag.model.domain.ModelValues.sha256;

import java.nio.charset.StandardCharsets;

public record IndexSegment(
    String segmentId, int ordinal, int page, int start, int end, String text, String textSha256) {
  public IndexSegment {
    identifier(segmentId, 128);
    if (ordinal < 0
        || ordinal >= 4096
        || page < 1
        || page > 500
        || start < 0
        || end <= start
        || end - start > 1200
        || text == null
        || text.codePointCount(0, text.length()) != end - start
        || text.codePoints().anyMatch(c -> c == 0 || (c >= 0xD800 && c <= 0xDFFF))
        || !sha256(text.getBytes(StandardCharsets.UTF_8)).equals(textSha256)) {
      throw invalid();
    }
  }

  @Override
  public String toString() {
    return "IndexSegment[redacted]";
  }
}
