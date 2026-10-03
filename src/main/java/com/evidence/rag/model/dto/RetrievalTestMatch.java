package com.evidence.rag.model.dto;

import com.evidence.rag.model.domain.ModelValues;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.charset.StandardCharsets;

/** Full authority-owned segment and code-point locator; scores are not factual confidence. */
public record RetrievalTestMatch(
    int rank,
    @JsonProperty("document_id") String documentId,
    @JsonProperty("revision_id") String revisionId,
    String filename,
    @JsonProperty("source_sha256") String sourceSha256,
    @JsonProperty("parser_revision") String parserRevision,
    int page,
    int start,
    int end,
    String text,
    @JsonProperty("text_sha256") String textSha256,
    @JsonProperty("retrieval_score") double retrievalScore,
    @JsonProperty("rerank_score") Double rerankScore) {
  public RetrievalTestMatch {
    ModelValues.identifier(documentId, 100);
    ModelValues.identifier(revisionId, 128);
    ModelValues.identifier(filename, 255);
    ModelValues.identifier(parserRevision, 200);
    if (rank < 1
        || rank > 20
        || page < 1
        || page > 500
        || start < 0
        || end <= start
        || end - start > 1200
        || text == null
        || text.codePointCount(0, text.length()) != end - start
        || text.codePoints().anyMatch(c -> c == 0 || (c >= 0xD800 && c <= 0xDFFF))
        || sourceSha256 == null
        || !sourceSha256.matches("[a-f0-9]{64}")
        || !ModelValues.sha256(text.getBytes(StandardCharsets.UTF_8)).equals(textSha256)
        || !Double.isFinite(retrievalScore)
        || retrievalScore < 0
        || (rerankScore != null && !Double.isFinite(rerankScore))) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "RetrievalTestMatch[redacted]";
  }
}
