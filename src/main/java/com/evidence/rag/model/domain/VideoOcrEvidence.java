package com.evidence.rag.model.domain;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Deterministic OCR sidecar manifest, including processed no-text frames and pixel geometry. */
public record VideoOcrEvidence(List<VideoOcrSegmentEvidence> segments, String manifestSha256) {
  public VideoOcrEvidence {
    segments = List.copyOf(segments);
    if (segments.size() > 4096
        || manifestSha256 == null
        || !manifestSha256.matches("[a-f0-9]{64}")) {
      throw ModelValues.invalid();
    }
  }

  public static VideoOcrEvidence fromCompilation(String revisionId, VideoCompilation compilation) {
    if (compilation == null || compilation.ocr() == null) {
      throw ModelValues.invalid();
    }
    var base = VideoEvidence.fromCompilation(revisionId, compilation);
    var output = new StringBuilder();
    fields(
        output,
        "video-ocr-authority-v1",
        revisionId,
        compilation.sourceSha256(),
        compilation.compilerRevision(),
        base.manifestSha256(),
        compilation.ocr().ocrRevision());
    var segments = new ArrayList<VideoOcrSegmentEvidence>();
    long points =
        compilation.frames().stream()
            .filter(frame -> frame.recall() != null)
            .mapToLong(
                f -> f.recall().recallText().codePointCount(0, f.recall().recallText().length()))
            .sum();
    if (compilation.audio() != null) {
      points +=
          compilation.audio().spans().stream()
              .mapToLong(s -> s.text().codePointCount(0, s.text().length()))
              .sum();
    }
    for (var frame : compilation.ocr().frames()) {
      String frameId = VideoEvidence.frameIdentity(revisionId, frame.frameOrdinal());
      fields(
          output,
          "frame",
          frameId,
          frame.frameSha256(),
          frame.dimensions().width(),
          frame.dimensions().height(),
          sha(frame.text()),
          frame.segments().size(),
          frame.regions().size());
      for (var segment : frame.segments()) {
        var evidence =
            new VideoOcrSegmentEvidence(
                VideoOcrSegmentEvidence.identity(revisionId, frameId, segment.ordinal()),
                revisionId,
                frameId,
                segment);
        segments.add(evidence);
        points += segment.text().codePointCount(0, segment.text().length());
        fields(
            output,
            "segment",
            evidence.id(),
            segment.ordinal(),
            segment.start(),
            segment.end(),
            sha(segment.text()));
      }
      for (var region : frame.regions()) {
        fields(
            output,
            "region",
            region.start(),
            region.end(),
            region.left(),
            region.top(),
            region.right(),
            region.bottom());
      }
    }
    if (points > 1_500_000 || base.projectionCount() + segments.size() > 4096) {
      throw ModelValues.invalid();
    }
    return new VideoOcrEvidence(segments, sha(output.toString()));
  }

  private static String sha(String text) {
    return ModelValues.sha256(text.getBytes(StandardCharsets.UTF_8));
  }

  private static void fields(StringBuilder output, Object... values) {
    for (Object value : values) {
      String text = value.toString();
      output.append(text.length()).append(':').append(text).append(';');
    }
  }

  @Override
  public String toString() {
    return "VideoOcrEvidence[redacted]";
  }
}
