package com.evidence.rag.model.domain;

/** Selected original frame and complete transcript; candidate span IDs are not physical IDs. */
public record PublishedVideoEvidence(
    PublishedVideoGroup source,
    VideoProofInput proofInput,
    VideoTranscriptEvidence transcriptSpan) {
  public PublishedVideoEvidence {
    if (source == null
        || proofInput == null
        || !source.group().equals(proofInput.group())
        || !source.publication().sourceSha256().equals(proofInput.sourceSha256())
        || !source.manifestSha256().equals(proofInput.manifestSha256())
        || (source.group().transcriptSpanId() == null) != (transcriptSpan == null)) {
      throw ModelValues.invalid();
    }
    var group = source.group();
    var frame = proofInput.frame();
    var transcript = proofInput.transcript();
    long start;
    long end;
    if (transcriptSpan != null) {
      if (!transcriptSpan.id().equals(group.transcriptSpanId())
          || !transcriptSpan.revisionId().equals(source.publication().sourceRevisionId())
          || (transcriptSpan.indexOrdinal() == null)
              != (source.transcriptPhysicalSegmentId() == null)
          || (transcriptSpan.span().text().isBlank()) != (transcript == null)
          || (transcript != null && !transcriptSpan.span().text().equals(transcript.snippet()))) {
        throw ModelValues.invalid();
      }
      start = transcriptSpan.span().startMs() * 1000;
      end = transcriptSpan.span().endMs() * 1000;
      if (frame != null) {
        start = Math.max(start, frame.presentationUs());
        end = Math.min(end, frame.presentationUs() + frame.durationUs());
      }
    } else {
      start = frame.presentationUs();
      end = frame.presentationUs() + frame.durationUs();
    }
    if (start >= end || group.startUs() != start || group.endUs() != end) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "PublishedVideoEvidence[redacted]";
  }
}
