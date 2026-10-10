package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Whole-image anchor; OCR offsets remain transcription offsets, not character boxes. */
public record ImageSourceResult(
    String type,
    @JsonProperty("mime_type") String mimeType,
    int width,
    int height,
    List<Integer> bbox,
    @JsonProperty("coordinate_system") String coordinateSystem,
    @JsonProperty("text_origin") String textOrigin,
    @JsonProperty("content_url") String contentUrl,
    @JsonProperty("region_kind") @JsonInclude(JsonInclude.Include.NON_NULL) String regionKind,
    @JsonInclude(JsonInclude.Include.NON_NULL) List<ImageTextRegionResult> regions) {

  public ImageSourceResult {
    bbox = List.copyOf(bbox);
    regions = regions == null ? null : List.copyOf(regions);
  }

  @Override
  public String toString() {
    return "ImageSourceResult[redacted]";
  }
}
