package com.evidence.rag.model.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Sealed complete original-sound publication, independent of a speech/text base publication. */
public record SoundPublication(
    String id,
    String workspaceId,
    String documentId,
    String sourceRevisionId,
    String sourceSha256,
    String filename,
    String mediaType,
    long sizeBytes,
    String generationId,
    IndexTarget target,
    String soundModelRevision,
    String decoderRevision,
    int chunkSeconds,
    long sampleCount,
    List<SoundSpan> spans,
    String manifestSha256,
    String profileFingerprint,
    String createdAt) {
  public SoundPublication {
    ModelValues.indexIdentity(id);
    ModelValues.indexIdentity(workspaceId);
    ModelValues.indexIdentity(documentId);
    ModelValues.indexIdentity(sourceRevisionId);
    ModelValues.identifier(filename, 255);
    SoundProfile.hash(sourceSha256);
    SoundProfile.hash(manifestSha256);
    if (target == null
        || mediaType == null
        || !mediaType.matches("audio/(wav|mpeg|flac|ogg|mp4|webm)")
        || sizeBytes < 1
        || sizeBytes > 20 * 1024 * 1024
        || sampleCount < 1
        || sampleCount > 9600000
        || spans == null
        || spans.isEmpty()
        || spans.size() > 600
        || !SoundProfile.fingerprint(target, soundModelRevision, decoderRevision, chunkSeconds)
            .equals(profileFingerprint)) {
      throw ModelValues.invalid();
    }
    try {
      if (!UUID.fromString(generationId).toString().equals(generationId)) {
        throw ModelValues.invalid();
      }
      Instant.parse(createdAt);
    } catch (RuntimeException invalid) {
      throw ModelValues.invalid();
    }
    long end = 0;
    for (int i = 0; i < spans.size(); i++) {
      var span = spans.get(i);
      if (span == null
          || span.ordinal() != i
          || !span.id().equals(SoundProfile.spanId(sourceRevisionId, i))
          || span.startSample() != end
          || span.endSample() - end > chunkSeconds * 16000L
          || (i + 1 < spans.size() && span.endSample() - end != chunkSeconds * 16000L)
          || !span.physicalSegmentId()
              .equals(SoundProfile.physicalSegmentId(generationId, span.id()))) {
        throw ModelValues.invalid();
      }
      end = span.endSample();
    }
    if (end != sampleCount
        || !manifestSha256.equals(
            SoundProfile.manifestSha256(workspaceId, documentId, generationId, spans))) {
      throw ModelValues.invalid();
    }
    spans = List.copyOf(spans);
  }

  @Override
  public String toString() {
    return "SoundPublication[redacted]";
  }
}
