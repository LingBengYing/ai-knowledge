package com.evidence.rag.web.converter;

import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.model.domain.VideoAvState;
import com.evidence.rag.model.dto.VideoAvIndexResult;
import com.evidence.rag.model.dto.VideoAvUploadResult;

/** Public original and publication metadata; no media bytes or private configuration. */
public final class VideoAvResponseMapper {
  private VideoAvResponseMapper() {}

  public static VideoAvUploadResult upload(DocumentOriginal original) {
    return new VideoAvUploadResult(
        original.documentId(),
        original.revisionId(),
        original.sourceSha256(),
        original.sizeBytes());
  }

  public static VideoAvIndexResult index(
      VideoAvState state, String profileFingerprint, String analysisModelRevision) {
    var source = state.original();
    var publication = state.publication();
    return new VideoAvIndexResult(
        publication == null ? "missing" : "available",
        source.documentId(),
        source.revisionId(),
        source.sourceSha256(),
        profileFingerprint,
        analysisModelRevision,
        state.visualTarget().modelRevision(),
        state.visualTarget().dimensions(),
        publication == null ? null : publication.id(),
        publication == null ? null : publication.id(),
        publication == null ? null : publication.manifestSha256(),
        publication == null ? 0 : publication.windowCount(),
        publication == null ? 0 : publication.videoWindowCount(),
        publication == null ? 0 : publication.audioWindowCount());
  }
}
