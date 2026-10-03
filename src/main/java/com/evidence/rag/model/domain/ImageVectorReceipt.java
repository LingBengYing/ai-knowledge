package com.evidence.rag.model.domain;

import java.util.List;

/** The unique verified entry, including float32 values needed for independent digest checking. */
public record ImageVectorReceipt(
    String physicalSegmentId, List<Double> vector, String entrySha256, VerifiedRevision verified) {
  public ImageVectorReceipt {
    ModelValues.identifier(physicalSegmentId, 128);
    if (vector == null
        || vector.size() < 2
        || vector.size() > 8192
        || entrySha256 == null
        || !entrySha256.matches("[a-f0-9]{64}")
        || verified == null
        || verified.segmentCount() != 1) {
      throw ModelValues.invalid();
    }
    boolean nonzero = false;
    for (var value : vector) {
      if (value == null || !Double.isFinite(value) || !Float.isFinite(value.floatValue())) {
        throw ModelValues.invalid();
      }
      nonzero |= value.floatValue() != 0;
    }
    if (!nonzero) {
      throw ModelValues.invalid();
    }
    vector = List.copyOf(vector);
  }

  @Override
  public String toString() {
    return "ImageVectorReceipt[redacted]";
  }
}
