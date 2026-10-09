package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class VideoDomainTest {
  private static final String SOURCE = "a".repeat(64);
  private static final String DECODER = "synthetic-video-decoder-v1";
  private static final String COMPILER = "synthetic-video-compiler-v1";
  private static final VisualImage IMAGE =
      new VisualImage(
          "image/png",
          Base64.getDecoder()
              .decode(
                  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jvZkAAAAASUVORK5CYII="));

  @Test
  void decodedVideoRetainsRealFrameTimesOriginAndOptionalAlignedAudioImmutably() {
    var frames = new ArrayList<>(List.of(frame(0, 0, 100_000), frame(1, 1_500_000, 100_000)));
    var audio = new DecodedAudio(SOURCE, DECODER, new byte[64_002]);
    var video = new DecodedVideo(SOURCE, DECODER, 2_000_000, 2_000_001, frames, audio);
    frames.clear();
    assertEquals(2, video.frames().size());
    assertEquals(2_000_000, video.timelineOriginUs());
    assertEquals(2_000_001, video.durationUs());
    assertEquals(1_500_000, video.frames().getLast().presentationUs());
    assertEquals(100_000, video.frames().getLast().durationUs());
    assertEquals(2001, video.audio().durationMs());
    assertThrows(UnsupportedOperationException.class, () -> video.frames().clear());
    assertEquals("DecodedVideo[redacted]", video.toString());
    assertEquals("VideoFrame[redacted]", video.frames().getFirst().toString());
    assertNull(
        new DecodedVideo(SOURCE, DECODER, -2_000_000, 1000, List.of(frame(0, 0, 1000)), null)
            .audio());
  }

  @Test
  void completeVideoRetainsSilentAudioSpansAndRecallWithoutInventingAudio() {
    var first =
        new VideoFrameRecall(
            frame(0, 0, 100_000), new ImageRecall("Synthetic visible square.", "vision-v1"));
    var last =
        new VideoFrameRecall(
            frame(1, 1_500_000, 100_000),
            new ImageRecall("Synthetic visible circle.", "vision-v1"));
    var frames = new ArrayList<>(List.of(first, last));
    var audio = transcription(SOURCE, DECODER, 32_001);
    var compiled =
        new VideoCompilation(SOURCE, DECODER, COMPILER, 2_000_000, 2_000_001, frames, audio);
    frames.clear();
    assertEquals(List.of(first, last), compiled.frames());
    assertEquals(2_000_000, compiled.timelineOriginUs());
    assertEquals("", compiled.audio().spans().getFirst().text());
    assertEquals(2001, compiled.audio().durationMs());
    assertThrows(UnsupportedOperationException.class, () -> compiled.frames().clear());
    assertEquals("VideoCompilation[redacted]", compiled.toString());
    assertEquals("VideoFrameRecall[redacted]", first.toString());
    assertNull(
        new VideoCompilation(SOURCE, DECODER, COMPILER, 0, 100_000, List.of(first), null).audio());
  }

  static Stream<Arguments> invalidTimelines() {
    return Stream.of(
        Arguments.of("zero duration", 0L, List.of(frame(0, 0, 1))),
        Arguments.of("over ten minutes", 600_000_001L, List.of(frame(0, 0, 1))),
        Arguments.of("no frames", 1000L, List.of()),
        Arguments.of("nonzero first PTS", 1000L, List.of(frame(0, 1, 1))),
        Arguments.of("ordinal gap", 1000L, List.of(frame(0, 0, 1), frame(2, 5, 1))),
        Arguments.of("repeated PTS", 1000L, List.of(frame(0, 0, 1), frame(1, 0, 1))),
        Arguments.of("tail outside video", 1000L, List.of(frame(0, 0, 1), frame(1, 900, 101))),
        Arguments.of(
            "too many frames",
            1000L,
            IntStream.range(0, 129).mapToObj(index -> frame(index % 128, index, 1)).toList()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidTimelines")
  void decodedAndCompiledProductsRejectTheSameInvalidTimeline(
      String description, long durationUs, List<VideoFrame> frames) {
    assertThrows(
        ApplicationException.class,
        () -> new DecodedVideo(SOURCE, DECODER, 0, durationUs, frames, null),
        description);
    var recalled =
        frames.stream()
            .map(
                frame ->
                    new VideoFrameRecall(frame, new ImageRecall("Synthetic recall.", "vision-v1")))
            .toList();
    assertThrows(
        ApplicationException.class,
        () -> new VideoCompilation(SOURCE, DECODER, COMPILER, 0, durationUs, recalled, null),
        description);
  }

  @Test
  void framesRejectMissingBytesInvalidGeometryAndNonPositiveTiming() {
    assertThrows(ApplicationException.class, () -> new VideoFrame(0, 0, 1, null, 1, 1));
    assertThrows(ApplicationException.class, () -> new VideoFrame(-1, 0, 1, IMAGE, 1, 1));
    assertThrows(ApplicationException.class, () -> new VideoFrame(128, 0, 1, IMAGE, 1, 1));
    assertThrows(ApplicationException.class, () -> new VideoFrame(0, -1, 1, IMAGE, 1, 1));
    assertThrows(ApplicationException.class, () -> new VideoFrame(0, 0, 0, IMAGE, 1, 1));
    assertThrows(ApplicationException.class, () -> new VideoFrame(0, 0, 1, IMAGE, 0, 1));
    assertThrows(ApplicationException.class, () -> new VideoFrame(0, 0, 1, IMAGE, 4000, 3001));
    assertThrows(
        ApplicationException.class,
        () -> new VideoFrameRecall(null, new ImageRecall("Recall.", "vision-v1")));
    assertThrows(
        ApplicationException.class,
        () ->
            new VideoCompilation(
                SOURCE,
                DECODER,
                COMPILER,
                0,
                1,
                List.of(new VideoFrameRecall(frame(0, 0, 1), null)),
                null));
  }

  @Test
  void textEvidenceCompilationRetainsEveryFrameAndAudioWithoutInventingCaptions() {
    var first = new VideoFrameRecall(frame(0, 0, 100_000), null);
    var last = new VideoFrameRecall(frame(1, 1_500_000, 100_000), null);
    var frames = new ArrayList<>(List.of(first, last));
    var audio = transcription(SOURCE, DECODER, 32_001);
    var compiled =
        new VideoCompilation(
            SOURCE,
            DECODER,
            VideoCompilation.TEXT_EVIDENCE_COMPILER_PREFIX + SOURCE,
            2_000_000,
            2_000_001,
            frames,
            audio);
    frames.clear();
    assertTrue(compiled.textEvidenceOnly());
    assertEquals(List.of(first, last), compiled.frames());
    assertNull(compiled.frames().getFirst().recall());
    assertNull(compiled.frames().getLast().recall());
    assertEquals(IMAGE.sha256(), compiled.frames().getLast().frame().image().sha256());
    assertEquals(1_500_000, compiled.frames().getLast().frame().presentationUs());
    assertEquals(100_000, compiled.frames().getLast().frame().durationUs());
    assertEquals(audio, compiled.audio());
    assertEquals(2_000_000, compiled.timelineOriginUs());
    assertEquals(2_000_001, compiled.durationUs());
    assertThrows(UnsupportedOperationException.class, () -> compiled.frames().clear());
  }

  @Test
  void legacyCompilersStillRequireRecallForEveryFrame() {
    var caption = new ImageRecall("Synthetic recall.", "vision-v1");
    var first = new VideoFrameRecall(frame(0, 0, 100), caption);
    var last = new VideoFrameRecall(frame(1, 100, 100), caption);
    for (int version : List.of(1, 2, 3)) {
      String compiler = "java-video-compiler-v" + version + ":" + SOURCE;
      assertEquals(
          List.of(first, last),
          new VideoCompilation(SOURCE, DECODER, compiler, 0, 200, List.of(first, last), null)
              .frames());
      for (var incomplete :
          List.of(
              List.of(new VideoFrameRecall(first.frame(), null), last),
              List.of(first, new VideoFrameRecall(last.frame(), null)),
              List.of(
                  new VideoFrameRecall(first.frame(), null),
                  new VideoFrameRecall(last.frame(), null)))) {
        assertThrows(
            ApplicationException.class,
            () -> new VideoCompilation(SOURCE, DECODER, compiler, 0, 200, incomplete, null));
      }
    }
  }

  @Test
  void textEvidenceModeRejectsMixedCaptionsAndMalformedCompilerIdentity() {
    var caption = new ImageRecall("Synthetic recall.", "vision-v1");
    var first = new VideoFrameRecall(frame(0, 0, 100), null);
    var last = new VideoFrameRecall(frame(1, 100, 100), null);
    String compiler = VideoCompilation.TEXT_EVIDENCE_COMPILER_PREFIX + SOURCE;
    for (var captions :
        List.of(
            List.of(new VideoFrameRecall(first.frame(), caption), last),
            List.of(first, new VideoFrameRecall(last.frame(), caption)),
            List.of(
                new VideoFrameRecall(first.frame(), caption),
                new VideoFrameRecall(last.frame(), caption)))) {
      assertThrows(
          ApplicationException.class,
          () -> new VideoCompilation(SOURCE, DECODER, compiler, 0, 200, captions, null));
    }
    for (String invalid :
        List.of(
            VideoCompilation.TEXT_EVIDENCE_COMPILER_PREFIX,
            VideoCompilation.TEXT_EVIDENCE_COMPILER_PREFIX + "a".repeat(63),
            VideoCompilation.TEXT_EVIDENCE_COMPILER_PREFIX + "g".repeat(64))) {
      assertThrows(
          ApplicationException.class,
          () -> new VideoCompilation(SOURCE, DECODER, invalid, 0, 200, List.of(first, last), null));
    }
  }

  @Test
  void bothProductsRejectForeignOrOverlongAudioAndInvalidSourceVersions() {
    var frames = List.of(frame(0, 0, 1));
    var recalled =
        List.of(new VideoFrameRecall(frames.getFirst(), new ImageRecall("Recall.", "vision-v1")));
    for (var audio :
        List.of(
            new DecodedAudio("b".repeat(64), DECODER, new byte[32]),
            new DecodedAudio(SOURCE, "other-decoder", new byte[32]),
            new DecodedAudio(SOURCE, DECODER, new byte[64]))) {
      assertThrows(
          ApplicationException.class,
          () -> new DecodedVideo(SOURCE, DECODER, 0, 1000, frames, audio));
    }
    for (var audio :
        List.of(
            transcription("b".repeat(64), DECODER, 16),
            transcription(SOURCE, "other-decoder", 16),
            transcription(SOURCE, DECODER, 32))) {
      assertThrows(
          ApplicationException.class,
          () -> new VideoCompilation(SOURCE, DECODER, COMPILER, 0, 1000, recalled, audio));
    }
    assertThrows(
        ApplicationException.class,
        () -> new DecodedVideo("invalid", DECODER, 0, 1000, frames, null));
    assertThrows(
        ApplicationException.class, () -> new DecodedVideo(SOURCE, "", 0, 1000, frames, null));
    assertThrows(
        ApplicationException.class,
        () -> new VideoCompilation(SOURCE, DECODER, "", 0, 1000, recalled, null));
  }

  @Test
  void bothProductsBoundTotalFrameBytesWithoutDroppingSelectedFrames() {
    var large = new VisualImage("image/png", Arrays.copyOf(IMAGE.content(), 9 * 1024 * 1024));
    var frames =
        IntStream.range(0, 4)
            .mapToObj(index -> new VideoFrame(index, index, 1, large, 1, 1))
            .toList();
    assertThrows(
        ApplicationException.class, () -> new DecodedVideo(SOURCE, DECODER, 0, 1000, frames, null));
    var recalled =
        frames.stream()
            .map(frame -> new VideoFrameRecall(frame, new ImageRecall("Recall.", "vision-v1")))
            .toList();
    assertThrows(
        ApplicationException.class,
        () -> new VideoCompilation(SOURCE, DECODER, COMPILER, 0, 1000, recalled, null));
  }

  private static VideoFrame frame(int ordinal, long presentationUs, long durationUs) {
    return new VideoFrame(ordinal, presentationUs, durationUs, IMAGE, 1, 1);
  }

  private static AudioTranscription transcription(String source, String decoder, long samples) {
    long duration = (samples + 15) / 16;
    var spans =
        duration > 1000
            ? List.of(
                new AudioTranscriptSpan(0, 0, 1000, ""),
                new AudioTranscriptSpan(1, 1000, duration, "Synthetic transcript."))
            : List.of(new AudioTranscriptSpan(0, 0, duration, ""));
    return new AudioTranscription(source, decoder, "asr-v1", "transcription-v1", samples, spans);
  }
}
