package com.evidence.rag.model.domain;

/** Normalized source page; offsets in related segments count Unicode code points. */
public record TextPage(int number, String text) {
  @Override
  public String toString() {
    return "TextPage[redacted]";
  }
}
