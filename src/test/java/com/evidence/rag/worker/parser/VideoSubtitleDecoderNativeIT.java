package com.evidence.rag.worker.parser;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.AudioModels;
import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.model.domain.DecodedVideo;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VideoSubtitleCue;
import com.evidence.rag.model.domain.VideoSubtitleTrack;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.service.AudioTranscriptionService;
import com.evidence.rag.service.VideoCompilationService;
import com.evidence.rag.tool.parser.TextParser;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual embedded subtitle bytes and native timestamps; no cloud or deployed service is used. */
class VideoSubtitleDecoderNativeIT {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"mp4", "mkv", "webm"})
  void everyEmbeddedTrackAndItsRealTailRemainIndependentOfSilentOriginalFrames(String format)
      throws Exception {
    byte[] source = generate(format, "basic", false);
    try (var decoder = decoder(true)) {
      var result = decoder.decode("synthetic." + format, mime(format), source);
      assertEquals(ModelValues.sha256(source), result.sourceSha256());
      assertNull(result.audio());
      assertEquals(
          List.of(1, 2),
          result.subtitles().tracks().stream().map(VideoSubtitleTrack::streamIndex).toList());
      assertEquals(4_500_000, result.durationUs());
      assertEquals(4_500_000, result.subtitles().endUs());
      assertEquals(0, result.timelineOriginUs());
      var first = result.subtitles().tracks().getFirst();
      assertEquals("eng", first.language());
      assertEquals(
          format.equals("mp4") ? "mov_text" : format.equals("mkv") ? "subrip" : "webvtt",
          first.codec());
      var cues = nonblank(first);
      assertEquals(
          List.of("SUB-482 alpha🙂", "SUB-482 beta"),
          cues.stream().map(VideoSubtitleCue::text).toList());
      assertEquals(500_000, result.subtitles().startUs(first, cues.getFirst()));
      assertEquals(1_250_000, result.subtitles().endUs(first, cues.getFirst()));
      assertEquals(2_000_000, result.subtitles().startUs(first, cues.getLast()));
      assertEquals(3_000_000, result.subtitles().endUs(first, cues.getLast()));
      var second = result.subtitles().tracks().getLast();
      assertEquals("zho", second.language());
      assertEquals("TAIL-917 字幕尾部", nonblank(second).getFirst().text());
      assertEquals(3_500_000, result.subtitles().startUs(second, nonblank(second).getFirst()));
      if (format.equals("mp4")) {
        assertTrue(first.cues().stream().anyMatch(cue -> cue.text().isEmpty()));
        assertEquals(
            ModelValues.sha256(new byte[] {0, 0}), first.cues().getFirst().payloadSha256());
      } else {
        assertEquals(
            ModelValues.sha256("SUB-482 alpha🙂".getBytes(StandardCharsets.UTF_8)),
            cues.getFirst().payloadSha256());
      }
      assertOnlyRedFrames(result);
    }
  }

  @Test
  void fractionalVideoEpochIsSubtractedBeforeRoundingAndInitialClearPacketIsNotAFalseCue()
      throws Exception {
    byte[] source = generate("mp4", "fractional", false);
    try (var decoder = decoder(true)) {
      var result = decoder.decode("fractional.mp4", "video/mp4", source);
      var subtitles = result.subtitles();
      assertEquals(60_001, subtitles.videoEpochPts());
      assertEquals(1, subtitles.videoTimeBaseNumerator());
      assertEquals(30_000, subtitles.videoTimeBaseDenominator());
      assertEquals(2_000_033, result.timelineOriginUs());
      var track = subtitles.tracks().getFirst();
      assertEquals(0, track.cues().getFirst().pts());
      assertTrue(track.cues().getFirst().text().isEmpty());
      var cue = nonblank(track).getFirst();
      assertEquals(2_500_000, cue.pts());
      assertEquals(750_000, cue.duration());
      assertEquals(499_966, subtitles.startUs(track, cue));
      assertEquals(1_249_967, subtitles.endUs(track, cue));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"mkv", "webm"})
  void actualOverlappingCueIntervalsAreNotDeduplicatedOrShortened(String format) throws Exception {
    byte[] source = generate(format, "overlap", false);
    try (var decoder = decoder(true)) {
      var result = decoder.decode("overlap." + format, mime(format), source);
      var track = result.subtitles().tracks().getFirst();
      var cues = nonblank(track);
      assertEquals(2, cues.size());
      assertEquals(500_000, result.subtitles().startUs(track, cues.getFirst()));
      assertEquals(2_250_000, result.subtitles().endUs(track, cues.getFirst()));
      assertEquals(1_000_000, result.subtitles().startUs(track, cues.getLast()));
      assertEquals(3_000_000, result.subtitles().endUs(track, cues.getLast()));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"mp4", "webm"})
  void styledPacketRetainsTextWithoutTreatingMovTextStyleAtomsAsUtf8(String format)
      throws Exception {
    byte[] source = generate(format, "styled", false);
    try (var decoder = decoder(true)) {
      var result = decoder.decode("styled." + format, mime(format), source);
      var cue = nonblank(result.subtitles().tracks().getFirst()).getFirst();
      String text = "SUB-482 样式🙂";
      assertEquals(format.equals("mp4") ? text : "<b>SUB-482</b> 样式🙂", cue.text());
      assertFalse(cue.text().contains("styl"));
      assertOnlyRedFrames(result);
    }
  }

  @Test
  void noSubtitleVideoKeepsLegacyMediaProductAndNewExplicitEmptyTrackManifest() throws Exception {
    byte[] source = generate("mp4", "none", false);
    try (var legacy = decoder(false);
        var current = decoder(true)) {
      var before = legacy.decode("plain.mp4", "video/mp4", source);
      var after = current.decode("plain.mp4", "video/mp4", source);
      assertNull(before.subtitles());
      assertNotNull(after.subtitles());
      assertTrue(after.subtitles().tracks().isEmpty());
      assertEquals(0, after.subtitles().endUs());
      assertEquals(before.durationUs(), after.durationUs());
      assertEquals(before.timelineOriginUs(), after.timelineOriginUs());
      assertEquals(before.frames().size(), after.frames().size());
      for (int index = 0; index < before.frames().size(); index++) {
        assertArrayEquals(
            before.frames().get(index).image().content(),
            after.frames().get(index).image().content());
        assertEquals(
            before.frames().get(index).presentationUs(),
            after.frames().get(index).presentationUs());
      }
    }
    byte[] withSubtitle = generate("mp4", "legacy-reject", false);
    try (var legacy = decoder(false)) {
      assertEquals(
          "unsupported_document",
          assertThrows(
                  TextParser.Failure.class,
                  () -> legacy.decode("subtitled.mp4", "video/mp4", withSubtitle))
              .code());
    }
  }

  @Test
  void nativeCompilationKeepsSubtitleOnlyFactOutOfAsrAndFrameRecall() throws Exception {
    byte[] source = generate("mp4", "compiled", true);
    var asrCalls = new AtomicInteger();
    var visionCalls = new AtomicInteger();
    AudioModels asr =
        new AudioModels() {
          public String revision() {
            return "subtitle-native-local-asr-v1";
          }

          public Transcript transcribe(byte[] wav) {
            asrCalls.incrementAndGet();
            return new Transcript("合成音调，不含字幕编号");
          }

          public void close() {}
        };
    VisionModels vision =
        new VisionModels() {
          public String revision() {
            return "subtitle-native-local-vision-v1";
          }

          public Description describe(VisualImage image) {
            visionCalls.incrementAndGet();
            return new Description("纯红色画面，不含文字");
          }

          public Draft draft(String question, VisualImage image) {
            throw new AssertionError("No proof call");
          }

          public Verification verify(String question, VisualImage image, List<String> claims) {
            throw new AssertionError("No proof call");
          }
        };
    try (var decoder = decoder(true)) {
      var compiler =
          new VideoCompilationService(
              decoder,
              new AudioTranscriptionService(asr, 2, Duration.ofSeconds(10)),
              vision,
              null,
              Duration.ofSeconds(30),
              true);
      var result = compiler.compile("compiled.mp4", "video/mp4", source, () -> true);
      assertTrue(result.compilerRevision().startsWith("java-video-compiler-v3:"));
      assertEquals(2, result.subtitles().tracks().size());
      assertEquals(
          "SUB-482 alpha🙂", nonblank(result.subtitles().tracks().getFirst()).getFirst().text());
      assertEquals(
          "TAIL-917 字幕尾部", nonblank(result.subtitles().tracks().getLast()).getFirst().text());
      assertEquals(4_500_000, result.durationUs());
      assertTrue(asrCalls.get() > 0);
      assertEquals(result.frames().size(), visionCalls.get());
      assertTrue(
          result.audio().spans().stream()
              .allMatch(
                  span -> !span.text().contains("SUB-482") && !span.text().contains("TAIL-917")));
      assertTrue(
          result.frames().stream()
              .allMatch(
                  frame ->
                      !frame.recall().recallText().contains("SUB-482")
                          && !frame.recall().recallText().contains("TAIL-917")));
    }
  }

  private ProcessVideoDecoder decoder(boolean subtitles) {
    return new ProcessVideoDecoder(
        binary("RAG_VIDEO_DECODER_IT_FFMPEG"),
        binary("RAG_VIDEO_DECODER_IT_FFPROBE"),
        Duration.ofSeconds(20),
        2,
        subtitles);
  }

  private byte[] generate(String format, String profile, boolean audio) throws Exception {
    Path folder = Files.createDirectory(directory.resolve(profile + "-" + format));
    boolean subtitles = !profile.equals("none");
    String firstText =
        profile.equals("overlap")
            ? "1\n00:00:00,500 --> 00:00:02,250\nSUB-482 alpha🙂\n\n2\n00:00:01,000 --> 00:00:03,000\nSUB-482 beta\n"
            : "1\n00:00:00,500 --> 00:00:01,250\nSUB-482 alpha🙂\n\n2\n00:00:02,000 --> 00:00:03,000\nSUB-482 beta\n";
    boolean styled = profile.equals("styled");
    if (styled) {
      firstText =
          format.equals("webm")
              ? "WEBVTT\n\ncue-name-482\n00:00.500 --> 00:01.250 line:20% position:30%\n<b>SUB-482</b> 样式🙂\n"
              : "1\n00:00:00,500 --> 00:00:01,250\n<b>SUB-482</b> 样式🙂\n";
    }
    Path first = folder.resolve(styled && format.equals("webm") ? "first.vtt" : "first.srt");
    Path second = folder.resolve("second.srt");
    Files.writeString(first, firstText, StandardCharsets.UTF_8);
    Files.writeString(
        second, "1\n00:00:03,500 --> 00:00:04,500\nTAIL-917 字幕尾部\n", StandardCharsets.UTF_8);
    var args =
        new ArrayList<>(
            List.of(
                binary("RAG_VIDEO_DECODER_IT_FFMPEG").toString(),
                "-hide_banner",
                "-nostdin",
                "-v",
                "error",
                "-n",
                "-copyts",
                "-f",
                "lavfi",
                "-i",
                "color=c=red:s=96x64:r=30:d=4"));
    boolean fractional = profile.equals("fractional");
    if (subtitles) {
      if (fractional) {
        args.addAll(List.of("-itsoffset", "2"));
      }
      args.addAll(List.of("-i", first.toString()));
      if (!styled && !fractional) {
        args.addAll(List.of("-i", second.toString()));
      }
    }
    if (audio) {
      args.addAll(List.of("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=16000:duration=4"));
    }
    args.addAll(List.of("-map", "0:v"));
    if (subtitles) {
      args.addAll(List.of("-map", "1:s"));
      if (!styled && !fractional) {
        args.addAll(List.of("-map", "2:s"));
      }
    }
    if (audio) {
      args.addAll(List.of("-map", "3:a", "-c:a", "aac"));
    }
    args.addAll(
        List.of(
            "-c:v",
            format.equals("webm") ? "libvpx-vp9" : "libx264",
            "-threads",
            "1",
            "-pix_fmt",
            "yuv420p"));
    if (!format.equals("webm")) {
      args.addAll(List.of("-bf", "0"));
    }
    if (subtitles) {
      args.addAll(
          List.of(
              "-c:s",
              format.equals("mp4")
                  ? "mov_text"
                  : format.equals("webm") ? (styled ? "copy" : "webvtt") : "subrip",
              "-metadata:s:s:0",
              "language=eng"));
      if (!styled && !fractional) {
        args.addAll(List.of("-metadata:s:s:1", "language=zho"));
      }
    }
    if (fractional) {
      args.addAll(
          List.of(
              "-vf",
              "settb=1/30000,setpts=PTS+60001",
              "-enc_time_base:v",
              "1/30000",
              "-video_track_timescale",
              "30000",
              "-movie_timescale",
              "30000",
              "-fps_mode",
              "passthrough",
              "-avoid_negative_ts",
              "disabled"));
    }
    Path output = folder.resolve("synthetic." + format);
    args.add(output.toString());
    var builder =
        new ProcessBuilder(args)
            .directory(folder.toFile())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD);
    builder.environment().clear();
    Process process = builder.start();
    process.getOutputStream().close();
    try {
      assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Synthetic mux timed out");
      assertEquals(0, process.exitValue(), "Synthetic mux failed");
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        assertTrue(process.waitFor(2, TimeUnit.SECONDS));
      }
    }
    assertFalse(process.isAlive());
    return Files.readAllBytes(output);
  }

  private static List<VideoSubtitleCue> nonblank(VideoSubtitleTrack track) {
    return track.cues().stream().filter(cue -> !cue.text().isBlank()).toList();
  }

  private static void assertOnlyRedFrames(DecodedVideo video) throws Exception {
    for (var frame : video.frames()) {
      var image = ImageIO.read(new ByteArrayInputStream(frame.image().content()));
      for (int y = 0; y < image.getHeight(); y++) {
        for (int x = 0; x < image.getWidth(); x++) {
          int rgb = image.getRGB(x, y);
          assertTrue(((rgb >> 16) & 255) > 240 && ((rgb >> 8) & 255) < 20 && (rgb & 255) < 20);
        }
      }
    }
  }

  private static String mime(String format) {
    return switch (format) {
      case "mp4" -> "video/mp4";
      case "mkv" -> "video/x-matroska";
      case "webm" -> "video/webm";
      default -> throw new AssertionError("Unsupported synthetic format");
    };
  }

  private static Path binary(String name) {
    assertEquals(
        "true",
        System.getenv("RAG_VIDEO_DECODER_IT_ENABLED"),
        "Explicit native IT opt-in required");
    String value = System.getenv(name);
    assertNotNull(value, "Explicit binary path required");
    Path path = Path.of(value);
    assertTrue(path.isAbsolute() && Files.isRegularFile(path) && Files.isExecutable(path));
    return path;
  }
}
