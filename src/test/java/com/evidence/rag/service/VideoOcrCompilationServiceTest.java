package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.AudioModels;
import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.model.domain.DecodedVideo;
import com.evidence.rag.model.domain.ImageDimensions;
import com.evidence.rag.model.domain.ImageTextRegion;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.ParsedImage;
import com.evidence.rag.model.domain.ParsedText;
import com.evidence.rag.model.domain.TextPage;
import com.evidence.rag.model.domain.TextSegment;
import com.evidence.rag.model.domain.VideoFrame;
import com.evidence.rag.model.domain.VideoOcrSegment;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.worker.parser.ImageOcr;
import com.evidence.rag.worker.parser.VideoDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VideoOcrCompilationServiceTest {
  private final byte[] source = {1, 2, 3};
  private VisualImage image;
  private final FakeOcr ocr = new FakeOcr();
  private final FakeVision vision = new FakeVision();
  private VideoDecoder decoder;
  private AudioTranscriptionService transcriber;

  @BeforeEach
  void prepare() throws Exception {
    image = VisualSyntheticFixture.image("png");
    decoder =
        new VideoDecoder() {
          public String revision() {
            return "decoder-test-v1";
          }

          public DecodedVideo decode(String name, String mime, byte[] bytes) {
            return new DecodedVideo(
                ModelValues.sha256(source),
                revision(),
                2_000_000,
                3_000_000,
                List.of(
                    new VideoFrame(0, 0, 100_000, image, 640, 320),
                    new VideoFrame(1, 1_700_000, 100_000, image, 640, 320)),
                null);
          }

          public void close() {}
        };
    transcriber =
        new AudioTranscriptionService(
            new AudioModels() {
              public String revision() {
                return "asr-test-v1";
              }

              public Transcript transcribe(byte[] wav) {
                throw new AssertionError("Silent fixture has no audio");
              }

              public void close() {}
            },
            1,
            Duration.ofSeconds(10));
    ocr.results = List.of(Optional.of(parsed()), Optional.empty());
  }

  @Test
  void everyRealFrameIsProcessedOnceAndBlankFrameIsExplicitlyComplete() {
    var compiler = compiler(Duration.ofSeconds(10));
    var result = compiler.compile("synthetic.mp4", "video/mp4", source, () -> true);
    assertNotNull(result.ocr());
    assertEquals(ocr.revision(), result.ocr().ocrRevision());
    assertEquals(List.of(image.sha256(), image.sha256()), ocr.seen);
    var first = result.ocr().frames().getFirst();
    assertEquals(0, first.frameOrdinal());
    assertEquals(image.sha256(), first.frameSha256());
    assertEquals(new ImageDimensions(640, 320), first.dimensions());
    assertEquals("预算😀 42\n", first.text());
    assertEquals(List.of(new VideoOcrSegment(0, 0, 6, "预算😀 42")), first.segments());
    assertEquals(2, first.regions().size());
    var blank = result.ocr().frames().getLast();
    assertEquals(1, blank.frameOrdinal());
    assertEquals(image.sha256(), blank.frameSha256());
    assertEquals("", blank.text());
    assertTrue(blank.segments().isEmpty());
    assertTrue(blank.regions().isEmpty());
    assertEquals(1_700_000, result.frames().getLast().frame().presentationUs());
    assertEquals(100_000, result.frames().getLast().frame().durationUs());
    assertEquals(2, vision.calls);
    assertNull(result.audio());
  }

  @Test
  void ocrRevisionIsBoundToV2WhileLegacyConstructorRetainsExactV1Fingerprint() {
    var legacy = new VideoCompilationService(decoder, transcriber, vision, Duration.ofSeconds(10));
    var hash =
        ModelValues.sha256(
            (decoder.revision()
                    + "\0"
                    + transcriber.revision()
                    + "\0"
                    + vision.revision()
                    + "\0actual-frame-pts-png+aligned-pcm16k+recall-only")
                .getBytes(StandardCharsets.UTF_8));
    assertEquals("java-video-compiler-v1:" + hash, legacy.revision());
    assertNull(legacy.compile("a.mp4", "video/mp4", source, () -> true).ocr());
    assertTrue(ocr.seen.isEmpty());
    var first = compiler(Duration.ofSeconds(10)).revision();
    assertTrue(first.startsWith("java-video-compiler-v2:"));
    assertEquals(first, compiler(Duration.ofSeconds(20)).revision());
    ocr.revision = "ocr-test-v2";
    assertNotEquals(first, compiler(Duration.ofSeconds(10)).revision());
  }

  @Test
  void revocationAfterOcrCannotReturnPartialCompilationOrProcessAnotherFrame() {
    var current = new AtomicBoolean(true);
    ocr.after = () -> current.set(false);
    failure(
        "parser_cancelled",
        () -> compiler(Duration.ofSeconds(10)).compile("a.mp4", "video/mp4", source, current::get));
    assertEquals(1, ocr.seen.size());
  }

  @Test
  void changingOcrRevisionAfterReadRejectsItsResult() {
    ocr.after = () -> ocr.revision = "ocr-test-v2";
    failure(
        "video_profile_changed",
        () -> compiler(Duration.ofSeconds(10)).compile("a.mp4", "video/mp4", source, () -> true));
    assertEquals(1, ocr.seen.size());
  }

  @Test
  void changedOcrRevisionBeforeDecodeDoesNotReadAnyFrame() {
    var compiler = compiler(Duration.ofSeconds(10));
    ocr.revision = "ocr-test-v2";
    failure(
        "video_profile_changed", () -> compiler.compile("a.mp4", "video/mp4", source, () -> true));
    assertTrue(ocr.seen.isEmpty());
    assertEquals(0, vision.calls);
  }

  @Test
  void wholeBudgetIncludesOcrAndDoesNotPublishPartialResult() {
    ocr.after =
        () -> {
          try {
            Thread.sleep(120);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
          }
        };
    failure(
        "parser_timeout",
        () -> compiler(Duration.ofMillis(100)).compile("a.mp4", "video/mp4", source, () -> true));
    assertEquals(1, ocr.seen.size());
  }

  @Test
  void failedFinalFrameIsNotRetriedOrTreatedAsBlank() {
    ocr.failOn = 2;
    failure(
        "parser_invalid_output",
        () -> compiler(Duration.ofSeconds(10)).compile("a.mp4", "video/mp4", source, () -> true));
    assertEquals(2, ocr.seen.size());
  }

  @Test
  void wrongDimensionsAreNotAttachedToOriginalFrame() {
    var parsed = parsed();
    ocr.results =
        List.of(
            Optional.of(
                new ParsedImage(parsed.text(), new ImageDimensions(641, 320), parsed.regions())));
    failure(
        "parser_invalid_output",
        () -> compiler(Duration.ofSeconds(10)).compile("a.mp4", "video/mp4", source, () -> true));
    assertEquals(1, ocr.seen.size());
  }

  @Test
  void foreignCorpusPageCannotBecomeFrameLocalEvidence() {
    var parsed = parsed();
    ocr.results =
        List.of(
            Optional.of(
                new ParsedImage(
                    new ParsedText(
                        List.of(new TextPage(2, "预算😀 42\n")),
                        List.of(new TextSegment(0, 2, 0, 6, "预算😀 42"))),
                    parsed.dimensions(),
                    parsed.regions())));
    failure(
        "parser_invalid_output",
        () -> compiler(Duration.ofSeconds(10)).compile("a.mp4", "video/mp4", source, () -> true));
  }

  @Test
  void presentButEmptyOrNullResultIsMalformedNotProcessedBlank() {
    ocr.results =
        List.of(
            Optional.of(
                new ParsedImage(
                    new ParsedText(List.of(new TextPage(1, "")), List.of()),
                    new ImageDimensions(640, 320),
                    List.of())));
    failure(
        "parser_invalid_output",
        () -> compiler(Duration.ofSeconds(10)).compile("a.mp4", "video/mp4", source, () -> true));
    ocr.seen.clear();
    ocr.returnNull = true;
    failure(
        "parser_invalid_output",
        () -> compiler(Duration.ofSeconds(10)).compile("a.mp4", "video/mp4", source, () -> true));
  }

  private VideoCompilationService compiler(Duration budget) {
    return new VideoCompilationService(decoder, transcriber, vision, ocr, budget);
  }

  private static ParsedImage parsed() {
    return new ParsedImage(
        new ParsedText(
            List.of(new TextPage(1, "预算😀 42\n")), List.of(new TextSegment(0, 1, 0, 6, "预算😀 42"))),
        new ImageDimensions(640, 320),
        List.of(
            new ImageTextRegion(0, 3, 10, 20, 40, 50), new ImageTextRegion(4, 6, 50, 20, 80, 50)));
  }

  private static void failure(String code, org.junit.jupiter.api.function.Executable operation) {
    assertEquals(code, assertThrows(TextParser.Failure.class, operation).code());
  }

  private static final class FakeOcr implements ImageOcr {
    String revision = "ocr-test-v1";
    List<Optional<ParsedImage>> results;
    final List<String> seen = new ArrayList<>();
    Runnable after = () -> {};
    int failOn;
    boolean returnNull;

    public String revision() {
      return revision;
    }

    public Optional<ParsedImage> read(VisualImage input) {
      seen.add(input.sha256());
      if (seen.size() == failOn) {
        throw new TextParser.Failure("parser_invalid_output");
      }
      after.run();
      return returnNull ? null : results.get(seen.size() - 1);
    }
  }

  private static final class FakeVision implements VisionModels {
    int calls;

    public String revision() {
      return "vision-test-v1";
    }

    public Description describe(VisualImage image) {
      calls++;
      return new Description("Recall only, no budget fact.");
    }

    public Draft draft(String question, VisualImage image) {
      throw new AssertionError("No visual proof during compilation");
    }

    public Verification verify(String question, VisualImage image, List<String> claims) {
      throw new AssertionError("No visual proof during compilation");
    }
  }
}
