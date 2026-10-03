package com.evidence.rag.model.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Complete immutable sidecar bound to one base publication and a distinct vector generation. */
public record AudioVectorPublication(
    String id,
    PublicationVersion basePublication,
    IndexTarget target,
    String vectorGenerationId,
    String decoderRevision,
    List<AudioVectorEntry> entries,
    String manifestSha256,
    String createdAt) {
  public AudioVectorPublication {
    ModelValues.identifier(id, 128);
    ModelValues.identifier(decoderRevision, 200);
    if (basePublication == null
        || target == null
        || entries == null
        || entries.isEmpty()
        || entries.size() > 600
        || entries.size() != basePublication.segmentCount()
        || manifestSha256 == null
        || !manifestSha256.matches("[a-f0-9]{64}")) {
      throw ModelValues.invalid();
    }
    try {
      if (!UUID.fromString(vectorGenerationId).toString().equals(vectorGenerationId)
          || vectorGenerationId.equals(basePublication.projectionGenerationId())) {
        throw ModelValues.invalid();
      }
      Instant.parse(createdAt);
    } catch (IllegalArgumentException | DateTimeParseException | NullPointerException failure) {
      throw ModelValues.invalid();
    }
    var evidence = new HashSet<String>();
    var bases = new HashSet<String>();
    var vectors = new HashSet<String>();
    int previous = -1;
    long end = 0;
    for (var entry : entries) {
      if (entry == null
          || entry.ordinal() <= previous
          || entry.startSample() < end
          || !evidence.add(entry.audioEvidenceId())
          || !bases.add(entry.basePhysicalSegmentId())
          || !vectors.add(entry.vectorPhysicalSegmentId())
          || entry.basePhysicalSegmentId().equals(entry.vectorPhysicalSegmentId())
          || !entry
              .audioEvidenceId()
              .equals(
                  "audio-"
                      + ModelValues.sha256(
                          (basePublication.sourceRevisionId() + "\0" + entry.ordinal())
                              .getBytes(StandardCharsets.UTF_8)))) {
        throw ModelValues.invalid();
      }
      previous = entry.ordinal();
      end = entry.endSample();
    }
    entries = List.copyOf(entries);
  }

  @Override
  public String toString() {
    return "AudioVectorPublication[redacted]";
  }
}
