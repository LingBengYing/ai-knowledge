package com.evidence.rag.model.domain;

import java.util.HashSet;
import java.util.List;

/** Complete verified vector values allow the authority parent to recompute every digest. */
public record AudioVectorReceipt(List<Entry> entries, VerifiedRevision verified) {
  public AudioVectorReceipt {
    if (entries == null
        || entries.isEmpty()
        || entries.size() > 600
        || verified == null
        || verified.segmentCount() != entries.size()) {
      throw ModelValues.invalid();
    }
    var ids = new HashSet<String>();
    for (var entry : entries) {
      if (entry == null || !ids.add(entry.physicalSegmentId())) {
        throw ModelValues.invalid();
      }
    }
    entries = List.copyOf(entries);
  }

  public record Entry(String physicalSegmentId, List<Double> vector, String entrySha256) {
    public Entry {
      ModelValues.identifier(physicalSegmentId, 128);
      if (vector == null
          || vector.size() < 2
          || vector.size() > 3072
          || entrySha256 == null
          || !entrySha256.matches("[a-f0-9]{64}")) {
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
      return "AudioVectorReceipt.Entry[redacted]";
    }
  }

  @Override
  public String toString() {
    return "AudioVectorReceipt[redacted]";
  }
}
