package com.evidence.rag.model.domain;

/** Current-authorized video citation, optionally carrying the validated original video. */
public record VideoSourceEvidence(
    PublishedVideoEvidence source, VideoTraceEvidence trace, SourceVideo video) {
  public VideoSourceEvidence {
    if (source == null
        || trace == null
        || (video != null
            && (!video.mediaType().equals(source.source().mediaType())
                || !ModelValues.sha256(video.content())
                    .equals(source.source().publication().sourceSha256())))) {
      throw ModelValues.invalid();
    }
    var group = source.source();
    if (trace.kind() == VideoTraceEvidence.Kind.VISUAL) {
      if (source.proofInput().frame() == null
          || !trace.physicalSegmentId().equals(group.framePhysicalSegmentId())) {
        throw ModelValues.invalid();
      }
    } else {
      var transcript = source.proofInput().transcript();
      if (transcript == null
          || !trace.physicalSegmentId().equals(group.transcriptPhysicalSegmentId())
          || trace.startCodePoint() < transcript.startCodePoint()
          || trace.endCodePoint() > transcript.endCodePoint()) {
        throw ModelValues.invalid();
      }
    }
  }

  public VideoSourceEvidence(PublishedVideoEvidence source, VideoTraceEvidence trace) {
    this(source, trace, null);
  }

  public String quote() {
    if (trace.kind() == VideoTraceEvidence.Kind.VISUAL) {
      return null;
    }
    String context = source.proofInput().transcript().contextText();
    return context.substring(
        context.offsetByCodePoints(0, trace.startCodePoint()),
        context.offsetByCodePoints(0, trace.endCodePoint()));
  }

  @Override
  public String toString() {
    return "VideoSourceEvidence[redacted]";
  }
}
