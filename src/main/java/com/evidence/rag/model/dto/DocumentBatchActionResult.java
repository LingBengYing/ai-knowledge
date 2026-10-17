package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A bulk batch action: how many files it changed and the batch's first page afterwards. */
public record DocumentBatchActionResult(
    @JsonProperty("batch_id") String batchId,
    String action,
    int affected,
    DocumentBatchResult batch) {}
