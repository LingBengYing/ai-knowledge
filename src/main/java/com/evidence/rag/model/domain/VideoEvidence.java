package com.evidence.rag.model.domain;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Deterministic authority identities, complete temporal groups and compilation manifest. */
public record VideoEvidence(
    List<VideoFrameEvidence> frames,
    List<VideoTranscriptEvidence> spans,
    List<VideoEvidenceGroup> groups,
    String manifestSha256,
    long frameBytes) {
  public VideoEvidence {
    frames = List.copyOf(frames);
    spans = List.copyOf(spans);
    groups = List.copyOf(groups);
    if (frames.isEmpty()
        || frames.size() > 128
        || spans.size() > 600
        || groups.isEmpty()
        || groups.size() > 77_528
        || frameBytes < 1
        || frameBytes > 32L * 1024 * 1024
        || manifestSha256 == null
        || !manifestSha256.matches("[a-f0-9]{64}")) {
      throw ModelValues.invalid();
    }
  }

  public static VideoEvidence fromCompilation(String revisionId, VideoCompilation compilation) {
    ModelValues.indexIdentity(revisionId);
    if (compilation == null) {
      throw ModelValues.invalid();
    }
    var frames = new ArrayList<VideoFrameEvidence>();
    var spans = new ArrayList<VideoTranscriptEvidence>();
    long bytes = 0;
    long recallPoints = 0;
    var manifest = new StringBuilder();
    fields(
        manifest,
        compilation.textEvidenceOnly() ? "video-authority-v2" : "video-authority-v1",
        revisionId,
        compilation.sourceSha256(),
        compilation.decoderRevision(),
        compilation.compilerRevision(),
        compilation.timelineOriginUs(),
        compilation.durationUs());
    for (var material : compilation.frames()) {
      var frame = material.frame();
      var evidence =
          new VideoFrameEvidence(frameIdentity(revisionId, frame.ordinal()), revisionId, material);
      frames.add(evidence);
      bytes += frame.image().content().length;
      var recall = material.recall();
      if (recall != null) {
        recallPoints += recall.recallText().codePointCount(0, recall.recallText().length());
      }
      fields(
          manifest,
          "frame",
          evidence.id(),
          frame.ordinal(),
          frame.presentationUs(),
          frame.durationUs(),
          frame.image().mediaType(),
          frame.image().sha256(),
          frame.width(),
          frame.height(),
          recall == null
              ? null
              : ModelValues.sha256(recall.recallText().getBytes(StandardCharsets.UTF_8)),
          recall == null ? null : recall.modelRevision());
    }
    int indexOrdinal = 0;
    var audio = compilation.audio();
    if (audio == null) {
      fields(manifest, "no-audio");
    } else {
      fields(
          manifest,
          "audio",
          audio.modelRevision(),
          audio.transcriptionRevision(),
          audio.sampleCount());
      for (var span : audio.spans()) {
        Integer projectionOrdinal = span.text().isBlank() ? null : indexOrdinal++;
        var evidence =
            new VideoTranscriptEvidence(
                transcriptIdentity(revisionId, span.ordinal()),
                revisionId,
                span,
                projectionOrdinal);
        spans.add(evidence);
        if (projectionOrdinal != null) {
          recallPoints += span.text().codePointCount(0, span.text().length());
        }
        fields(
            manifest,
            "span",
            evidence.id(),
            span.ordinal(),
            span.startMs(),
            span.endMs(),
            span.textSha256(),
            projectionOrdinal);
      }
    }
    if (recallPoints > 1_500_000) {
      throw ModelValues.invalid();
    }
    var groups = new ArrayList<VideoEvidenceGroup>();
    var pairedSpans = new HashSet<String>();
    for (var frame : frames) {
      long start = frame.material().frame().presentationUs();
      long end = start + frame.material().frame().durationUs();
      boolean paired = false;
      for (var span : spans) {
        long intersectionStart = Math.max(start, span.span().startMs() * 1000);
        long intersectionEnd = Math.min(end, span.span().endMs() * 1000);
        if (intersectionStart < intersectionEnd) {
          addGroup(groups, revisionId, intersectionStart, intersectionEnd, frame.id(), span.id());
          pairedSpans.add(span.id());
          paired = true;
        }
      }
      if (!paired) {
        addGroup(groups, revisionId, start, end, frame.id(), null);
      }
    }
    for (var span : spans) {
      if (!pairedSpans.contains(span.id())) {
        addGroup(
            groups,
            revisionId,
            span.span().startMs() * 1000,
            span.span().endMs() * 1000,
            null,
            span.id());
      }
    }
    for (var group : groups) {
      fields(
          manifest,
          "group",
          group.id(),
          group.ordinal(),
          group.startUs(),
          group.endUs(),
          group.frameId(),
          group.transcriptSpanId());
    }
    return new VideoEvidence(
        frames,
        spans,
        groups,
        ModelValues.sha256(manifest.toString().getBytes(StandardCharsets.UTF_8)),
        bytes);
  }

  public static String frameIdentity(String revisionId, int ordinal) {
    return identity("video-frame-", revisionId, ordinal, 128);
  }

  public static String transcriptIdentity(String revisionId, int ordinal) {
    return identity("video-transcript-", revisionId, ordinal, 600);
  }

  static String groupIdentity(String revisionId, String frameId, String transcriptSpanId) {
    return "video-group-"
        + ModelValues.sha256(
            (revisionId
                    + "\0"
                    + (frameId == null ? "" : frameId)
                    + "\0"
                    + (transcriptSpanId == null ? "" : transcriptSpanId))
                .getBytes(StandardCharsets.UTF_8));
  }

  private static String identity(String prefix, String revisionId, int ordinal, int maximum) {
    ModelValues.indexIdentity(revisionId);
    if (ordinal < 0 || ordinal >= maximum) {
      throw ModelValues.invalid();
    }
    return prefix
        + ModelValues.sha256((revisionId + "\0" + ordinal).getBytes(StandardCharsets.UTF_8));
  }

  private static void addGroup(
      List<VideoEvidenceGroup> groups,
      String revisionId,
      long start,
      long end,
      String frame,
      String span) {
    groups.add(
        new VideoEvidenceGroup(
            groupIdentity(revisionId, frame, span),
            revisionId,
            groups.size(),
            start,
            end,
            frame,
            span));
  }

  private static void fields(StringBuilder output, Object... values) {
    for (Object value : values) {
      if (value == null) {
        output.append("null;");
      } else {
        String text = value.toString();
        output.append(text.length()).append(':').append(text).append(';');
      }
    }
  }

  public int projectionCount() {
    return (int) frames.stream().filter(frame -> frame.material().recall() != null).count()
        + (int) spans.stream().filter(span -> span.indexOrdinal() != null).count();
  }

  @Override
  public String toString() {
    return "VideoEvidence[redacted]";
  }
}
