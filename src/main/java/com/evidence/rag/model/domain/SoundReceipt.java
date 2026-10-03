package com.evidence.rag.model.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Worker result whose complete float32 entries are independently verified by its parent. */
public record SoundReceipt(List<Entry> entries, VerifiedRevision verified) {
  public SoundReceipt {
    if (entries == null
        || entries.isEmpty()
        || entries.size() > 600
        || verified == null
        || verified.segmentCount() != entries.size()) {
      throw ModelValues.invalid();
    }
    var spans = new HashSet<String>();
    var physical = new HashSet<String>();
    for (var entry : entries) {
      if (entry == null || !spans.add(entry.spanId()) || !physical.add(entry.physicalSegmentId())) {
        throw ModelValues.invalid();
      }
    }
    entries = List.copyOf(entries);
  }

  public record Entry(
      String spanId,
      String physicalSegmentId,
      String recallText,
      List<Double> vector,
      String entrySha256) {
    public Entry {
      ModelValues.indexIdentity(spanId);
      ModelValues.indexIdentity(physicalSegmentId);
      SoundProfile.hash(entrySha256);
      new SoundSpan(spanId, 0, 0, 1, entrySha256, recallText, physicalSegmentId, entrySha256);
      if (vector == null || vector.size() < 2 || vector.size() > 3072) {
        throw ModelValues.invalid();
      }
      var canonical = new ArrayList<Double>();
      boolean nonzero = false;
      for (Double value : vector) {
        if (value == null || !Double.isFinite(value) || Math.abs(value) > Float.MAX_VALUE) {
          throw ModelValues.invalid();
        }
        float scalar = value.floatValue();
        nonzero |= scalar != 0;
        canonical.add(scalar == 0 ? 0.0 : (double) scalar);
      }
      if (!nonzero) {
        throw ModelValues.invalid();
      }
      vector = List.copyOf(canonical);
    }

    @Override
    public String toString() {
      return "SoundReceipt.Entry[redacted]";
    }
  }

  @Override
  public String toString() {
    return "SoundReceipt[redacted]";
  }
}
