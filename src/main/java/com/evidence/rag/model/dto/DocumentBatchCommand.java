package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record DocumentBatchCommand(
    @JsonProperty("request_id") String requestId,
    List<File> files,
    @JsonProperty("embedding_request_limit") Integer embeddingRequestLimit,
    @JsonProperty("include_duplicates") Boolean includeDuplicates) {
  public DocumentBatchCommand {
    files = files == null ? null : List.copyOf(files);
  }

  public DocumentBatchCommand(String requestId, List<File> files, Integer embeddingRequestLimit) {
    this(requestId, files, embeddingRequestLimit, null);
  }

  public record File(
      String filename,
      @JsonProperty("size_bytes") long sizeBytes,
      @JsonProperty("source_sha256") String sourceSha256) {}
}
