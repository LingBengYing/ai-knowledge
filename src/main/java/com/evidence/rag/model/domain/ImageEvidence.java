package com.evidence.rag.model.domain;

import java.nio.charset.StandardCharsets;

/** Immutable original-image identity and recall metadata, without fabricated text coordinates. */
public record ImageEvidence(
    String id,
    String revisionId,
    int width,
    int height,
    String recallText,
    String recallSha256,
    String descriptionRevision) {
  public ImageEvidence {
    ModelValues.indexIdentity(id);
    ModelValues.identifier(revisionId, 128);
    new ImageRecall(recallText, descriptionRevision);
    if (width < 1
        || height < 1
        || (long) width * height > 12_000_000
        || recallSha256 == null
        || !recallSha256.equals(ModelValues.sha256(recallText.getBytes(StandardCharsets.UTF_8)))) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "ImageEvidence[redacted]";
  }
}
