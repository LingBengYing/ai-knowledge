package com.evidence.rag.model.domain;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/** Complete same-track context; empty original packets still contribute their separators. */
public record VideoSubtitleTrackEvidence(
    String id,
    String revisionId,
    VideoSubtitleTrack track,
    String text,
    String textSha256,
    List<VideoSubtitleCueEvidence> cues) {
  public VideoSubtitleTrackEvidence {
    ModelValues.indexIdentity(revisionId);
    if (track == null
        || !identity(revisionId, track.streamIndex()).equals(id)
        || text == null
        || !track.cues().stream()
            .map(VideoSubtitleCue::text)
            .collect(Collectors.joining("\n"))
            .equals(text)
        || !ModelValues.sha256(text.getBytes(StandardCharsets.UTF_8)).equals(textSha256)
        || cues == null
        || cues.size() != track.cues().size()) {
      throw ModelValues.invalid();
    }
    int offset = 0;
    for (int ordinal = 0; ordinal < cues.size(); ordinal++) {
      var cue = cues.get(ordinal);
      var packet = track.cues().get(ordinal);
      int end = offset + packet.text().codePointCount(0, packet.text().length());
      if (cue == null
          || !cue.revisionId().equals(revisionId)
          || !cue.trackId().equals(id)
          || cue.streamIndex() != track.streamIndex()
          || !cue.cue().equals(packet)
          || cue.startOffset() != offset
          || cue.endOffset() != end) {
        throw ModelValues.invalid();
      }
      offset = end + 1;
    }
    cues = List.copyOf(cues);
  }

  public static String identity(String revisionId, int streamIndex) {
    ModelValues.indexIdentity(revisionId);
    if (streamIndex < 0) {
      throw ModelValues.invalid();
    }
    return "video-subtitle-track-"
        + ModelValues.sha256((revisionId + "\0" + streamIndex).getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public String toString() {
    return "VideoSubtitleTrackEvidence[redacted]";
  }
}
