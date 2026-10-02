package com.evidence.rag.model.domain;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Complete subtitle packet authority on the original video's rational epoch. */
public record VideoSubtitleCompilation(
    long videoEpochPts,
    long videoTimeBaseNumerator,
    long videoTimeBaseDenominator,
    List<VideoSubtitleTrack> tracks) {
  public static final String TEXT_FORMAT = "subtitle-payload-utf8-v1";
  private static final BigInteger MICROSECONDS = BigInteger.valueOf(1_000_000);

  public VideoSubtitleCompilation {
    if (videoTimeBaseNumerator <= 0
        || videoTimeBaseDenominator <= 0
        || tracks == null
        || tracks.size() > 4) {
      throw ModelValues.invalid();
    }
    originUs(videoEpochPts, videoTimeBaseNumerator, videoTimeBaseDenominator);
    int previousStream = -1;
    int packets = 0;
    long textPoints = 0;
    for (var track : tracks) {
      if (track == null || track.streamIndex() <= previousStream) {
        throw ModelValues.invalid();
      }
      previousStream = track.streamIndex();
      packets += track.cues().size();
      if (packets > 2048) {
        throw ModelValues.invalid();
      }
      for (var cue : track.cues()) {
        textPoints += cue.text().codePointCount(0, cue.text().length());
        if (textPoints > 500_000) {
          throw ModelValues.invalid();
        }
        if (!cue.text().isBlank()) {
          long start =
              relativeUs(
                  track,
                  BigInteger.valueOf(cue.pts()),
                  videoEpochPts,
                  videoTimeBaseNumerator,
                  videoTimeBaseDenominator,
                  false);
          long end =
              relativeUs(
                  track,
                  endPts(cue),
                  videoEpochPts,
                  videoTimeBaseNumerator,
                  videoTimeBaseDenominator,
                  true);
          if (start < 0 || end > 600_000_000) {
            throw ModelValues.invalid();
          }
        }
      }
    }
    tracks = List.copyOf(tracks);
  }

  public long timelineOriginUs() {
    return originUs(videoEpochPts, videoTimeBaseNumerator, videoTimeBaseDenominator);
  }

  public long startUs(VideoSubtitleTrack track, VideoSubtitleCue cue) {
    requireFactPacket(track, cue);
    return relativeUs(
        track,
        BigInteger.valueOf(cue.pts()),
        videoEpochPts,
        videoTimeBaseNumerator,
        videoTimeBaseDenominator,
        false);
  }

  public long endUs(VideoSubtitleTrack track, VideoSubtitleCue cue) {
    requireFactPacket(track, cue);
    return relativeUs(
        track, endPts(cue), videoEpochPts, videoTimeBaseNumerator, videoTimeBaseDenominator, true);
  }

  public long endUs() {
    long end = 0;
    for (var track : tracks) {
      for (var cue : track.cues()) {
        if (!cue.text().isBlank()) {
          end =
              Math.max(
                  end,
                  relativeUs(
                      track,
                      endPts(cue),
                      videoEpochPts,
                      videoTimeBaseNumerator,
                      videoTimeBaseDenominator,
                      true));
        }
      }
    }
    return end;
  }

  public String manifestSha256() {
    var material =
        new StringBuilder("video-subtitle-compilation-v1\0")
            .append(TEXT_FORMAT)
            .append('\0')
            .append(videoEpochPts)
            .append('\0')
            .append(videoTimeBaseNumerator)
            .append('\0')
            .append(videoTimeBaseDenominator)
            .append('\0')
            .append(tracks.size());
    for (var track : tracks) {
      material
          .append('\0')
          .append(track.streamIndex())
          .append('\0')
          .append(track.codec())
          .append('\0')
          .append(track.timeBaseNumerator())
          .append('\0')
          .append(track.timeBaseDenominator())
          .append('\0')
          .append(track.language() == null ? "absent" : "present:" + sha(track.language()))
          .append('\0')
          .append(track.cues().size());
      for (var cue : track.cues()) {
        material
            .append('\0')
            .append(cue.ordinal())
            .append('\0')
            .append(cue.pts())
            .append('\0')
            .append(cue.duration())
            .append('\0')
            .append(cue.payloadSha256())
            .append('\0')
            .append(sha(cue.text()));
      }
    }
    return sha(material.toString());
  }

  private void requireFactPacket(VideoSubtitleTrack track, VideoSubtitleCue cue) {
    if (track == null
        || cue == null
        || !tracks.contains(track)
        || cue.ordinal() >= track.cues().size()
        || !track.cues().get(cue.ordinal()).equals(cue)
        || cue.text().isBlank()) {
      throw ModelValues.invalid();
    }
  }

  private static long originUs(long epoch, long numerator, long denominator) {
    return rounded(
        BigInteger.valueOf(epoch).multiply(BigInteger.valueOf(numerator)).multiply(MICROSECONDS),
        BigInteger.valueOf(denominator),
        false);
  }

  private static BigInteger endPts(VideoSubtitleCue cue) {
    return BigInteger.valueOf(cue.pts()).add(BigInteger.valueOf(cue.duration()));
  }

  private static long relativeUs(
      VideoSubtitleTrack track,
      BigInteger pts,
      long videoEpochPts,
      long videoNumerator,
      long videoDenominator,
      boolean ceil) {
    var trackDenominator = BigInteger.valueOf(track.timeBaseDenominator());
    var epochDenominator = BigInteger.valueOf(videoDenominator);
    var packet =
        pts.multiply(BigInteger.valueOf(track.timeBaseNumerator())).multiply(epochDenominator);
    var epoch =
        BigInteger.valueOf(videoEpochPts)
            .multiply(BigInteger.valueOf(videoNumerator))
            .multiply(trackDenominator);
    return rounded(
        packet.subtract(epoch).multiply(MICROSECONDS),
        trackDenominator.multiply(epochDenominator),
        ceil);
  }

  private static long rounded(BigInteger numerator, BigInteger denominator, boolean ceil) {
    var divided = numerator.divideAndRemainder(denominator);
    var value = divided[0];
    if (divided[1].signum() != 0) {
      if (ceil && numerator.signum() > 0) {
        value = value.add(BigInteger.ONE);
      } else if (!ceil && numerator.signum() < 0) {
        value = value.subtract(BigInteger.ONE);
      }
    }
    try {
      return value.longValueExact();
    } catch (ArithmeticException outsideTimeline) {
      throw ModelValues.invalid();
    }
  }

  private static String sha(String text) {
    return ModelValues.sha256(text.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public String toString() {
    return "VideoSubtitleCompilation[redacted]";
  }
}
