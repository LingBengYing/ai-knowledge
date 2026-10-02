package com.evidence.rag.model.dto;

import com.evidence.rag.model.domain.ImageTextRegion;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Frame-local code points and original-pixel word boxes, never a corpus page. */
public record VideoOcrResult(
    @JsonProperty("start_code_point") int startCodePoint,
    @JsonProperty("end_code_point") int endCodePoint,
    String quote,
    @JsonProperty("quote_sha256") String quoteSha256,
    @JsonProperty("ocr_revision") String ocrRevision,
    List<ImageTextRegion> regions) {
  public VideoOcrResult {
    regions = List.copyOf(regions);
  }

  @Override
  public String toString() {
    return "VideoOcrResult[redacted]";
  }
}
