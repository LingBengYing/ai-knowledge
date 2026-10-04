package com.evidence.rag.web.converter;

import com.evidence.rag.model.dto.DocumentReplacementResult;
import com.evidence.rag.model.vo.DocumentReplacementResponse;

public final class DocumentReplacementResponseMapper {
  private DocumentReplacementResponseMapper() {}

  public static DocumentReplacementResponse from(DocumentReplacementResult value) {
    return new DocumentReplacementResponse(
        value.documentId(),
        value.baseRevisionId(),
        value.basePublicationId(),
        value.candidateRevisionId(),
        value.pipeline(),
        value.state(),
        value.filename(),
        value.documentType(),
        value.mediaType(),
        value.sourceSha256(),
        value.sizeBytes(),
        value.ingestionTask() == null ? null : TaskResponseMapper.from(value.ingestionTask()),
        value.indexTask() == null ? null : TaskResponseMapper.from(value.indexTask()),
        value.canUpload(),
        value.canIndex(),
        value.publicationId());
  }
}
