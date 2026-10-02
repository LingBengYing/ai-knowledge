package com.evidence.rag.model.domain;

import java.nio.charset.StandardCharsets;

/** Complete authority-supplied material for synopsis generation, never a recall caption. */
public record SynopsisEvidence(String id, Kind kind, Content content, TimeRange time) {
  public enum Kind {
    TEXT,
    IMAGE_OCR,
    IMAGE,
    AUDIO_TRANSCRIPT,
    VIDEO_FRAME,
    VIDEO_TRANSCRIPT,
    VIDEO_OCR,
    VIDEO_SUBTITLE
  }

  public sealed interface Content permits Text, Image {}

  public record Text(String text) implements Content {
    public Text {
      requireText(text, 64000);
    }

    @Override
    public String toString() {
      return "SynopsisEvidence.Text[redacted]";
    }
  }

  public record Image(VisualImage image) implements Content {
    public Image {
      if (image == null) {
        throw ModelValues.invalid();
      }
    }

    @Override
    public String toString() {
      return "SynopsisEvidence.Image[redacted]";
    }
  }

  /** Server-owned half-open microsecond interval, not model-predicted word alignment. */
  public record TimeRange(long startUs, long endUs) {
    public TimeRange {
      if (startUs < 0 || endUs <= startUs) {
        throw ModelValues.invalid();
      }
    }

    @Override
    public String toString() {
      return "SynopsisEvidence.TimeRange[redacted]";
    }
  }

  public SynopsisEvidence {
    ModelValues.identifier(id, 128);
    if (kind == null
        || content == null
        || ((kind == Kind.IMAGE || kind == Kind.VIDEO_FRAME) != (content instanceof Image))
        || requiresTime(kind) != (time != null)) {
      throw ModelValues.invalid();
    }
  }

  public String sha256() {
    return switch (content) {
      case Text text -> ModelValues.sha256(text.text().getBytes(StandardCharsets.UTF_8));
      case Image image -> image.image().sha256();
    };
  }

  static boolean requiresTime(Kind kind) {
    return switch (kind) {
      case AUDIO_TRANSCRIPT, VIDEO_FRAME, VIDEO_TRANSCRIPT, VIDEO_OCR, VIDEO_SUBTITLE -> true;
      case TEXT, IMAGE_OCR, IMAGE -> false;
    };
  }

  static void requireText(String value, int maximumCodePoints) {
    if (value == null
        || value.isBlank()
        || value.codePointCount(0, value.length()) > maximumCodePoints
        || value
            .codePoints()
            .anyMatch(
                c ->
                    (c >= 0xD800 && c <= 0xDFFF)
                        || (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'))) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "SynopsisEvidence[redacted]";
  }
}
