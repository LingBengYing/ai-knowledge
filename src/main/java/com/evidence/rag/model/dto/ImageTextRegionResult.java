package com.evidence.rag.model.dto;

import java.util.List;

/** OCR word offsets and its normalized original-image rectangle. */
public record ImageTextRegionResult(int start, int end, List<Double> bbox) {
  public ImageTextRegionResult {
    bbox = List.copyOf(bbox);
  }
}
