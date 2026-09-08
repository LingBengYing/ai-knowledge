package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Current-authorized source excerpt reconstructed from the immutable answer trace. */
public record SourceResult(@JsonProperty("answer_id") String answerId, CitationResult citation) {
  @Override
  public String toString() {
    return "SourceResult[redacted]";
  }
}
