package com.evidence.rag.model.domain;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Independent subtitle authority manifest, bound to complete video and optional OCR products. */
public record VideoSubtitleEvidence(
    List<VideoSubtitleTrackEvidence> tracks, String manifestSha256) {
  public VideoSubtitleEvidence {
    if (tracks == null
        || tracks.size() > 4
        || manifestSha256 == null
        || !manifestSha256.matches("[a-f0-9]{64}")) {
      throw ModelValues.invalid();
    }
    int previousStream = -1;
    int projectionOrdinal = 0;
    int packets = 0;
    long points = 0;
    String revisionId = null;
    for (var track : tracks) {
      if (track == null
          || track.track().streamIndex() <= previousStream
          || (revisionId != null && !revisionId.equals(track.revisionId()))) {
        throw ModelValues.invalid();
      }
      previousStream = track.track().streamIndex();
      revisionId = track.revisionId();
      packets += track.cues().size();
      for (var cue : track.cues()) {
        points += cue.cue().text().codePointCount(0, cue.cue().text().length());
        if (cue.indexOrdinal() != null && cue.indexOrdinal() != projectionOrdinal++) {
          throw ModelValues.invalid();
        }
      }
    }
    if (packets > 2048 || points > 500_000) {
      throw ModelValues.invalid();
    }
    tracks = List.copyOf(tracks);
  }

  public static VideoSubtitleEvidence fromCompilation(
      String revisionId, VideoCompilation compilation) {
    ModelValues.indexIdentity(revisionId);
    if (compilation == null
        || compilation.subtitles() == null
        || !(compilation.compilerRevision().matches("java-video-compiler-v3:[a-f0-9]{64}")
            || compilation.textEvidenceOnly())) {
      throw ModelValues.invalid();
    }
    var base = VideoEvidence.fromCompilation(revisionId, compilation);
    var ocr =
        compilation.ocr() == null
            ? null
            : VideoOcrEvidence.fromCompilation(revisionId, compilation);
    var nativeSubtitles = compilation.subtitles();
    var manifest = new StringBuilder();
    fields(
        manifest,
        "video-subtitle-authority-v1",
        revisionId,
        compilation.sourceSha256(),
        compilation.decoderRevision(),
        compilation.compilerRevision(),
        base.manifestSha256(),
        ocr != null,
        ocr == null ? null : ocr.manifestSha256(),
        nativeSubtitles.manifestSha256(),
        nativeSubtitles.tracks().size());
    var tracks = new ArrayList<VideoSubtitleTrackEvidence>();
    int indexOrdinal = 0;
    long points =
        compilation.frames().stream()
            .filter(frame -> frame.recall() != null)
            .mapToLong(
                frame ->
                    frame
                        .recall()
                        .recallText()
                        .codePointCount(0, frame.recall().recallText().length()))
            .sum();
    if (compilation.audio() != null) {
      points +=
          compilation.audio().spans().stream()
              .filter(span -> !span.text().isBlank())
              .mapToLong(span -> span.text().codePointCount(0, span.text().length()))
              .sum();
    }
    if (ocr != null) {
      points +=
          ocr.segments().stream()
              .mapToLong(
                  segment ->
                      segment.segment().text().codePointCount(0, segment.segment().text().length()))
              .sum();
    }
    for (var track : nativeSubtitles.tracks()) {
      String trackId = VideoSubtitleTrackEvidence.identity(revisionId, track.streamIndex());
      String text =
          track.cues().stream().map(VideoSubtitleCue::text).collect(Collectors.joining("\n"));
      var cues = new ArrayList<VideoSubtitleCueEvidence>();
      int offset = 0;
      for (var cue : track.cues()) {
        int end = offset + cue.text().codePointCount(0, cue.text().length());
        boolean blank = cue.text().isBlank();
        var evidence =
            new VideoSubtitleCueEvidence(
                VideoSubtitleCueEvidence.identity(revisionId, track.streamIndex(), cue.ordinal()),
                revisionId,
                trackId,
                track.streamIndex(),
                cue,
                offset,
                end,
                blank ? null : nativeSubtitles.startUs(track, cue),
                blank ? null : nativeSubtitles.endUs(track, cue),
                blank ? null : indexOrdinal++);
        cues.add(evidence);
        if (!blank) {
          points += end - offset;
        }
        fields(
            manifest,
            "cue",
            evidence.id(),
            offset,
            end,
            evidence.startUs(),
            evidence.endUs(),
            evidence.indexOrdinal());
        offset = end + 1;
      }
      var evidence =
          new VideoSubtitleTrackEvidence(trackId, revisionId, track, text, sha(text), cues);
      tracks.add(evidence);
      fields(manifest, "track", trackId, track.streamIndex(), evidence.textSha256(), cues.size());
    }
    if (points > 1_500_000
        || base.projectionCount() + (ocr == null ? 0 : ocr.segments().size()) + indexOrdinal
            > 4096) {
      throw ModelValues.invalid();
    }
    return new VideoSubtitleEvidence(tracks, sha(manifest.toString()));
  }

  public int projectionCount() {
    return (int)
        tracks.stream()
            .flatMap(track -> track.cues().stream())
            .filter(cue -> cue.indexOrdinal() != null)
            .count();
  }

  private static String sha(String text) {
    return ModelValues.sha256(text.getBytes(StandardCharsets.UTF_8));
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

  @Override
  public String toString() {
    return "VideoSubtitleEvidence[redacted]";
  }
}
