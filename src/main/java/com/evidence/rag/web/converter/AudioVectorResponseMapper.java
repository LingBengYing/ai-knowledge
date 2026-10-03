package com.evidence.rag.web.converter;

import com.evidence.rag.model.domain.AudioVectorState;
import com.evidence.rag.model.vo.AudioVectorResponse;

/** Ten-field response whitelist for the exact saved-original-audio vector profile. */
public final class AudioVectorResponseMapper {
  private AudioVectorResponseMapper() {}

  public static AudioVectorResponse response(AudioVectorState state) {
    var base = state.basePublication();
    var target = state.target();
    var receipt = state.publication();
    return new AudioVectorResponse(
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
