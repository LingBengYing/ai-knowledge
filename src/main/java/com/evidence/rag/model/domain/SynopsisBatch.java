package com.evidence.rag.model.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Explicit original-evidence sub-batch retaining the real complete publication and fingerprint. */
public record SynopsisBatch(
    PublicationVersion publication,
    String inputFingerprint,
    int fromOrdinal,
    List<SynopsisEvidence> evidence) {
  public static final int MAX_EVIDENCE = 64;
  public static final int MAX_TEXT_CODE_POINTS = 64000;
  public static final int MAX_IMAGES = 8;
  public static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

  public SynopsisBatch {
    if (publication == null
        || inputFingerprint == null
        || !inputFingerprint.matches("[a-f0-9]{64}")
        || fromOrdinal < 0
        || evidence == null
        || evidence.isEmpty()
        || evidence.size() > MAX_EVIDENCE
        || (long) fromOrdinal + evidence.size() > publication.segmentCount()) {
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
    evidence = List.copyOf(evidence);
  }

  public int endOrdinal() {
    return fromOrdinal + evidence.size();
  }

  public static List<SynopsisBatch> partition(SynopsisFileInput input) {
    if (input == null) {
      throw ModelValues.invalid();
    }
    var batches = new ArrayList<SynopsisBatch>();
    int from = 0;
    long points = 0;
    long bytes = 0;
    int images = 0;
    for (int index = 0; index < input.evidence().size(); index++) {
      var item = input.evidence().get(index);
      long nextPoints = 0;
      long nextBytes = 0;
      int nextImages = 0;
      switch (item.content()) {
        case SynopsisEvidence.Text text ->
            nextPoints = text.text().codePointCount(0, text.text().length());
        case SynopsisEvidence.Image image -> {
          nextImages = 1;
          nextBytes = image.image().content().length;
        }
      }
      if (nextPoints > MAX_TEXT_CODE_POINTS || nextBytes > MAX_IMAGE_BYTES) {
        throw ModelValues.invalid();
      }
      if (index - from >= MAX_EVIDENCE
          || points + nextPoints > MAX_TEXT_CODE_POINTS
          || images + nextImages > MAX_IMAGES
          || bytes + nextBytes > MAX_IMAGE_BYTES) {
        batches.add(
            new SynopsisBatch(
                input.publication(),
                input.fingerprint(),
                from,
                input.evidence().subList(from, index)));
        from = index;
        points = 0;
        bytes = 0;
        images = 0;
      }
      points += nextPoints;
      bytes += nextBytes;
      images += nextImages;
    }
    batches.add(
        new SynopsisBatch(
            input.publication(),
            input.fingerprint(),
            from,
            input.evidence().subList(from, input.evidence().size())));
    return List.copyOf(batches);
  }

  @Override
  public String toString() {
    return "SynopsisBatch[redacted]";
  }
}
