package com.evidence.rag.model.domain;

import java.util.List;
import java.util.Objects;

/** OCR transcript and original-image word locations; authority revalidates the complete result. */
public record ParsedImage(
    ParsedText text, ImageDimensions dimensions, List<ImageTextRegion> regions) {
  public ParsedImage {
    Objects.requireNonNull(text);
    Objects.requireNonNull(dimensions);
    regions = List.copyOf(regions);
  }

  @Override
  public String toString() {
    return "ParsedImage[redacted]";
  }
}
