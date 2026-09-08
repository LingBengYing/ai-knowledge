package com.evidence.rag.model.domain;

import java.util.List;

/** Immutable parser result; recipients still validate the complete content and source locators. */
public record ParsedText(List<TextPage> pages, List<TextSegment> segments) {
  public ParsedText {
    pages = List.copyOf(pages);
    segments = List.copyOf(segments);
  }

  @Override
  public String toString() {
    return "ParsedText[redacted]";
  }
}
