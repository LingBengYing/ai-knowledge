package com.evidence.rag.model.domain;

/** One published subtitle cue with its complete, independently sealed track context. */
public record PublishedVideoSubtitleEvidence(
    PublicationVersion publication,
    String physicalSegmentId,
    String entrySha256,
    VideoSubtitleCueEvidence source,
    VideoSubtitleTrackEvidence track,
    String subtitleManifestSha256,
    String nativeManifestSha256,
    String decoderRevision,
    String filename,
    String mediaType) {
  public PublishedVideoSubtitleEvidence {
    ModelValues.identifier(physicalSegmentId, 128);
    ModelValues.identifier(decoderRevision, 200);
    ModelValues.identifier(filename, 255);
    if (publication == null
        || source == null
        || track == null
        || !publication.parserRevision().matches("java-video-compiler-v[34]:[a-f0-9]{64}")
        || !publication.sourceRevisionId().equals(source.revisionId())
        || !publication.sourceRevisionId().equals(track.revisionId())
        || !source.trackId().equals(track.id())
        || source.streamIndex() != track.track().streamIndex()
        || !track.cues().contains(source)
        || source.indexOrdinal() == null
        || source.startUs() == null
        || source.endUs() == null
        || source.cue().text().isBlank()
        || physicalSegmentId.equals(source.id())
        || mediaType == null
        || !mediaType.matches("video/(mp4|quicktime|webm|x-matroska)")) {
      throw ModelValues.invalid();
    }
    for (String hash : new String[] {entrySha256, subtitleManifestSha256, nativeManifestSha256}) {
      if (hash == null || !hash.matches("[a-f0-9]{64}")) {
        throw ModelValues.invalid();
      }
    }
  }

  public GroundingText grounding() {
    return new GroundingText(
        physicalSegmentId,
        publication.publicationId() + ":video-subtitle:" + track.id(),
        track.text(),
        track.textSha256(),
        source.startOffset(),
        source.endOffset());
  }

  public String trackText() {
    return track.text();
  }

  public String trackTextSha256() {
    return track.textSha256();
  }

  @Override
  public String toString() {
    return "PublishedVideoSubtitleEvidence[redacted]";
  }
}
