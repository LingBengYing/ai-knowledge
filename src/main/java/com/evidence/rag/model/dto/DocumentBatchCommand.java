package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record DocumentBatchCommand(
    @JsonProperty("request_id") String requestId,
    List<File> files,
    @JsonProperty("embedding_request_limit") Integer embeddingRequestLimit,
    @JsonProperty("include_duplicates") Boolean includeDuplicates,
    Boolean open,
    @JsonProperty("expected_total") Integer expectedTotal) {
  public DocumentBatchCommand {
    files = files == null ? null : List.copyOf(files);
  }

  public DocumentBatchCommand(String requestId, List<File> files, Integer embeddingRequestLimit) {
    this(requestId, files, embeddingRequestLimit, null);
  }

  public DocumentBatchCommand(
      String requestId,
      List<File> files,
      Integer embeddingRequestLimit,
      Boolean includeDuplicates) {
    this(requestId, files, embeddingRequestLimit, includeDuplicates, null, null);
  }

  /**
   * An open batch: no files yet, {@code expectedTotal} announced; entries are appended with {@link
   * DocumentBatchAppendCommand} and the manifest is closed with a seal.
   */
  public static DocumentBatchCommand open(
      String requestId,
      int expectedTotal,
      Integer embeddingRequestLimit,
      Boolean includeDuplicates) {
    return new DocumentBatchCommand(
        requestId, null, embeddingRequestLimit, includeDuplicates, true, expectedTotal);
  }

  public record File(
      String filename,
      @JsonProperty("size_bytes") long sizeBytes,
      @JsonProperty("source_sha256") String sourceSha256,
      @JsonProperty("relative_path") String relativePath) {
    public File(String filename, long sizeBytes, String sourceSha256) {
      this(filename, sizeBytes, sourceSha256, null);
    }
  }
}
