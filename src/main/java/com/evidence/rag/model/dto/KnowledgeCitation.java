package com.evidence.rag.model.dto;

import com.evidence.rag.model.domain.KnowledgeReference;
import com.evidence.rag.model.domain.ProductHelpEvidence;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.Locale;

/** Flat discriminated source shape; absent page/time locators stay explicitly null. */
public record KnowledgeCitation(
    @JsonProperty("citation_id") int citationId,
    @JsonProperty("evidence_kind") String evidenceKind,
    @JsonProperty("document_id") String documentId,
    @JsonProperty("revision_id") String revisionId,
    String filename,
    @JsonProperty("source_sha256") String sourceSha256,
    @JsonProperty("parser_revision") String parserRevision,
    @JsonProperty("media_type") String mediaType,
    String quote,
    @JsonProperty("text_sha256") String textSha256,
    Integer page,
    Integer start,
    Integer end,
    @JsonProperty("start_ms") BigDecimal startMs,
    @JsonProperty("end_ms") BigDecimal endMs,
    @JsonProperty("time_precision") String timePrecision,
    String origin,
    @JsonProperty("content_url") String contentUrl,
    @JsonProperty("source_url") String sourceUrl) {
  public static KnowledgeCitation from(String answerId, KnowledgeReference reference) {
    var source = reference.evidence().source();
    var publication = source.publication();
    boolean document = source.kind() == ProductHelpEvidence.Kind.DOCUMENT_TEXT;
    return new KnowledgeCitation(
        reference.citationId(),
        source.kind().name().toLowerCase(Locale.ROOT),
        publication.documentId(),
        publication.sourceRevisionId(),
        source.filename(),
        publication.sourceSha256(),
        publication.parserRevision(),
        source.mediaType(),
        reference.quote(),
        reference.quoteSha256(),
        source.page(),
        document ? reference.start() : null,
        document ? reference.end() : null,
        source.startUs() == null ? null : BigDecimal.valueOf(source.startUs(), 3),
        source.endUs() == null ? null : BigDecimal.valueOf(source.endUs(), 3),
        source.timePrecision(),
        source.origin(),
        "/v1/documents/"
            + publication.documentId()
            + "/revisions/"
            + publication.sourceRevisionId()
            + "/content",
        "/v1/knowledge-sources/" + answerId + "/" + reference.citationId());
  }

  @Override
  public String toString() {
    return "KnowledgeCitation[redacted]";
  }
}
