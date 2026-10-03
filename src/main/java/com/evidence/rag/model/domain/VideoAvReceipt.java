package com.evidence.rag.model.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public record VideoAvReceipt(
    List<Entry> entries, VideoAvRouteReceipt visualReceipt, VideoAvRouteReceipt audioReceipt) {
  public VideoAvReceipt {
    if (entries == null
        || entries.isEmpty()
        || entries.size() > 2402
        || visualReceipt == null
        || audioReceipt == null
        || visualReceipt.route() != VideoAvRoute.VISUAL
        || audioReceipt.route() != VideoAvRoute.AUDIO
        || visualReceipt.count() + audioReceipt.count() != entries.size()) {
      throw ModelValues.invalid();
    }
    var ids = new HashSet<String>();
    var windows = new HashSet<String>();
    for (var entry : entries) {
      if (entry == null
          || !ids.add(entry.physicalSegmentId())
          || !windows.add(entry.route().name() + ":" + entry.windowId())) {
        throw ModelValues.invalid();
      }
    }
    entries = List.copyOf(entries);
  }

  public record Entry(
      String windowId,
      VideoAvRoute route,
      String physicalSegmentId,
      List<Double> vector,
      String entrySha256) {
    public Entry {
      ModelValues.indexIdentity(windowId);
      ModelValues.indexIdentity(physicalSegmentId);
      VideoAvProfile.hash(entrySha256);
      if (route == null || vector == null || vector.size() < 2 || vector.size() > 3072) {
        throw ModelValues.invalid();
      }
      var values = new ArrayList<Double>();
      boolean nonzero = false;
      for (Double v : vector) {
        if (v == null || !Double.isFinite(v) || Math.abs(v) > Float.MAX_VALUE) {
          throw ModelValues.invalid();
        }
        float f = v.floatValue();
        nonzero |= f != 0;
        values.add(f == 0 ? 0.0 : (double) f);
      }
      if (!nonzero) {
        throw ModelValues.invalid();
      }
      vector = List.copyOf(values);
    }

    @Override
    public String toString() {
      return "VideoAvReceipt.Entry[redacted]";
    }
  }

  @Override
  public String toString() {
    return "VideoAvReceipt[redacted]";
  }
}
