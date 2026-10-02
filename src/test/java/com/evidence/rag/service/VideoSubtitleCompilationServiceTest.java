package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.AudioModels;
import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.DecodedAudio;
import com.evidence.rag.model.domain.DecodedVideo;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.ParsedImage;
import com.evidence.rag.model.domain.VideoSubtitleCompilation;
import com.evidence.rag.model.domain.VideoSubtitleCue;
import com.evidence.rag.model.domain.VideoSubtitleTrack;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.support.VideoCompilationFixture;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.worker.parser.ImageOcr;
import com.evidence.rag.worker.parser.VideoDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VideoSubtitleCompilationServiceTest {
  private static final Duration BUDGET = Duration.ofSeconds(10);
  private static final byte[] SOURCE = VideoCompilationFixture.ORIGINAL;
  private final FakeDecoder decoder = new FakeDecoder();
  private final FakeVision vision = new FakeVision();
  private final FakeAudio audio = new FakeAudio();
  private final FakeOcr ocr = new FakeOcr();
  private final AudioTranscriptionService transcriber =
      new AudioTranscriptionService(audio, 1, BUDGET);

  @BeforeEach
  void prepare() {
    decoder.output = decoded(subtitles(), false);
  }

  @Test
  void enabledCompilerRetainsAllSubtitlePacketsWithoutInventingSpeechOrCaptionEvidence() {
    var service = enabled(null);
    var result = service.compile("synthetic.mp4", "video/mp4", SOURCE, () -> true);
    assertSame(decoder.output.subtitles(), result.subtitles());
    assertEquals(2, result.subtitles().tracks().size());
    assertEquals(3, result.subtitles().tracks().getFirst().cues().size());
    assertEquals("字幕唯一事实 SUB-482", result.subtitles().tracks().getFirst().cues().get(1).text());
    assertEquals("frame description only", result.frames().getFirst().recall().recallText());
    assertNull(result.audio());
    assertNull(result.ocr());
    assertEquals(1, vision.calls);
    assertEquals(0, audio.calls);
    assertEquals(0, ocr.calls);
    assertTrue(service.revision().startsWith("java-video-compiler-v3:"));
    assertEquals(service.revision(), result.compilerRevision());
  }

  @Test
  void enabledCompilerDistinguishesCompleteNoTracksFromMissingSubtitleProduct() {
    decoder.output = decoded(new VideoSubtitleCompilation(2, 1, 1, List.of()), false);
    var result = enabled(null).compile("silent.mp4", "video/mp4", SOURCE, () -> true);
    assertNotNull(result.subtitles());
    assertTrue(result.subtitles().tracks().isEmpty());
    assertEquals(1, vision.calls);
    decoder.output = decoded(null, true);
    vision.calls = 0;
    failure(() -> enabled(null).compile("missing.mp4", "video/mp4", SOURCE, () -> true));
    assertEquals(0, vision.calls);
    assertEquals(0, audio.calls);
  }

  @Test
  void oldAndExplicitlyDisabledCompilersRejectNewProductBeforeAnyModelCall() {
    decoder.output = decoded(subtitles(), true);
    var compilers =
        List.of(
            new VideoCompilationService(decoder, transcriber, vision, BUDGET),
            new VideoCompilationService(decoder, transcriber, vision, ocr, BUDGET),
            new VideoCompilationService(decoder, transcriber, vision, null, BUDGET, false),
            new VideoCompilationService(decoder, transcriber, vision, ocr, BUDGET, false));
    for (var compiler : compilers) {
      failure(() -> compiler.compile("new.mp4", "video/mp4", SOURCE, () -> true));
    }
    assertEquals(0, vision.calls);
    assertEquals(0, audio.calls);
    assertEquals(0, ocr.calls);
  }

  @Test
  void missingRequiredSubtitlesBlockOcrAndRemoteProcessingTogether() {
    decoder.output = decoded(null, true);
    failure(() -> enabled(ocr).compile("missing.mp4", "video/mp4", SOURCE, () -> true));
    assertEquals(0, vision.calls);
    assertEquals(0, audio.calls);
    assertEquals(0, ocr.calls);
  }

  @Test
  void subtitlesAndOcrRemainIndependentCompleteProducts() {
    var result = enabled(ocr).compile("both.mp4", "video/mp4", SOURCE, () -> true);
    assertSame(decoder.output.subtitles(), result.subtitles());
    assertEquals(ocr.revision(), result.ocr().ocrRevision());
    assertEquals(1, result.ocr().frames().size());
    assertEquals("", result.ocr().frames().getFirst().text());
    assertEquals(1, ocr.calls);
    assertEquals(1, vision.calls);
  }

  @Test
  void oldRevisionBytesStayExactWhileV3BindsSubtitleAndOcrProfiles() {
    String base =
        decoder.revision()
            + "\0"
            + transcriber.revision()
            + "\0"
            + vision.revision()
            + "\0actual-frame-pts-png+aligned-pcm16k+recall-only";
    var legacy = new VideoCompilationService(decoder, transcriber, vision, BUDGET);
    var legacyOcr = new VideoCompilationService(decoder, transcriber, vision, ocr, BUDGET);
    assertEquals("java-video-compiler-v1:" + sha(base), legacy.revision());
    assertEquals(
        "java-video-compiler-v2:"
            + sha(base + "\0" + ocr.revision() + "\0complete-frame-local-ocr-cp-boxes"),
        legacyOcr.revision());
    assertEquals(
        legacy.revision(),
        new VideoCompilationService(decoder, transcriber, vision, null, BUDGET, false).revision());
    assertEquals(
        legacyOcr.revision(),
        new VideoCompilationService(decoder, transcriber, vision, ocr, BUDGET, false).revision());
    var v3 = enabled(null).revision();
    var v3Ocr = enabled(ocr).revision();
    assertTrue(v3.startsWith("java-video-compiler-v3:"));
    assertTrue(v3Ocr.startsWith("java-video-compiler-v3:"));
    assertNotEquals(v3, v3Ocr);
    assertEquals(
        v3,
        new VideoCompilationService(
                decoder, transcriber, vision, null, Duration.ofSeconds(20), true)
            .revision());
    decoder.revision = "decoder-test-v3";
    assertNotEquals(v3, enabled(null).revision());
    ocr.revision = "ocr-test-v2";
    assertNotEquals(v3Ocr, enabled(ocr).revision());
  }

  @Test
  void explicitDisabledConstructorRetainsLegacyBehaviorAndNullOcrLegacyStillRejects() {
    decoder.output = decoded(null, false);
    var result =
        new VideoCompilationService(decoder, transcriber, vision, null, BUDGET, false)
            .compile("legacy.mp4", "video/mp4", SOURCE, () -> true);
    assertNull(result.subtitles());
    assertNull(result.ocr());
    assertEquals(1, vision.calls);
    assertThrows(
        ApplicationException.class,
        () -> new VideoCompilationService(decoder, transcriber, vision, null, BUDGET));
  }

  @Test
  void nativeSubtitleFailureDoesNotBecomeAnEmptySuccessfulProductOrCallModels() {
    decoder.failure = true;
    failure(() -> enabled(ocr).compile("bad.mp4", "video/mp4", SOURCE, () -> true));
    assertEquals(0, vision.calls);
    assertEquals(0, audio.calls);
    assertEquals(0, ocr.calls);
  }

  private VideoCompilationService enabled(ImageOcr selectedOcr) {
    return new VideoCompilationService(decoder, transcriber, vision, selectedOcr, BUDGET, true);
  }

  private DecodedVideo decoded(VideoSubtitleCompilation subtitles, boolean withAudio) {
    return new DecodedVideo(
        ModelValues.sha256(SOURCE),
        decoder.revision(),
        2_000_000,
        4_000_000,
        List.of(VideoCompilationFixture.frame(0, 0, 100_000).frame()),
        withAudio
            ? new DecodedAudio(ModelValues.sha256(SOURCE), decoder.revision(), new byte[32])
            : null,
        subtitles);
  }

  private static VideoSubtitleCompilation subtitles() {
    return new VideoSubtitleCompilation(
        2,
        1,
        1,
        List.of(
            new VideoSubtitleTrack(
                1,
                "mov_text",
                1,
                1000,
                null,
                List.of(
                    new VideoSubtitleCue(0, 0, 0, "", "a".repeat(64)),
                    new VideoSubtitleCue(1, 2500, 1000, "字幕唯一事实 SUB-482", "b".repeat(64)),
                    new VideoSubtitleCue(2, 3500, 0, "", "c".repeat(64)))),
            new VideoSubtitleTrack(
                2,
                "webvtt",
                1,
                1000,
                "eng",
                List.of(
                    new VideoSubtitleCue(
                        0, 2500, 1000, "<b>subtitle only SUB-482</b>", "d".repeat(64))))));
  }

  private static String sha(String text) {
    return ModelValues.sha256(text.getBytes(StandardCharsets.UTF_8));
  }

  private static void failure(Runnable action) {
    assertEquals(
        "parser_invalid_output", assertThrows(TextParser.Failure.class, action::run).code());
  }

  private static final class FakeDecoder implements VideoDecoder {
    private String revision = "decoder-test-v2";
    private DecodedVideo output;
    private boolean failure;

    public String revision() {
      return revision;
    }

    public DecodedVideo decode(String name, String mime, byte[] bytes) {
      if (failure) {
        throw new TextParser.Failure("parser_invalid_output");
      }
      return output;
    }

    public void close() {}
  }

  private static final class FakeVision implements VisionModels {
    private int calls;

    public String revision() {
      return "vision-test-v1";
    }

    public Description describe(VisualImage image) {
      calls++;
      return new Description("frame description only");
    }

    public Draft draft(String question, VisualImage image) {
      throw new AssertionError("No proof during compilation");
    }

    public Verification verify(String question, VisualImage image, List<String> claims) {
      throw new AssertionError("No proof during compilation");
    }
  }

  private static final class FakeAudio implements AudioModels {
    private int calls;

    public String revision() {
      return "asr-test-v1";
    }

    public Transcript transcribe(byte[] wav) {
      calls++;
      return new Transcript("synthetic spoken text");
    }

    public void close() {}
  }

  private static final class FakeOcr implements ImageOcr {
    private String revision = "ocr-test-v1";
    private int calls;

    public String revision() {
      return revision;
    }

    public Optional<ParsedImage> read(VisualImage image) {
      calls++;
      return Optional.empty();
    }
  }
}
