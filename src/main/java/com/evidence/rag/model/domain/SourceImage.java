package com.evidence.rag.model.domain;

import java.util.List;

/** Same-version original image, retained only for an authorized source response. */
public record SourceImage(
    String mimeType, byte[] content, int width, int height, List<ImageTextRegion> regions) {

  public SourceImage {
    if (!("image/png".equals(mimeType) || "image/jpeg".equals(mimeType))
        || content == null
        || content.length == 0
        || content.length > 10 * 1024 * 1024
        || width < 1
        || height < 1
        || (long) width * height > 12_000_000) {
      throw ModelValues.invalid();
    }
    content = content.clone();
    regions = List.copyOf(regions);
  }

  @Override
  public byte[] content() {
    return content.clone();
  }

  @Override
  public String toString() {
    return "SourceImage[redacted]";
  }
}
