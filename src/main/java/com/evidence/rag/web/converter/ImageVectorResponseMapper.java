package com.evidence.rag.web.converter;

import com.evidence.rag.model.domain.ImageVectorState;
import com.evidence.rag.model.vo.ImageVectorResponse;

/** Ten-field response whitelist for the exact saved-original-image vector profile. */
public final class ImageVectorResponseMapper {
  private ImageVectorResponseMapper() {}

  public static ImageVectorResponse response(ImageVectorState state) {
    var base = state.basePublication();
    var target = state.target();
    var receipt = state.publication();
    return new ImageVectorResponse(
        receipt == null ? "missing" : "available",
        base.documentId(),
        base.publicationId(),
        base.sourceRevisionId(),
        base.sourceSha256(),
        target.projectionIdentity(),
        target.modelRevision(),
        target.dimensions(),
        receipt == null ? null : receipt.vectorGenerationId(),
        receipt == null ? null : receipt.manifestSha256());
  }
}
