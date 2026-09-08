package com.evidence.rag.model.domain;

import java.nio.charset.StandardCharsets;

/** Authority-owned source body and locator, never the vector provider's supplied text. */
public record PublishedEvidence(
    PublicationVersion publication,
    String physicalSegmentId,
    String entrySha256,
    IndexSegment segment,
    TextPage page,
    String pageSha256,
    String filename) {
  public PublishedEvidence {
    ModelValues.identifier(physicalSegmentId, 128);
    ModelValues.identifier(filename, 255);
    if (publication == null
        || segment == null
        || page == null
        || page.text() == null
        || page.number() != segment.page()
        || entrySha256 == null
        || !entrySha256.matches("[a-f0-9]{64}")
        || !ModelValues.sha256(page.text().getBytes(StandardCharsets.UTF_8)).equals(pageSha256)
        || segment.end() > page.text().codePointCount(0, page.text().length())) {
      throw ModelValues.invalid();
    }
    int startIndex = page.text().offsetByCodePoints(0, segment.start());
    int endIndex = page.text().offsetByCodePoints(0, segment.end());
    if (!page.text().substring(startIndex, endIndex).equals(segment.text())) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "PublishedEvidence[redacted]";
  }
}
