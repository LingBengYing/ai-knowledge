package com.evidence.rag.model.dto;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.ProductHelpEvidence;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.Locale;

/** Original text plus an authority-owned page or media interval; no generated instructions. */
public record ProductHelpMatch(
    int rank,
    String category,
    @JsonProperty("evidence_kind") String evidenceKind,
    @JsonProperty("document_id") String documentId,
    @JsonProperty("revision_id") String revisionId,
    String filename,
    @JsonProperty("source_sha256") String sourceSha256,
    @JsonProperty("parser_revision") String parserRevision,
    @JsonProperty("media_type") String mediaType,
    String text,
    @JsonProperty("text_sha256") String textSha256,
    Integer page,
    Integer start,
    Integer end,
    @JsonProperty("start_ms") BigDecimal startMs,
    @JsonProperty("end_ms") BigDecimal endMs,
    String origin,
    @JsonProperty("time_precision") String timePrecision,
    @JsonProperty("content_url") String contentUrl,
    @JsonProperty("retrieval_score") double retrievalScore,
    @JsonProperty("rerank_score") Double rerankScore) {
  public static ProductHelpMatch from(
      int rank, ProductHelpEvidence evidence, double retrievalScore, Double rerankScore) {
    if (rank < 1
        || rank > 10
        || evidence == null
        || !Double.isFinite(retrievalScore)
        || retrievalScore < 0
        || (rerankScore != null && !Double.isFinite(rerankScore))) {
      throw ModelValues.invalid();
    }
    var source = evidence.publication();
    return new ProductHelpMatch(
        rank,
        evidence.category(),
        evidence.kind().name().toLowerCase(Locale.ROOT),
        source.documentId(),
        source.sourceRevisionId(),
        evidence.filename(),
        source.sourceSha256(),
        source.parserRevision(),
        evidence.mediaType(),
        evidence.text(),
        evidence.textSha256(),
        evidence.page(),
        evidence.start(),
        evidence.end(),
        evidence.startUs() == null ? null : BigDecimal.valueOf(evidence.startUs(), 3),
        evidence.endUs() == null ? null : BigDecimal.valueOf(evidence.endUs(), 3),
        evidence.origin(),
        evidence.timePrecision(),
        "/v1/documents/"
            + source.documentId()
            + "/revisions/"
            + source.sourceRevisionId()
            + "/content",
        retrievalScore,
        rerankScore);
  }

  @Override
  public String toString() {
    return "ProductHelpMatch[redacted]";
  }
}
