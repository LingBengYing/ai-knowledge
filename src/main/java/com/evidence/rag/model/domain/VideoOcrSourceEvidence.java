package com.evidence.rag.model.domain;

import java.util.List;

/** Rematerialized source of one OCR excerpt and its sealed original frame/video. */
public record VideoOcrSourceEvidence(
    PublishedVideoOcrEvidence source, TraceEvidence trace, VideoFrame frame, SourceVideo video) {
  public VideoOcrSourceEvidence {
    if (source == null
        || trace == null
        || frame == null
        || !trace.physicalSegmentId().equals(source.physicalSegmentId())
        || trace.start() < source.source().segment().start()
        || trace.end() > source.source().segment().end()
        || !frame.image().sha256().equals(source.frame().frameSha256())
        || frame.ordinal() != source.frame().frameOrdinal()
        || frame.presentationUs() != source.framePresentationUs()
        || frame.durationUs() != source.frameDurationUs()
        || frame.width() != source.frame().dimensions().width()
        || frame.height() != source.frame().dimensions().height()) {
      throw ModelValues.invalid();
    }
  }

  public String quote() {
    String text = source.frame().text();
    return text.substring(
        text.offsetByCodePoints(0, trace.start()), text.offsetByCodePoints(0, trace.end()));
  }

  public List<ImageTextRegion> regions() {
    return source.frame().regions().stream()
        .filter(r -> r.start() < trace.end() && r.end() > trace.start())
        .toList();
  }

  @Override
  public String toString() {
    return "VideoOcrSourceEvidence[redacted]";
  }
}
