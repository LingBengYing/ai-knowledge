package com.evidence.rag.model.domain;

import java.time.Duration;

/**
 * Text indexing deadline: {@code base + perBatch × embedding requests}, never above {@code cap}.
 * One deadline covers every embedding request, its retries, the vector writes and the read-back
 * verification of one attempt, so it grows with the document instead of failing large ones.
 */
public record IndexDeadline(Duration base, Duration perBatch, Duration cap) {
  public IndexDeadline {
    if (base == null
        || perBatch == null
        || cap == null
        || base.compareTo(Duration.ofMillis(10)) < 0
        || perBatch.isNegative()
        || cap.compareTo(base) < 0
        || cap.compareTo(Duration.ofSeconds(600)) > 0) {
      throw new IllegalArgumentException("Invalid indexing deadline");
    }
  }

  public static IndexDeadline fixed(Duration base) {
    return new IndexDeadline(base, Duration.ZERO, base);
  }

  public Duration forRequests(int requests) {
    long extra = perBatch.toMillis() * Math.max(requests, 0);
    long total = Math.min(cap.toMillis(), base.toMillis() + extra);
    return Duration.ofMillis(Math.max(total, base.toMillis()));
  }
}
