package com.evidence.rag.model.domain;

import java.util.List;

/** One processed original frame, including explicit complete no-text outcomes. */
public record VideoFrameOcr(
    int frameOrdinal,
    String frameSha256,
    ImageDimensions dimensions,
    String text,
    List<VideoOcrSegment> segments,
    List<ImageTextRegion> regions) {
  public VideoFrameOcr {
    if (frameOrdinal < 0
        || frameOrdinal >= 128
        || frameSha256 == null
        || !frameSha256.matches("[a-f0-9]{64}")
        || dimensions == null
        || dimensions.width() < 1
        || dimensions.height() < 1
        || (long) dimensions.width() * dimensions.height() > 12_000_000
        || text == null
        || text.codePointCount(0, text.length()) > 1_000_000
        || segments == null
        || segments.size() > 4096
        || regions == null
        || regions.size() > 50_000) {
      throw ModelValues.invalid();
    }
    int[] points = text.codePoints().toArray();
    for (int point : points) {
      if ((Character.isISOControl(point) && point != '\n' && point != '\t' && point != '\f')
          || (point >= 0xD800 && point <= 0xDFFF)) {
        throw ModelValues.invalid();
      }
    }
    if (text.isEmpty()) {
      if (!segments.isEmpty() || !regions.isEmpty()) {
        throw ModelValues.invalid();
      }
    } else {
      if (segments.isEmpty() || regions.isEmpty()) {
        throw ModelValues.invalid();
      }
      validateSegments(points, segments);
      validateRegions(points, regions, dimensions);
    }
    segments = List.copyOf(segments);
    regions = List.copyOf(regions);
  }

  private static void validateSegments(int[] points, List<VideoOcrSegment> segments) {
    int covered = 0;
    int previousStart = -1;
    long segmentPoints = 0;
    for (int ordinal = 0; ordinal < segments.size(); ordinal++) {
      var segment = segments.get(ordinal);
      if (segment == null
          || segment.ordinal() != ordinal
          || segment.start() <= previousStart
          || segment.end() > points.length
          || !segment
              .text()
              .equals(new String(points, segment.start(), segment.end() - segment.start()))) {
        throw ModelValues.invalid();
      }
      whitespaceOnly(points, covered, segment.start());
      covered = Math.max(covered, segment.end());
      previousStart = segment.start();
      segmentPoints += segment.end() - segment.start();
      if (segmentPoints > 1_500_000) {
        throw ModelValues.invalid();
      }
    }
    whitespaceOnly(points, covered, points.length);
  }

  private static void validateRegions(
      int[] points, List<ImageTextRegion> regions, ImageDimensions dimensions) {
    int covered = 0;
    for (var region : regions) {
      if (region == null
          || region.start() < covered
          || region.end() <= region.start()
          || region.end() > points.length
          || region.left() < 0
          || region.top() < 0
          || region.right() <= region.left()
          || region.bottom() <= region.top()
          || region.right() > dimensions.width()
          || region.bottom() > dimensions.height()) {
        throw ModelValues.invalid();
      }
      whitespaceOnly(points, covered, region.start());
      for (int index = region.start(); index < region.end(); index++) {
        if (space(points[index])) {
          throw ModelValues.invalid();
        }
      }
      covered = region.end();
    }
    whitespaceOnly(points, covered, points.length);
  }

  private static void whitespaceOnly(int[] points, int start, int end) {
    for (int index = start; index < end; index++) {
      if (!space(points[index])) {
        throw ModelValues.invalid();
      }
    }
  }

  static boolean space(int point) {
    return Character.isWhitespace(point) || Character.isSpaceChar(point);
  }

  @Override
  public String toString() {
    return "VideoFrameOcr[redacted]";
  }
}
