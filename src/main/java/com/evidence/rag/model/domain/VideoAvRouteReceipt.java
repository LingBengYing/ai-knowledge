package com.evidence.rag.model.domain;

public record VideoAvRouteReceipt(
    VideoAvRoute route, int count, String manifestSha256, VerifiedRevision verified) {
  public VideoAvRouteReceipt {
    VideoAvProfile.hash(manifestSha256);
    if (route == null
        || count < 0
        || count > 1201
        || (count == 0
            ? verified != null
            : verified == null
                || verified.segmentCount() != count
                || !verified.manifestSha256().equals(manifestSha256))) {
      throw ModelValues.invalid();
    }
  }
}
