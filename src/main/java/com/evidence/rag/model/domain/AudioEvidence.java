package com.evidence.rag.model.domain;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Authority-owned server-cut span; the optional index ordinal excludes silent spans. */
public record AudioEvidence(
    String id,
    String revisionId,
    int ordinal,
    long startMs,
    long endMs,
    String text,
    String textSha256,
    Integer indexOrdinal) {
  public AudioEvidence {
    ModelValues.indexIdentity(revisionId);
    var span = new AudioTranscriptSpan(ordinal, startMs, endMs, text);
    if (!identity(revisionId, ordinal).equals(id)
        || !span.textSha256().equals(textSha256)
        || (text.isBlank() ? indexOrdinal != null : indexOrdinal == null)
        || (indexOrdinal != null && (indexOrdinal < 0 || indexOrdinal >= 600))) {
      throw ModelValues.invalid();
    }
  }

  public static List<AudioEvidence> fromCompilation(
      String revisionId, AudioCompilation compilation) {
    ModelValues.indexIdentity(revisionId);
    if (compilation == null) {
      throw ModelValues.invalid();
    }
    var evidence = new ArrayList<AudioEvidence>();
    int indexOrdinal = 0;
    for (var span : compilation.spans()) {
      evidence.add(
          new AudioEvidence(
              identity(revisionId, span.ordinal()),
              revisionId,
              span.ordinal(),
              span.startMs(),
              span.endMs(),
              span.text(),
              span.textSha256(),
              span.text().isBlank() ? null : indexOrdinal++));
    }
    return List.copyOf(evidence);
  }

  private static String identity(String revisionId, int ordinal) {
    return "audio-"
        + ModelValues.sha256((revisionId + "\0" + ordinal).getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public String toString() {
    return "AudioEvidence[redacted]";
  }
}
