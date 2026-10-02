package com.evidence.rag.model.domain;

import java.nio.charset.StandardCharsets;

/** Original subtitle packet identity and its code point range in the complete track text. */
public record VideoSubtitleCueEvidence(
    String id,
    String revisionId,
    String trackId,
    int streamIndex,
    VideoSubtitleCue cue,
    int startOffset,
    int endOffset,
    Long startUs,
    Long endUs,
    Integer indexOrdinal) {
  public VideoSubtitleCueEvidence {
    ModelValues.indexIdentity(revisionId);
    if (cue == null
        || !VideoSubtitleTrackEvidence.identity(revisionId, streamIndex).equals(trackId)
        || !identity(revisionId, streamIndex, cue.ordinal()).equals(id)
        || startOffset < 0
        || endOffset < startOffset
        || endOffset > 502_047
        || endOffset - startOffset != cue.text().codePointCount(0, cue.text().length())) {
      throw ModelValues.invalid();
    }
    if (cue.text().isBlank()) {
      if (startUs != null || endUs != null || indexOrdinal != null) {
        throw ModelValues.invalid();
      }
    } else if (startUs == null
        || endUs == null
        || indexOrdinal == null
        || startUs < 0
        || endUs <= startUs
        || endUs > 600_000_000
        || indexOrdinal < 0
        || indexOrdinal >= 2048) {
      throw ModelValues.invalid();
    }
  }

  public static String identity(String revisionId, int streamIndex, int ordinal) {
    ModelValues.indexIdentity(revisionId);
    if (streamIndex < 0 || ordinal < 0 || ordinal >= 2048) {
      throw ModelValues.invalid();
    }
    return "video-subtitle-cue-"
        + ModelValues.sha256(
            (revisionId + "\0" + streamIndex + "\0" + ordinal).getBytes(StandardCharsets.UTF_8));
  }

  public String textSha256() {
    return ModelValues.sha256(cue.text().getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public String toString() {
    return "VideoSubtitleCueEvidence[redacted]";
  }
}
