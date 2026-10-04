package com.evidence.rag.model.domain;

import java.util.HashMap;
import java.util.List;

/** Exactly every origin in the sealed plan has an independently checked complete manifest. */
public record VerifiedReindexVectors(
    ReindexVectorPlan plan, List<ReindexVectorVerification> receipts) {
  public VerifiedReindexVectors {
    if (plan == null
        || receipts == null
        || receipts.size() != plan.images().size() + plan.audios().size()) {
      throw ModelValues.invalid();
    }
    var expected = new HashMap<String, Object>();
    for (var binding : plan.images()) {
      var origin = binding.origin();
      expected.put("image:" + origin.id(), origin);
    }
    for (var binding : plan.audios()) {
      var origin = binding.origin();
      expected.put("audio:" + origin.id(), origin);
    }
    for (var receipt : receipts) {
      if (receipt == null) {
        throw ModelValues.invalid();
      }
      var origin = expected.remove(receipt.route() + ":" + receipt.originReceiptId());
      IndexTarget target;
      String manifest;
      int count;
      if (origin instanceof ImageVectorPublication image) {
        target = image.target();
        manifest = image.manifestSha256();
        count = 1;
      } else if (origin instanceof AudioVectorPublication audio) {
        target = audio.target();
        manifest = audio.manifestSha256();
        count = audio.entries().size();
      } else {
        throw ModelValues.invalid();
      }
      if (!receipt.target().equals(target)
          || !receipt.verified().manifestSha256().equals(manifest)
          || receipt.verified().segmentCount() != count) {
        throw ModelValues.invalid();
      }
    }
    if (!expected.isEmpty()) {
      throw ModelValues.invalid();
    }
    receipts = List.copyOf(receipts);
  }

  @Override
  public String toString() {
    return "VerifiedReindexVectors[redacted]";
  }
}
