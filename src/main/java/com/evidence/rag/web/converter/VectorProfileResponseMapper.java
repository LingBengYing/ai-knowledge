package com.evidence.rag.web.converter;

import com.evidence.rag.model.domain.AudioVectorState;
import com.evidence.rag.model.domain.ImageVectorState;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.vo.VectorProfileResponse;

/** Ten-field response whitelist for the exact saved-original audio or image vector profile. */
public final class VectorProfileResponseMapper {
  private VectorProfileResponseMapper() {}

  public static VectorProfileResponse audio(AudioVectorState state) {
    var receipt = state.publication();
    return response(
        state.basePublication(),
        state.target(),
        receipt != null,
        receipt == null ? null : receipt.vectorGenerationId(),
        receipt == null ? null : receipt.manifestSha256());
  }

  public static VectorProfileResponse image(ImageVectorState state) {
    var receipt = state.publication();
    return response(
        state.basePublication(),
        state.target(),
        receipt != null,
        receipt == null ? null : receipt.vectorGenerationId(),
        receipt == null ? null : receipt.manifestSha256());
  }

  private static VectorProfileResponse response(
      PublicationVersion base,
      IndexTarget target,
      boolean available,
      String vectorGenerationId,
      String manifest) {
    return new VectorProfileResponse(
        available ? "available" : "missing",
        base.documentId(),
        base.publicationId(),
        base.sourceRevisionId(),
        base.sourceSha256(),
        target.projectionIdentity(),
        target.modelRevision(),
        target.dimensions(),
        vectorGenerationId,
        manifest);
  }
}
