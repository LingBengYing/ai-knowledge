package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.*;

import com.evidence.rag.client.model.AudioModels;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.DecodedAudio;
import com.evidence.rag.model.domain.DecodedVideo;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VideoFrame;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.worker.parser.VideoDecoder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VideoCompilationServiceTest {
  private final byte[] source = new byte[] {1, 2, 3};
  private final String sha = ModelValues.sha256(source);
  private final FakeDecoder decoder = new FakeDecoder();
  private final FakeAudio audio = new FakeAudio();
  private final FakeVision vision = new FakeVision();
  private VisualImage image;

  @BeforeEach
  void prepare() throws Exception {
    image = VisualSyntheticFixture.image("png");
    decoder.decoded = decoded(true);
  }

  @Test
  void completeFramesAndExactAlignedAudioKeepTheirOwnIdentityAndTime() {
    audio.texts = List.of("", "合成声音来自右侧", "尾部声音");
    var compiler = compiler(Duration.ofSeconds(10));
    var result = compiler.compile("synthetic.mp4", "video/mp4", source, () -> true);
    assertEquals(sha, result.sourceSha256());
    assertEquals("video-decoder-test-v1", result.decoderRevision());
    assertEquals(compiler.revision(), result.compilerRevision());
    assertEquals(2_000_000, result.timelineOriginUs());
    assertEquals(3_000_000, result.durationUs());
    assertEquals(
        List.of(0L, 1_700_000L),
        result.frames().stream().map(f -> f.frame().presentationUs()).toList());
    assertEquals(List.of(image.sha256(), image.sha256()), vision.seen);
    assertEquals(image.sha256(), result.frames().getFirst().frame().image().sha256());
    assertNotEquals(sha, image.sha256());
    assertEquals("合成画面 1", result.frames().getFirst().recall().recallText());
    assertEquals("vision-test-v1", result.frames().getFirst().recall().modelRevision());
    assertEquals(32_001, result.audio().sampleCount());
    assertEquals(
        List.of(0L, 1000L, 2000L), result.audio().spans().stream().map(s -> s.startMs()).toList());
    assertEquals(
        List.of(1000L, 2000L, 2001L), result.audio().spans().stream().map(s -> s.endMs()).toList());
    assertEquals("", result.audio().spans().getFirst().text());
    assertEquals(3, audio.calls);
    assertEquals(0, vision.proofCalls);
  }

  @Test
  void noAudioTrackStillCompilesEveryFrameWithoutAsr() {
    decoder.decoded = decoded(false);
    var result =
        compiler(Duration.ofSeconds(10)).compile("silent.mp4", "video/mp4", source, () -> true);
    assertNull(result.audio());
    assertEquals(2, result.frames().size());
    assertEquals(0, audio.calls);
    assertEquals(2, vision.seen.size());
  }

  @Test
  void allBlankAudioIsRetainedWithoutPretendingItContainsSpeech() {
    audio.texts = List.of("", " ", "");
    var result =
        compiler(Duration.ofSeconds(10)).compile("quiet.mp4", "video/mp4", source, () -> true);
    assertEquals(3, result.audio().spans().size());
    assertTrue(result.audio().spans().stream().allMatch(s -> s.text().isBlank()));
    assertEquals(2, result.frames().size());
  }

  @Test
  void initialRevocationDoesNotDecodeOrCallModels() {
    failure(
        "parser_cancelled",
        () -> compiler(Duration.ofSeconds(10)).compile("a.mp4", "video/mp4", source, () -> false));
    assertEquals(0, decoder.calls);
    assertNoModels();
  }

  @Test
  void revocationAfterFirstCaptionStopsWithoutSecondCaptionOrPartialResult() {
    var current = new AtomicBoolean(true);
    vision.after = () -> current.set(false);
    failure(
        "parser_cancelled",
        () -> compiler(Duration.ofSeconds(10)).compile("a.mp4", "video/mp4", source, current::get));
    assertEquals(1, vision.seen.size());
  }

  @Test
  void wrongParentShaOrDecoderRevisionBlocksEveryRemoteCall() {
    var original = decoder.decoded;
    decoder.decoded =
        new DecodedVideo(
            "a".repeat(64),
            original.decoderRevision(),
            original.timelineOriginUs(),
            original.durationUs(),
            original.frames(),
            null);
    failure(
        "parser_invalid_output",
        () -> compiler(Duration.ofSeconds(10)).compile("a.mp4", "video/mp4", source, () -> true));
    decoder.decoded =
        new DecodedVideo(
            sha,
            "different-decoder",
            original.timelineOriginUs(),
            original.durationUs(),
            original.frames(),
            null);
    failure(
        "parser_invalid_output",
        () -> compiler(Duration.ofSeconds(10)).compile("a.mp4", "video/mp4", source, () -> true));
    assertNoModels();
  }

  @Test
  void actualFrameHeaderMustMatchAllDeclaredDimensionsBeforeAnyModelCall() {
    decoder.decoded =
        new DecodedVideo(
            sha,
            decoder.revision(),
            0,
            3_000_000,
            List.of(
                new VideoFrame(0, 0, 100_000, image, 640, 320),
                new VideoFrame(1, 2_000_000, 100_000, image, 640, 321)),
            null);
    failure(
        "parser_invalid_output",
        () -> compiler(Duration.ofSeconds(10)).compile("a.mp4", "video/mp4", source, () -> true));
    assertNoModels();
  }

  @Test
  void invalidFrameBytesNeverBecomeRecall() {
    decoder.decoded =
        new DecodedVideo(
            sha,
            decoder.revision(),
            0,
            3_000_000,
            List.of(
                new VideoFrame(0, 0, 100_000, new VisualImage("image/png", new byte[] {1}), 1, 1)),
            null);
    failure(
        "parser_invalid_output",
        () -> compiler(Duration.ofSeconds(10)).compile("a.mp4", "video/mp4", source, () -> true));
    assertNoModels();
  }

  @Test
  void failedFinalCaptionIsNotRetriedOrPublishedPartially() {
    vision.failOn = 2;
    var error =
        assertThrows(
            TextModels.Failure.class,
            () ->
                compiler(Duration.ofSeconds(10)).compile("a.mp4", "video/mp4", source, () -> true));
    assertEquals("model_unavailable", error.code());
    assertEquals(2, vision.seen.size());
  }

  @Test
  void failedAudioTailDoesNotGenerateCaptionsOrRetry() {
    audio.failOn = 3;
    assertThrows(
        TextModels.Failure.class,
        () -> compiler(Duration.ofSeconds(10)).compile("a.mp4", "video/mp4", source, () -> true));
    assertEquals(3, audio.calls);
    assertTrue(vision.seen.isEmpty());
  }

  @Test
  void profileChangeAfterDescriptionRejectsItsOutput() {
    vision.after = () -> vision.revision = "vision-test-v2";
    failure(
        "video_profile_changed",
        () -> compiler(Duration.ofSeconds(10)).compile("a.mp4", "video/mp4", source, () -> true));
    assertEquals(1, vision.seen.size());
  }

  @Test
  void asrProfileChangeAfterTranscriptionStillInvalidatesVideoCompilation() {
    vision.after = () -> audio.revision = "asr-test-v2";
    failure(
        "audio_profile_changed",
        () -> compiler(Duration.ofSeconds(10)).compile("a.mp4", "video/mp4", source, () -> true));
    assertEquals(1, vision.seen.size());
  }

  @Test
  void wholeBudgetIncludesDecodeAndStopsBeforeRemote() {
    decoder.after = () -> pause(30);
    failure(
        "parser_timeout",
        () -> compiler(Duration.ofMillis(10)).compile("a.mp4", "video/mp4", source, () -> true));
    assertNoModels();
  }

  @Test
  void outerDeadlineAfterAsrIsNotRemappedToCancellation() {
    audio.after = () -> pause(100);
    failure(
        "parser_timeout",
        () -> compiler(Duration.ofMillis(80)).compile("a.mp4", "video/mp4", source, () -> true));
    assertEquals(1, audio.calls);
    assertTrue(vision.seen.isEmpty());
  }

  @Test
  void emptyRecallFailsTheWholeCompilation() {
    vision.blank = true;
    failure(
        "parser_invalid_output",
        () -> compiler(Duration.ofSeconds(10)).compile("a.mp4", "video/mp4", source, () -> true));
    assertEquals(1, vision.seen.size());
  }

  @Test
  void frozenCompilerRevisionBindsDecoderAsrVisionAndProtocol() {
    var first = compiler(Duration.ofSeconds(10)).revision();
    assertTrue(first.startsWith("java-video-compiler-v1:"));
    assertEquals(first, compiler(Duration.ofSeconds(20)).revision());
    vision.revision = "vision-test-v2";
    assertNotEquals(first, compiler(Duration.ofSeconds(10)).revision());
    vision.revision = "vision-test-v1";
    audio.revision = "asr-test-v2";
    assertNotEquals(first, compiler(Duration.ofSeconds(10)).revision());
    audio.revision = "asr-test-v1";
    decoder.revision = "decoder-test-v2";
    assertNotEquals(first, compiler(Duration.ofSeconds(10)).revision());
  }

  @Test
  void missingDependenciesAndInvalidBudgetsAreRejected() {
    var transcriber = new AudioTranscriptionService(audio, 1, Duration.ofSeconds(10));
    assertEquals(
        "invalid_request",
        assertThrows(
                ApplicationException.class,
                () -> new VideoCompilationService(null, transcriber, vision, Duration.ofSeconds(1)))
            .code());
    assertEquals(
        "invalid_request",
        assertThrows(
                ApplicationException.class,
                () -> new VideoCompilationService(decoder, null, vision, Duration.ofSeconds(1)))
            .code());
    assertEquals(
        "invalid_request",
        assertThrows(
                ApplicationException.class,
                () ->
                    new VideoCompilationService(decoder, transcriber, null, Duration.ofSeconds(1)))
            .code());
    assertEquals(
        "invalid_request",
        assertThrows(
                ApplicationException.class,
                () ->
                    new VideoCompilationService(decoder, transcriber, vision, Duration.ofMillis(9)))
            .code());
    assertEquals(
        "invalid_request",
        assertThrows(
                ApplicationException.class,
                () ->
                    new VideoCompilationService(
                        decoder, transcriber, vision, Duration.ofMinutes(11)))
            .code());
  }

  private VideoCompilationService compiler(Duration budget) {
    return new VideoCompilationService(
        decoder, new AudioTranscriptionService(audio, 1, Duration.ofSeconds(10)), vision, budget);
  }

  private DecodedVideo decoded(boolean withAudio) {
    return new DecodedVideo(
        sha,
        decoder.revision(),
        2_000_000,
        3_000_000,
        List.of(
            new VideoFrame(0, 0, 100_000, image, 640, 320),
            new VideoFrame(1, 1_700_000, 100_000, image, 640, 320)),
        withAudio ? new DecodedAudio(sha, decoder.revision(), new byte[64_002]) : null);
  }

  private void assertNoModels() {
    assertEquals(0, audio.calls);
    assertTrue(vision.seen.isEmpty());
  }

  private static void failure(String code, org.junit.jupiter.api.function.Executable operation) {
    assertEquals(code, assertThrows(TextParser.Failure.class, operation).code());
  }

  private static void pause(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError(failure);
    }
  }

  private static final class FakeDecoder implements VideoDecoder {
    private String revision = "video-decoder-test-v1";
    private DecodedVideo decoded;
    private int calls;
    private Runnable after = () -> {};

    public DecodedVideo decode(String name, String mime, byte[] bytes) {
      calls++;
      after.run();
      return decoded;
    }

    public String revision() {
      return revision;
    }

    public void close() {}
  }

  private static final class FakeAudio implements AudioModels {
    private String revision = "asr-test-v1";
    private List<String> texts = List.of("合成一", "合成二", "合成三");
    private int calls;
    private int failOn;
    private Runnable after = () -> {};

    public Transcript transcribe(byte[] wav) {
      calls++;
      if (calls == failOn) {
        throw new TextModels.Failure("model_unavailable");
      }
      after.run();
      return new Transcript(texts.get(calls - 1));
    }

    public String revision() {
      return revision;
    }

    public void close() {}
  }

  private static final class FakeVision implements VisionModels {
    private String revision = "vision-test-v1";
    private final List<String> seen = new ArrayList<>();
    private int proofCalls;
    private int failOn;
    private boolean blank;
    private Runnable after = () -> {};

    public Description describe(VisualImage input) {
      seen.add(input.sha256());
      if (seen.size() == failOn) {
        throw new TextModels.Failure("model_unavailable");
      }
      after.run();
      return new Description(blank ? "" : "合成画面 " + seen.size());
    }

    public Draft draft(String question, VisualImage input) {
      proofCalls++;
      throw new AssertionError("Compiler captions must not be treated as proof");
    }

    public Verification verify(String question, VisualImage input, List<String> claims) {
      proofCalls++;
      throw new AssertionError("Compiler captions must not be treated as proof");
    }

    public String revision() {
      return revision;
    }
  }
}
