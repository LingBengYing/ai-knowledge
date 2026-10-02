package com.evidence.rag.model.domain;

import java.util.HashSet;
import java.util.List;

/** Complete bounded immutable authority file, distinct from an explicit model-sized sub-batch. */
public final class SynopsisFileInput {
  public static final int MAX_EVIDENCE = 4096;
  public static final int MAX_TEXT_CODE_POINTS = 2_500_000;
  public static final int MAX_IMAGES = 128;
  public static final int MAX_IMAGE_BYTES = 32 * 1024 * 1024;
  private final PublicationVersion publication;
  private final List<SynopsisEvidence> evidence;
  private final String fingerprint;
  private final boolean bounded;

  public SynopsisFileInput(PublicationVersion publication, List<SynopsisEvidence> evidence) {
    if (publication == null
        || evidence == null
        || evidence.isEmpty()
        || evidence.size() > MAX_EVIDENCE
        || evidence.size() != publication.segmentCount()) {
      throw ModelValues.invalid();
    }
    var ids = new HashSet<String>();
    long points = 0;
    long bytes = 0;
    int images = 0;
    for (var item : evidence) {
      if (item == null || !ids.add(item.id())) {
        throw ModelValues.invalid();
      }
      switch (item.content()) {
        case SynopsisEvidence.Text text ->
            points += text.text().codePointCount(0, text.text().length());
        case SynopsisEvidence.Image image -> {
          images++;
          bytes += image.image().content().length;
        }
      }
    }
    if (points > MAX_TEXT_CODE_POINTS || images > MAX_IMAGES || bytes > MAX_IMAGE_BYTES) {
      throw ModelValues.invalid();
    }
    this.publication = publication;
    this.evidence = List.copyOf(evidence);
    this.bounded =
        evidence.size() <= SynopsisInput.MAX_EVIDENCE
            && points <= SynopsisInput.MAX_TEXT_CODE_POINTS
            && images <= SynopsisInput.MAX_IMAGES
            && bytes <= SynopsisInput.MAX_IMAGE_BYTES;
    this.fingerprint = SynopsisFingerprint.of(publication, this.evidence);
  }

  public SynopsisFileInput(SynopsisInput input) {
    this(input == null ? null : input.publication(), input == null ? null : input.evidence());
  }

  public PublicationVersion publication() {
    return publication;
  }

  public List<SynopsisEvidence> evidence() {
    return evidence;
  }

  public String fingerprint() {
    return fingerprint;
  }

  public boolean bounded() {
    return bounded;
  }

  public SynopsisInput toBounded() {
    if (!bounded) {
      throw ModelValues.invalid();
    }
    return new SynopsisInput(publication, evidence);
  }

  @Override
  public String toString() {
    return "SynopsisFileInput[redacted]";
  }
}
