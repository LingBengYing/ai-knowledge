package com.evidence.rag.model.domain;

/** Text evidence within one normalized page, with a half-open code point range. */
public record TextSegment(int ordinal, int page, int start, int end, String text) {
  @Override
  public String toString() {
    return "TextSegment[redacted]";
  }
}
