package com.evidence.rag.web.converter;

import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.model.domain.SoundState;
import com.evidence.rag.model.dto.SoundIndexResult;
import com.evidence.rag.model.dto.SoundUploadResult;

/** Exact safe upload and publication metadata, independent of speech tasks. */
public final class SoundResponseMapper {
  private SoundResponseMapper() {}

  public static SoundUploadResult upload(DocumentOriginal original) {
    return new SoundUploadResult(
        original.documentId(),
        original.revisionId(),
        original.sourceSha256(),
        original.sizeBytes());
  }

  public static SoundIndexResult index(SoundState state, String soundModelRevision) {
    var source = state.original();
    var publication = state.publication();
    return new SoundIndexResult(
        publication == null ? "missing" : "available",
        source.documentId(),
        source.revisionId(),
        source.sourceSha256(),
        state.profileFingerprint(),
        soundModelRevision,
        state.target().modelRevision(),
        state.target().dimensions(),
        publication == null ? null : publication.id(),
        publication == null ? null : publication.generationId(),
        publication == null ? null : publication.manifestSha256(),
        publication == null ? 0 : publication.spans().size());
  }
}
