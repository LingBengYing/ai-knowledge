package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.AudioModels;
import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.model.domain.DecodedAudio;
import com.evidence.rag.model.domain.DecodedVideo;
import com.evidence.rag.model.domain.ImageDimensions;
import com.evidence.rag.model.domain.ImageTextRegion;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.ParsedImage;
import com.evidence.rag.model.domain.ParsedText;
import com.evidence.rag.model.domain.PreparedQuery;
import com.evidence.rag.model.domain.QueryAttachment;
import com.evidence.rag.model.domain.TextPage;
import com.evidence.rag.model.domain.TextSegment;
import com.evidence.rag.model.domain.VideoFrame;
import com.evidence.rag.model.domain.VideoSubtitleCompilation;
import com.evidence.rag.model.domain.VideoSubtitleCue;
import com.evidence.rag.model.domain.VideoSubtitleTrack;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.worker.parser.AudioDecoder;
import com.evidence.rag.worker.parser.ImageOcr;
import com.evidence.rag.worker.parser.VideoDecoder;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.Test;

class QueryPreparationServiceTest {
  private static final Duration BUDGET = Duration.ofSeconds(10);
  private static final String QUESTION = " 原完整问题😀，且包括最后条件？\n";
  private static final byte[] AUDIO = "RIFF0000WAVE0000".getBytes(StandardCharsets.UTF_8);
  private static final byte[] VIDEO = "0000ftypisom00000000".getBytes(StandardCharsets.UTF_8);

  @Test
  void allThreeAttachmentsKeepCompleteTextsWhileSamplingActualFirstMiddleAndLastUniquePixels() {
    var fixture = new Fixture();
    var service = fixture.service();
    var result = service.prepare(QUESTION, fixture.attachments(), () -> true);
    assertEquals(QUESTION, result.originalQuestion());
    assertTrue(result.retrievalText().startsWith(QUESTION));
    for (String text :
        List.of("image-description", "OCR文字", "audio-first", "audio-tail", "字幕首条", "字幕尾部条件")) {
      assertTrue(result.retrievalText().contains(text), text);
    }
    assertEquals(
        List.of(
            fixture.images.get(0).sha256(),
            fixture.images.get(3).sha256(),
            fixture.images.get(6).sha256()),
        result.queryImages().stream().map(VisualImage::sha256).toList());
    assertEquals(
        List.of(QueryAttachment.Kind.IMAGE, QueryAttachment.Kind.AUDIO, QueryAttachment.Kind.VIDEO),
        result.attachments().stream().map(item -> item.mediaKind()).toList());
    assertEquals(
        List.of(1, 0, 7), result.attachments().stream().map(item -> item.visualCount()).toList());
    assertEquals(
        List.of(false, false, true),
        result.attachments().stream().map(item -> item.visualSampled()).toList());
    assertEquals(
        List.of(fixture.images.getFirst().sha256()),
        result.attachments().getFirst().selectedImageSha256());
    assertEquals(
        result.queryImages().stream().map(VisualImage::sha256).toList(),
        result.attachments().getLast().selectedImageSha256());
    assertEquals(service.revision(), result.preparationRevision());
    assertTrue(
        result.attachments().stream()
            .allMatch(item -> item.contentSha256().matches("[0-9a-f]{64}")));
    assertTrue(result.attachments().stream().allMatch(item -> item.textCodePoints() > 0));
    assertEquals(4, fixture.asrCalls);
  }

  @Test
  void noAttachmentsIsExactlyTheTextOnlyContractAndNeverCallsMediaDependencies() {
    var fixture = new Fixture();
    var service = fixture.service();
    fixture.rejectCalls = true;
    var result = service.prepare(QUESTION, List.of(), () -> true);
    assertEquals(PreparedQuery.text(QUESTION), result);
    assertEquals(0, fixture.modelCalls);
    assertEquals(0, fixture.decodeCalls);
  }

  @Test
  void unsampledVideoFrameAndTailTextStillChangeTheCompleteContentHash() {
    var first = new Fixture();
    var original = first.service().prepare(QUESTION, List.of(first.video()), () -> true);
    var pixelsChanged = new Fixture();
    pixelsChanged.images.set(1, image(0xabcdef));
    var changed =
        pixelsChanged.service().prepare(QUESTION, List.of(pixelsChanged.video()), () -> true);
    assertEquals(
        original.queryImages().stream().map(VisualImage::sha256).toList(),
        changed.queryImages().stream().map(VisualImage::sha256).toList());
    assertNotEquals(
        original.attachments().getFirst().contentSha256(),
        changed.attachments().getFirst().contentSha256());
    var tailChanged = new Fixture();
    tailChanged.subtitleTail = "末尾条件已变更";
    var tail = tailChanged.service().prepare(QUESTION, List.of(tailChanged.video()), () -> true);
    assertNotEquals(
        original.attachments().getFirst().contentSha256(),
        tail.attachments().getFirst().contentSha256());
    assertTrue(tail.retrievalText().contains("末尾条件已变更"));
  }

  @Test
  void duplicateImageAttachmentsAreFullyCompiledButDoNotConsumeDuplicateMatchingSlots() {
    var fixture = new Fixture();
    var image = fixture.image();
    var result = fixture.service().prepare(QUESTION, List.of(image, image), () -> true);
    assertEquals(2, result.attachments().size());
    assertEquals(1, result.queryImages().size());
    assertFalse(result.attachments().getFirst().visualSampled());
    assertFalse(result.attachments().getLast().visualSampled());
    assertEquals(2, fixture.modelCalls);
  }

  @Test
  void completeRetrievalCapacityFailureNeverTruncatesAnAudioTail() {
    var fixture = new Fixture();
    fixture.longTranscript = true;
    assertEquals(
        "query_text_limit",
        assertThrows(
                TextParser.Failure.class,
                () -> fixture.service().prepare(QUESTION, List.of(fixture.audio()), () -> true))
            .code());
    assertEquals(2, fixture.asrCalls);
  }

  @Test
  void countBytesAndCurrentAreCheckedBeforeAnyAttachmentDisclosure() {
    var fixture = new Fixture();
    var service = fixture.service();
    assertEquals(
        "query_attachment_limit",
        assertThrows(
                TextParser.Failure.class,
                () ->
                    service.prepare(
                        QUESTION,
                        List.of(fixture.image(), fixture.image(), fixture.image(), fixture.image()),
                        () -> true))
            .code());
    var large = new QueryAttachment("large.wav", "audio/wav", new byte[11 * 1024 * 1024]);
    assertEquals(
        "query_attachment_limit",
        assertThrows(
                TextParser.Failure.class,
                () -> service.prepare(QUESTION, List.of(large, large), () -> true))
            .code());
    assertEquals(
        "parser_cancelled",
        assertThrows(
                TextParser.Failure.class,
                () -> service.prepare(QUESTION, fixture.attachments(), () -> false))
            .code());
    assertEquals(0, fixture.modelCalls);
    assertEquals(0, fixture.decodeCalls);
  }

  @Test
  void invalidImageBytesAndMidCallProfileChangeCannotReturnPreparedSuccess() {
    var fixture = new Fixture();
    var service = fixture.service();
    assertEquals(
        "unsupported_document",
        assertThrows(
                TextParser.Failure.class,
                () ->
                    service.prepare(
                        QUESTION,
                        List.of(new QueryAttachment("fake.png", "image/png", VIDEO)),
                        () -> true))
            .code());
    assertEquals(0, fixture.modelCalls);
    fixture.changeProfile = true;
    assertEquals(
        "query_profile_changed",
        assertThrows(
                TextParser.Failure.class,
                () -> service.prepare(QUESTION, List.of(fixture.image()), () -> true))
            .code());
  }

  private static VisualImage image(int color) {
    try {
      var bytes = new ByteArrayOutputStream();
      var pixels = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
      pixels.setRGB(0, 0, color);
      try (var output = new MemoryCacheImageOutputStream(bytes)) {
        ImageIO.write(pixels, "png", output);
      }
      return new VisualImage("image/png", bytes.toByteArray());
    } catch (IOException failure) {
      throw new AssertionError(failure);
    }
  }

  private static final class Fixture {
    final List<VisualImage> images = new ArrayList<>();
    int modelCalls;
    int asrCalls;
    int decodeCalls;
    boolean rejectCalls;
    boolean changeProfile;
    boolean longTranscript;
    String visionRevision = "query-vision-v1";
    String subtitleTail = "字幕尾部条件";

    Fixture() {
      for (int index = 0; index < 7; index++) {
        images.add(QueryPreparationServiceTest.image(0x110000 + index));
      }
    }

    QueryAttachment image() {
      return new QueryAttachment("private.png", "image/png", images.getFirst().content());
    }

    QueryAttachment audio() {
      return new QueryAttachment("private.wav", "audio/wav", AUDIO);
    }

    QueryAttachment video() {
      return new QueryAttachment("private.mp4", "video/mp4", VIDEO);
    }

    List<QueryAttachment> attachments() {
      return List.of(image(), audio(), video());
    }

    QueryPreparationService service() {
      var vision =
          new VisionModels() {
            public String revision() {
              return visionRevision;
            }

            public Description describe(VisualImage image) {
              if (rejectCalls) {
                throw new AssertionError("No attachment must not call models");
              }
              modelCalls++;
              if (changeProfile) {
                visionRevision = "query-vision-v2";
              }
              return new Description("image-description");
            }

            public Draft draft(String question, VisualImage image) {
              throw new AssertionError("Preparation is not proof");
            }

            public Verification verify(String question, VisualImage image, List<String> claims) {
              throw new AssertionError("Preparation is not proof");
            }
          };
      var ocr =
          new ImageOcr() {
            public String revision() {
              return "query-ocr-v1";
            }

            public Optional<ParsedImage> read(VisualImage image) {
              if (rejectCalls) {
                throw new AssertionError("No attachment must not call OCR");
              }
              return Optional.of(
                  new ParsedImage(
                      new ParsedText(
                          List.of(new TextPage(1, "OCR文字")),
                          List.of(new TextSegment(0, 1, 0, 5, "OCR文字"))),
                      new ImageDimensions(2, 2),
                      List.of(new ImageTextRegion(0, 5, 0, 0, 2, 2))));
            }
          };
      var audioModels =
          new AudioModels() {
            public String revision() {
              return "query-asr-v1";
            }

            public Transcript transcribe(byte[] wav) {
              if (rejectCalls) {
                throw new AssertionError("No attachment must not call ASR");
              }
              asrCalls++;
              return new Transcript(
                  longTranscript
                      ? "字".repeat(4096)
                      : (asrCalls % 2 == 1 ? "audio-first" : "audio-tail"));
            }

            public void close() {}
          };
      var audioDecoder =
          new AudioDecoder() {
            public String revision() {
              return "query-audio-decoder-v1";
            }

            public DecodedAudio decode(String filename, String mime, byte[] bytes) {
              decodeCalls++;
              return new DecodedAudio(ModelValues.sha256(bytes), revision(), new byte[64_000]);
            }

            public void close() {}
          };
      var videoDecoder =
          new VideoDecoder() {
            public String revision() {
              return "query-video-decoder-v1";
            }

            public DecodedVideo decode(String filename, String mime, byte[] bytes) {
              decodeCalls++;
              var frames = new ArrayList<VideoFrame>();
              for (int index = 0; index < images.size(); index++) {
                frames.add(
                    new VideoFrame(index, index * 1_000_000L, 1_000_000L, images.get(index), 2, 2));
              }
              var subtitles =
                  new VideoSubtitleCompilation(
                      0,
                      1,
                      1000,
                      List.of(
                          new VideoSubtitleTrack(
                              2,
                              "mov_text",
                              1,
                              1000,
                              "chi",
                              List.of(
                                  new VideoSubtitleCue(0, 0, 1000, "字幕首条", "a".repeat(64)),
                                  new VideoSubtitleCue(
                                      1, 7000, 1000, subtitleTail, "b".repeat(64))))));
              return new DecodedVideo(
                  ModelValues.sha256(bytes),
                  revision(),
                  0,
                  8_000_000,
                  frames,
                  new DecodedAudio(ModelValues.sha256(bytes), revision(), new byte[64_000]),
                  subtitles);
            }

            public void close() {}
          };
      return new QueryPreparationService(
          vision,
          ocr,
          new AudioCompilationService(audioDecoder, audioModels, 1, BUDGET),
          new VideoCompilationService(
              videoDecoder,
              new AudioTranscriptionService(audioModels, 1, BUDGET),
              vision,
              ocr,
              BUDGET,
              true),
          BUDGET);
    }
  }
}
