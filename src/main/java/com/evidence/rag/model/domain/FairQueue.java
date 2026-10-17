package com.evidence.rag.model.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;

/**
 * Claim order for one durable queue: tasks outside any import batch (single interactive uploads)
 * first, oldest first; then the heads of the active batches in round-robin order, starting with the
 * first batch after the one served last. A claim takes the first candidate that passes its checks
 * and records that candidate's batch, so batches alternate claim by claim and a 10,000-file batch
 * never holds up an interactive upload or another batch.
 */
public final class FairQueue {
  /** A queued task; {@code batchId} is null outside an import batch. */
  public record Candidate(String taskId, String batchId) {}

  private String lastBatch;

  /**
   * @param interactive queued tasks outside batches, oldest first
   * @param batchHeads the next few queued tasks of each active batch, each batch's oldest first
   * @param limit the most candidates to return
   */
  public synchronized List<Candidate> order(
      List<String> interactive, List<Candidate> batchHeads, int limit) {
    var result = new ArrayList<Candidate>();
    for (var task : interactive) {
      if (result.size() >= limit) return List.copyOf(result);
      result.add(new Candidate(task, null));
    }
    var byBatch = new TreeMap<String, List<Candidate>>();
    for (var head : batchHeads) {
      byBatch.computeIfAbsent(head.batchId(), ignored -> new ArrayList<>()).add(head);
    }
    // Rotate so the batch after the last one served comes first, then interleave.
    var rotated = new LinkedHashMap<String, List<Candidate>>();
    if (lastBatch != null) byBatch.tailMap(lastBatch, false).forEach(rotated::put);
    byBatch.forEach(rotated::putIfAbsent);
    var queues = new ArrayList<>(rotated.values());
    for (int round = 0; result.size() < limit; round++) {
      boolean any = false;
      for (var queue : queues) {
        if (round < queue.size() && result.size() < limit) {
          result.add(queue.get(round));
          any = true;
        }
      }
      if (!any) break;
    }
    return List.copyOf(result);
  }

  /** Records the batch whose task was just claimed; interactive claims do not move the cursor. */
  public synchronized void served(Candidate candidate) {
    if (candidate.batchId() != null) {
      lastBatch = candidate.batchId();
    }
  }
}
