package com.evidence.rag.model.domain;

/** Frame-local OCR evidence; offsets are Unicode code points, not corpus-page coordinates. */
public record VideoOcrSegment(int ordinal, int start, int end, String text) {
  public VideoOcrSegment {
    if (ordinal < 0
        || ordinal >= 4096
        || start < 0
        || end <= start
        || end > 1_000_000
        || end - start > 1200
        || text == null
        || text.codePointCount(0, text.length()) != end - start
        || text.codePoints().allMatch(VideoFrameOcr::space)) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "VideoOcrSegment[redacted]";
  }
}
