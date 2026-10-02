package com.evidence.rag.model.domain;

import java.util.List;

/** Current-authorized original bytes and server-owned locator for one synopsis reference. */
public record SynopsisSourceMaterial(
    SynopsisEvidence evidence,
    String filename,
    String mediaType,
    Locator locator,
    byte[] content,
    VisualImage frame) {
  public sealed interface Locator
      permits Page, Image, Audio, VideoFrame, VideoTranscript, VideoOcr, VideoSubtitle {}

  public record Page(
      int page, int start, int end, ImageDimensions dimensions, List<ImageTextRegion> regions)
      implements Locator {
    public Page {
      if (page < 1 || start < 0 || end <= start) {
        throw ModelValues.invalid();
      }
      regions = checkedRegions(dimensions, regions, start, end);
    }

    @Override
    public String toString() {
      return "SynopsisSourceMaterial.Page[redacted]";
    }
  }

  public record Image(ImageDimensions dimensions) implements Locator {
    public Image {
      if (dimensions == null) {
        throw ModelValues.invalid();
      }
    }

    @Override
    public String toString() {
      return "SynopsisSourceMaterial.Image[redacted]";
    }
  }

  public record Audio(int spanOrdinal, SynopsisEvidence.TimeRange time) implements Locator {
    public Audio {
      if (spanOrdinal < 0 || time == null) {
        throw ModelValues.invalid();
      }
    }

    @Override
    public String toString() {
      return "SynopsisSourceMaterial.Audio[redacted]";
    }
  }

  public record VideoFrame(
      String frameId, int frameOrdinal, SynopsisEvidence.TimeRange time, ImageDimensions dimensions)
      implements Locator {
    public VideoFrame {
      ModelValues.identifier(frameId, 128);
      if (frameOrdinal < 0 || time == null || dimensions == null) {
        throw ModelValues.invalid();
      }
    }

    @Override
    public String toString() {
      return "SynopsisSourceMaterial.VideoFrame[redacted]";
    }
  }

  public record VideoTranscript(String spanId, int spanOrdinal, SynopsisEvidence.TimeRange time)
      implements Locator {
    public VideoTranscript {
      ModelValues.identifier(spanId, 128);
      if (spanOrdinal < 0 || time == null) {
        throw ModelValues.invalid();
      }
    }

    @Override
    public String toString() {
      return "SynopsisSourceMaterial.VideoTranscript[redacted]";
    }
  }

  public record VideoOcr(
      String frameId,
      int frameOrdinal,
      SynopsisEvidence.TimeRange time,
      ImageDimensions dimensions,
      int start,
      int end,
      List<ImageTextRegion> regions)
      implements Locator {
    public VideoOcr {
      ModelValues.identifier(frameId, 128);
      if (frameOrdinal < 0 || time == null || dimensions == null || start < 0 || end <= start) {
        throw ModelValues.invalid();
      }
      regions = checkedRegions(dimensions, regions, start, end);
    }

    @Override
    public String toString() {
      return "SynopsisSourceMaterial.VideoOcr[redacted]";
    }
  }

  /** Reuse the sealed publication/cue identity instead of another mutable locator copy. */
  public record VideoSubtitle(PublishedVideoSubtitleEvidence subtitle) implements Locator {
    public VideoSubtitle {
      if (subtitle == null) {
        throw ModelValues.invalid();
      }
    }

    public SynopsisEvidence.TimeRange time() {
      return new SynopsisEvidence.TimeRange(subtitle.source().startUs(), subtitle.source().endUs());
    }

    @Override
    public String toString() {
      return "SynopsisSourceMaterial.VideoSubtitle[redacted]";
    }
  }

  public SynopsisSourceMaterial {
    ModelValues.identifier(filename, 255);
    ModelValues.identifier(mediaType, 100);
    if (evidence == null
        || locator == null
        || content == null
        || content.length == 0
        || content.length > 20 * 1024 * 1024) {
      throw ModelValues.invalid();
    }
    boolean valid =
        switch (locator) {
          case Page page ->
              (evidence.kind() == SynopsisEvidence.Kind.TEXT
                      && page.dimensions() == null
                      && page.regions().isEmpty())
                  || evidence.kind() == SynopsisEvidence.Kind.IMAGE_OCR;
          case Image image -> evidence.kind() == SynopsisEvidence.Kind.IMAGE;
          case Audio audio ->
              evidence.kind() == SynopsisEvidence.Kind.AUDIO_TRANSCRIPT
                  && audio.time().equals(evidence.time());
          case VideoFrame video ->
              evidence.kind() == SynopsisEvidence.Kind.VIDEO_FRAME
                  && video.time().equals(evidence.time());
          case VideoTranscript video ->
              evidence.kind() == SynopsisEvidence.Kind.VIDEO_TRANSCRIPT
                  && video.time().equals(evidence.time());
          case VideoOcr video ->
              evidence.kind() == SynopsisEvidence.Kind.VIDEO_OCR
                  && video.time().equals(evidence.time());
          case VideoSubtitle video ->
              evidence.kind() == SynopsisEvidence.Kind.VIDEO_SUBTITLE
                  && video.time().equals(evidence.time())
                  && video.subtitle().physicalSegmentId().equals(evidence.id())
                  && video.subtitle().source().textSha256().equals(evidence.sha256())
                  && video
                      .subtitle()
                      .publication()
                      .sourceSha256()
                      .equals(ModelValues.sha256(content));
        };
    boolean needsFrame =
        evidence.kind() == SynopsisEvidence.Kind.VIDEO_FRAME
            || evidence.kind() == SynopsisEvidence.Kind.VIDEO_OCR;
    if (!valid
        || needsFrame != (frame != null)
        || (evidence.kind() == SynopsisEvidence.Kind.VIDEO_FRAME
            && !evidence.sha256().equals(frame.sha256()))
        || (evidence.kind() == SynopsisEvidence.Kind.IMAGE
            && !evidence.sha256().equals(ModelValues.sha256(content)))) {
      throw ModelValues.invalid();
    }
    content = content.clone();
  }

  private static List<ImageTextRegion> checkedRegions(
      ImageDimensions dimensions, List<ImageTextRegion> regions, int start, int end) {
    if (regions == null) {
      throw ModelValues.invalid();
    }
    for (var region : regions) {
      if (region == null
          || region.start() < 0
          || region.start() >= region.end()
          || region.start() >= end
          || region.end() <= start
          || region.left() < 0
          || region.top() < 0
          || region.right() <= region.left()
          || region.bottom() <= region.top()
          || (dimensions != null
              && (region.right() > dimensions.width() || region.bottom() > dimensions.height()))) {
        throw ModelValues.invalid();
      }
    }
    return List.copyOf(regions);
  }

  @Override
  public byte[] content() {
    return content.clone();
  }

  @Override
  public String toString() {
    return "SynopsisSourceMaterial[redacted]";
  }
}
