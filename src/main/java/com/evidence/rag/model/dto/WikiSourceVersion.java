package com.evidence.rag.model.dto;

import com.evidence.rag.model.domain.ModelValues;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Publication the user reviewed before requesting compilation. */
public record WikiSourceVersion(
    @JsonProperty("document_id") String documentId,
    @JsonProperty("publication_id") String publicationId) {
  public WikiSourceVersion {
    ModelValues.identifier(documentId, 100);
    ModelValues.identifier(publicationId, 128);
  }
}
