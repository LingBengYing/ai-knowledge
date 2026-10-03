package com.evidence.rag.model.domain;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Complete frozen speech-span build input; gaps represent saved silent spans. */
public record AudioVectorBuildClaim(
    Actor actor,
    PublicationVersion basePublication,
    IndexTarget target,
    String vectorGenerationId,
    String decoderRevision,
    List<AudioVectorSpan> spans) {
  public AudioVectorBuildClaim {
    ModelValues.identifier(decoderRevision, 200);
    if (actor == null
        || basePublication == null
        || target == null
        || spans == null
        || spans.isEmpty()
        || spans.size() > 600
        || spans.size() != basePublication.segmentCount()) {
      throw ModelValues.invalid();
    }
    try {
      if (!UUID.fromString(vectorGenerationId).toString().equals(vectorGenerationId)
          || vectorGenerationId.equals(basePublication.projectionGenerationId())) {
        throw ModelValues.invalid();
      }
    } catch (IllegalArgumentException | NullPointerException failure) {
      throw ModelValues.invalid();
    }
    var ids = new HashSet<String>();
    var physical = new HashSet<String>();
    int previous = -1;
    long end = 0;
    for (var span : spans) {
      if (span == null
          || span.ordinal() <= previous
          || span.waveform().startSample() < end
          || !ids.add(span.audioEvidenceId())
          || !physical.add(span.basePhysicalSegmentId())
          || !span.waveform().sourceSha256().equals(basePublication.sourceSha256())
          || !span.waveform().decoderRevision().equals(decoderRevision)
          || !span.audioEvidenceId()
              .equals(
                  "audio-"
                      + ModelValues.sha256(
                          (basePublication.sourceRevisionId() + "\0" + span.ordinal())
                              .getBytes(StandardCharsets.UTF_8)))) {
        throw ModelValues.invalid();
      }
      previous = span.ordinal();
      end = span.waveform().endSample();
    }
    spans = List.copyOf(spans);
  }

  @Override
  public String toString() {
    return "AudioVectorBuildClaim[redacted]";
  }
}
