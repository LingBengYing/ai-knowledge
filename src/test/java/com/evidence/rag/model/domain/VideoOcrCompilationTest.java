package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class VideoOcrCompilationTest {
  private final String sha = "a".repeat(64);
  private final ImageDimensions dimensions = new ImageDimensions(640, 320);

  @Test
  void immutableFrameLocalUnicodeEvidenceContainsNoPageAndCopiesCollections() {
    var segments = new ArrayList<>(List.of(new VideoOcrSegment(0, 0, 4, "值😀42")));
    var regions = new ArrayList<>(List.of(new ImageTextRegion(0, 4, 1, 2, 80, 30)));
    var frame = new VideoFrameOcr(0, sha, dimensions, "值😀42\n", segments, regions);
    var frames = new ArrayList<>(List.of(frame));
    var ocr = new VideoOcrCompilation("ocr-v1", frames);
    segments.clear();
    regions.clear();
    frames.clear();
    assertEquals(1, frame.segments().size());
    assertEquals(1, frame.regions().size());
    assertEquals(1, ocr.frames().size());
    assertEquals("VideoFrameOcr[redacted]", frame.toString());
    assertEquals("VideoOcrCompilation[redacted]", ocr.toString());
    assertEquals("VideoOcrSegment[redacted]", frame.segments().getFirst().toString());
  }

  @Test
  void malformedOrIncompleteSegmentsCannotClaimCompleteText() {
    invalid(() -> new VideoOcrSegment(0, 0, 3, "值😀42"));
    invalid(
        () ->
            new VideoFrameOcr(
                0,
                sha,
                dimensions,
                "AB CD\n",
                List.of(new VideoOcrSegment(0, 0, 2, "AB")),
                List.of(
                    new ImageTextRegion(0, 2, 1, 2, 20, 30),
                    new ImageTextRegion(3, 5, 25, 2, 45, 30))));
    invalid(
        () ->
            new VideoFrameOcr(
                0,
                sha,
                dimensions,
                "AB\n",
                List.of(new VideoOcrSegment(0, 0, 2, "CD")),
                List.of(new ImageTextRegion(0, 2, 1, 2, 20, 30))));
  }

  @Test
  void missingWordsInvalidPixelBoxesAndWhitespaceInsideWordsAreRejected() {
    invalid(() -> frame("AB CD\n", List.of(new ImageTextRegion(0, 2, 1, 2, 20, 30))));
    invalid(() -> frame("AB\n", List.of(new ImageTextRegion(0, 2, 1, 2, 641, 30))));
    invalid(() -> frame("AB CD\n", List.of(new ImageTextRegion(0, 5, 1, 2, 20, 30))));
  }

  @Test
  void blankFrameMustHaveNoInventedSegmentsOrRegionsAndFramesAreConsecutive() {
    var blank = new VideoFrameOcr(0, sha, dimensions, "", List.of(), List.of());
    assertTrue(blank.text().isEmpty());
    invalid(
        () ->
            new VideoFrameOcr(
                0, sha, dimensions, "", List.of(), List.of(new ImageTextRegion(0, 1, 0, 0, 1, 1))));
    invalid(
        () ->
            new VideoOcrCompilation(
                "ocr-v1",
                List.of(new VideoFrameOcr(1, sha, dimensions, "", List.of(), List.of()))));
    invalid(() -> new VideoOcrCompilation("ocr-v1", List.of()));
  }

  @Test
  void videoCompilationRequiresOcrForEveryExactSelectedFrame() {
    var image = new VisualImage("image/png", new byte[] {1});
    var frames =
        List.of(
            new VideoFrameRecall(
                new VideoFrame(0, 0, 1, image, 640, 320), new ImageRecall("recall", "vision-v1")));
    var wrongSha =
        new VideoOcrCompilation(
            "ocr-v1", List.of(new VideoFrameOcr(0, sha, dimensions, "", List.of(), List.of())));
    invalid(() -> new VideoCompilation(sha, "decoder", "compiler", 0, 1, frames, null, wrongSha));
    var wrongSize =
        new VideoOcrCompilation(
            "ocr-v1",
            List.of(
                new VideoFrameOcr(
                    0, image.sha256(), new ImageDimensions(1, 1), "", List.of(), List.of())));
    invalid(() -> new VideoCompilation(sha, "decoder", "compiler", 0, 1, frames, null, wrongSize));
    var complete =
        new VideoOcrCompilation(
            "ocr-v1",
            List.of(new VideoFrameOcr(0, image.sha256(), dimensions, "", List.of(), List.of())));
    assertSame(
        complete,
        new VideoCompilation(sha, "decoder", "compiler", 0, 1, frames, null, complete).ocr());
    assertNull(new VideoCompilation(sha, "decoder", "compiler", 0, 1, frames, null).ocr());
  }

  private VideoFrameOcr frame(String text, List<ImageTextRegion> regions) {
    String trimmed = text.strip();
    return new VideoFrameOcr(
        0,
        sha,
        dimensions,
        text,
        List.of(new VideoOcrSegment(0, 0, trimmed.codePointCount(0, trimmed.length()), trimmed)),
        regions);
  }

  private static void invalid(org.junit.jupiter.api.function.Executable operation) {
    assertEquals("invalid_request", assertThrows(ApplicationException.class, operation).code());
  }
}
