package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/** The workspace's import batches, newest first. */
public record DocumentBatchList(int offset, int limit, int total, List<Item> items) {
  public DocumentBatchList {
    items = List.copyOf(items);
  }

  public record Item(
      @JsonProperty("batch_id") String batchId,
      String state,
      int total,
      Map<String, Long> counts,
      @JsonProperty("halted_reason") String haltedReason,
      @JsonProperty("created_at") String createdAt,
      @JsonProperty("updated_at") String updatedAt,
      boolean open,
      @JsonProperty("expected_total") Integer expectedTotal) {}
}
