package com.evidence.rag.model.domain;

import java.util.List;
import java.util.UUID;

/** Frozen raw source, complete actual samples and a fresh immutable projection generation. */
public record SoundBuildClaim(
    Actor actor,
    DocumentOriginal original,
    IndexTarget target,
    String generationId,
    String soundModelRevision,
    String decoderRevision,
    int chunkSeconds,
    List<SoundInputSpan> spans,
    String profileFingerprint) {
  public SoundBuildClaim {
    if (actor == null
        || original == null
        || !"audio".equals(original.documentType())
        || target == null
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
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw ModelValues.invalid();
    }
    long end = 0;
    for (int i = 0; i < spans.size(); i++) {
      var span = spans.get(i);
      if (span == null
          || span.ordinal() != i
          || !span.id().equals(SoundProfile.spanId(original.revisionId(), i))
          || span.waveform().startSample() != end
          || !span.waveform().sourceSha256().equals(original.sourceSha256())
          || !span.waveform().decoderRevision().equals(decoderRevision)
          || span.waveform().endSample() - end > chunkSeconds * 16000L
          || (i + 1 < spans.size() && span.waveform().endSample() - end != chunkSeconds * 16000L)) {
        throw ModelValues.invalid();
      }
      end = span.waveform().endSample();
    }
    spans = List.copyOf(spans);
  }

  @Override
  public String toString() {
    return "SoundBuildClaim[redacted]";
  }
}
