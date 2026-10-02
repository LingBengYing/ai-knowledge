package com.evidence.rag.model.domain;

/** Published recall span with its complete authority transcript, never a fabricated text page. */
public record PublishedAudioEvidence(
    PublicationVersion publication,
    String physicalSegmentId,
    String entrySha256,
    AudioEvidence span,
    GroundingText transcript,
    String filename,
    String mediaType) {
  public PublishedAudioEvidence {
    ModelValues.identifier(physicalSegmentId, 128);
    ModelValues.identifier(filename, 255);
    if (publication == null
        || span == null
        || transcript == null
        || span.indexOrdinal() == null
        || !span.revisionId().equals(publication.sourceRevisionId())
        || !publication.parserRevision().matches("java-audio-compiler-v1:[a-f0-9]{64}")
        || !physicalSegmentId.equals(transcript.physicalId())
        || !(publication.publicationId() + "/audio").equals(transcript.contextId())
        || !span.text().equals(transcript.snippet())
        || entrySha256 == null
        || !entrySha256.matches("[a-f0-9]{64}")
        || mediaType == null
        || !mediaType.matches("audio/(wav|mpeg|flac|ogg|mp4|webm)")) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "PublishedAudioEvidence[redacted]";
  }
}
