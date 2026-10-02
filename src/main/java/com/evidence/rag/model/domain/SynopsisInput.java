package com.evidence.rag.model.domain;

import java.util.HashSet;
import java.util.List;

/**
 * Bounded complete single-publication input. The authority, not this value, authenticates origin.
 */
public record SynopsisInput(PublicationVersion publication, List<SynopsisEvidence> evidence) {
  public static final int MAX_EVIDENCE = 64;
  public static final int MAX_TEXT_CODE_POINTS = 64000;
  public static final int MAX_IMAGES = 8;
  public static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;

  public SynopsisInput {
    if (publication == null
        || evidence == null
        || evidence.isEmpty()
        || evidence.size() > MAX_EVIDENCE
        || evidence.size() != publication.segmentCount()) {
      throw ModelValues.invalid();
    }
    var ids = new HashSet<String>();
    long codePoints = 0;
    long imageBytes = 0;
    int images = 0;
    for (var item : evidence) {
      if (item == null || !ids.add(item.id())) {
        throw ModelValues.invalid();
      }
      switch (item.content()) {
        case SynopsisEvidence.Text text ->
            codePoints += text.text().codePointCount(0, text.text().length());
        case SynopsisEvidence.Image image -> {
          images++;
          imageBytes += image.image().content().length;
        }
      }
    }
    if (codePoints > MAX_TEXT_CODE_POINTS || images > MAX_IMAGES || imageBytes > MAX_IMAGE_BYTES) {
      throw ModelValues.invalid();
    }
    evidence = List.copyOf(evidence);
  }

  public String fingerprint() {
    return SynopsisFingerprint.of(publication, evidence);
  }

  @Override
  public String toString() {
    return "SynopsisInput[redacted]";
  }
}
