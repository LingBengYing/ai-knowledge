package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * The open batch after an append: how many entries are registered, whether it is still open, and
 * the maintained state of each entry the request named ({@code awaiting_upload}, {@code duplicate}
 * or, for a replayed entry that was uploaded meanwhile, its processing state).
 */
public record DocumentBatchAppendResult(
    @JsonProperty("batch_id") String batchId,
    int registered,
    @JsonProperty("expected_total") int expectedTotal,
    boolean open,
    List<Item> items) {
  public DocumentBatchAppendResult {
    items = List.copyOf(items);
  }

  public record Item(
      int ordinal,
      String state,
      @JsonProperty("duplicate_of_document_id") String duplicateOfDocumentId,
      @JsonProperty("duplicate_of_ordinal") Integer duplicateOfOrdinal) {}
}
