package com.evidence.rag.worker.parser;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.tool.parser.TextParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProcessVideoDecoderTest {
  @TempDir Path directory;

  @Test
  void actualFrameEpochPngPairingAndLongerAudioTailAreReturnedTogether() throws Exception {
    byte[] original = original();
    try (var decoder = decoder("audio-tail", Duration.ofSeconds(5), 2)) {
      var decoded = decoder.decode("synthetic.mp4", "video/mp4", original);
      assertEquals(ModelValues.sha256(original), decoded.sourceSha256());
      assertEquals(decoder.revision(), decoded.decoderRevision());
      assertEquals(2_000_000, decoded.timelineOriginUs());
      // The video ends at normalized 3s; its real audio tail continues to 4s.
      assertEquals(4_000_000, decoded.durationUs());
      assertEquals(
          List.of(0L, 1_500_000L, 2_500_000L),
          decoded.frames().stream().map(frame -> frame.presentationUs()).toList());
      assertEquals(
          List.of(500_000L, 1_000_000L, 500_000L),
          decoded.frames().stream().map(frame -> frame.durationUs()).toList());
      for (int index = 0; index < 3; index++) {
        var frame = decoded.frames().get(index);
        assertEquals(index, frame.ordinal());
        assertEquals(2, frame.width());
        assertEquals(1, frame.height());
        assertArrayEquals(png(index == 0 ? 0xffff0000 : 0xff0000ff), frame.image().content());
      }
      assertEquals(decoded.sourceSha256(), decoded.audio().sourceSha256());
      assertEquals(decoder.revision(), decoded.audio().decoderRevision());
      assertArrayEquals(alignedPcm(), decoded.audio().pcm());
      assertEquals(4000, decoded.audio().durationMs());
    }
  }

  @Test
  void videoWithoutAudioStillReturnsCompleteVisualTimeline() throws Exception {
    try (var decoder = decoder("silent", Duration.ofSeconds(5), 2)) {
      var decoded = decoder.decode("synthetic.mp4", "video/mp4", original());
      assertEquals(3_000_000, decoded.durationUs());
      assertEquals(3, decoded.frames().size());
      assertNull(decoded.audio());
    }
  }

  @Test
  void fractionalTimeBasePreservesNonzeroEpochAndOutwardRoundedFrameIntervals() throws Exception {
    try (var decoder = decoder("fractional-timebase", Duration.ofSeconds(5), 2)) {
      var decoded = decoder.decode("synthetic.mp4", "video/mp4", original());
      assertEquals(66_666, decoded.timelineOriginUs());
      assertEquals(100_000, decoded.durationUs());
      assertEquals(
          List.of(0L, 50_000L, 83_333L),
          decoded.frames().stream().map(frame -> frame.presentationUs()).toList());
      assertEquals(
          List.of(16_667L, 33_334L, 16_667L),
          decoded.frames().stream().map(frame -> frame.durationUs()).toList());
      assertNull(decoded.audio());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing-duration", "png-mismatch", "too-many-frames"})
  void incompleteFrameOutputsFailAsAWhole(String mode) throws Exception {
    try (var decoder = decoder(mode, Duration.ofSeconds(5), 2)) {
      assertEquals(
          "parser_invalid_output",
          assertThrows(
                  TextParser.Failure.class,
                  () -> decoder.decode("synthetic.mp4", "video/mp4", original()))
              .code());
    }
  }

  @Test
  void audioBeforeVideoEpochIsRejectedInsteadOfTrimmingRealSamples() throws Exception {
    try (var decoder = decoder("early-audio", Duration.ofSeconds(5), 2)) {
      assertEquals(
          "unsupported_document",
          assertThrows(
                  TextParser.Failure.class,
                  () -> decoder.decode("synthetic.mp4", "video/mp4", original()))
              .code());
    }
  }

  @Test
  void timeoutConfirmsTheNativeProbeHasExited() throws Exception {
    try (var decoder = decoder("waiting", Duration.ofSeconds(1), 2)) {
      assertEquals(
          "parser_timeout",
          assertThrows(
                  TextParser.Failure.class,
                  () -> decoder.decode("synthetic.mp4", "video/mp4", original()))
              .code());
      long pid = Long.parseLong(Files.readString(directory.resolve("pid")));
      assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }
  }

  @Test
  void selectionIntervalIsFrozenIntoDecoderRevision() throws Exception {
    try (var first = decoder("silent", Duration.ofSeconds(5), 1);
        var second = decoder("silent", Duration.ofSeconds(5), 2)) {
      assertTrue(first.revision().matches("java-video-decoder-v1:[a-f0-9]{64}"));
      assertNotEquals(first.revision(), second.revision());
    }
  }

  private ProcessVideoDecoder decoder(String mode, Duration deadline, int interval)
      throws Exception {
    Path java = Path.of(System.getProperty("java.home"), "bin", "java").toRealPath();
    return new ProcessVideoDecoder(
        java,
        java,
        deadline,
        interval,
        PipelineFixture.class.getName(),
        List.of(mode, directory.toString(), ModelValues.sha256(original())));
  }

  static byte[] original() {
    return "\u0000\u0000\u0000\u0018ftypisom\u0000\u0000\u0000\u0000isommp42"
        .getBytes(StandardCharsets.ISO_8859_1);
  }

  static byte[] png(int rgb) throws Exception {
    var image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
    image.setRGB(0, 0, rgb);
    image.setRGB(1, 0, rgb);
    var bytes = new ByteArrayOutputStream();
    try (var output = new MemoryCacheImageOutputStream(bytes)) {
      if (!ImageIO.write(image, "png", output)) {
        throw new IllegalStateException("PNG test encoder unavailable");
      }
    }
    return bytes.toByteArray();
  }

  static byte[] alignedPcm() {
    var bytes = ByteBuffer.allocate(128_000).order(ByteOrder.LITTLE_ENDIAN);
    for (int index = 0; index < 64_000; index++) {
      bytes.putShort(index < 20_000 ? (short) 0 : (short) 1000);
    }
    return bytes.array();
  }

  /** Synthetic native protocol output, transported through real isolated child processes. */
  public static final class PipelineFixture {
    public static void main(String[] args) throws Exception {
      String mode = args[0];
      int ticksPerSecond = mode.equals("fractional-timebase") ? 30_000 : 1000;
      String timeBase = "1/" + ticksPerSecond;
      Path markers = Path.of(args[1]);
      var command = Arrays.asList(args).subList(4, args.length);
      if (System.getenv().keySet().stream().anyMatch(key -> !key.equals("__CF_USER_TEXT_ENCODING"))
          || !command.contains("-protocol_whitelist")
          || !command.contains("-format_whitelist")
          || command.contains("-t")
          || command.contains("-shortest")
          || command.contains("-frames:v")
          || command.stream()
              .anyMatch(value -> value.contains("atrim") || value.contains("end_sample"))) {
        System.exit(3);
      }
      Path source =
          Path.of(
              command.contains("-i") ? command.get(command.indexOf("-i") + 1) : command.getLast());
      if (!ModelValues.sha256(Files.readAllBytes(source)).equals(args[2])) {
        System.exit(4);
      }
      Files.writeString(markers.resolve("pid"), Long.toString(ProcessHandle.current().pid()));
      if (mode.equals("waiting")) {
        Thread.sleep(30_000);
      }
      if (command.contains("-show_entries") && !command.contains("-show_frames")) {
        String audio =
            mode.equals("audio-tail") || mode.equals("early-audio")
                ? ",{\"index\":1,\"codec_type\":\"audio\",\"sample_rate\":\"16000\",\"time_base\":\"1/16000\"}"
                : "";
        System.out.print(
            "{\"streams\":[{\"index\":0,\"codec_type\":\"video\",\"width\":2,\"height\":1,\"time_base\":\""
                + timeBase
                + "\"}"
                + audio
                + "],\"format\":{\"format_name\":\"mov,mp4,m4a,3gp,3g2,mj2\",\"start_time\":\"1.000000\"}}");
      } else if (command.contains("-show_frames")) {
        if (command.contains("a:0")) {
          long first = mode.equals("early-audio") ? 16_000 : 52_000;
          System.out.print(
              "{\"streams\":[{\"time_base\":\"1/16000\",\"sample_rate\":\"16000\"}],\"frames\":[{\"media_type\":\"audio\",\"pts\":"
                  + first
                  + ",\"nb_samples\":1024,\"sample_rate\":\"16000\"}]}");
        } else if (mode.equals("too-many-frames")) {
          var frames = new StringBuilder();
          for (int index = 0; index < 129; index++) {
            if (index > 0) {
              frames.append(',');
            }
            frames
                .append("{\"media_type\":\"video\",\"pts\":")
                .append(2000 + index * 10)
                .append(",\"duration\":10,\"width\":2,\"height\":1}");
          }
          System.out.print(
              "{\"streams\":[{\"time_base\":\"" + timeBase + "\"}],\"frames\":[" + frames + "]}");
        } else {
          String tail = mode.equals("missing-duration") ? "" : ",\"duration\":500";
          System.out.print(
              "{\"streams\":[{\"time_base\":\""
                  + timeBase
                  + "\"}],\"frames\":["
                  + "{\"media_type\":\"video\",\"pts\":2000,\"duration\":500,\"width\":2,\"height\":1},"
                  + "{\"media_type\":\"video\",\"pts\":2500,\"duration\":1000,\"width\":2,\"height\":1},"
                  + "{\"media_type\":\"video\",\"pts\":3500,\"duration\":1000,\"width\":2,\"height\":1},"
                  + "{\"media_type\":\"video\",\"pts\":4500,\"width\":2,\"height\":1"
                  + tail
                  + "}]}");
        }
      } else if (command.contains("image2pipe")) {
        System.err.println(
            "[Parsed_showinfo_1 @ test] config in time_base: " + timeBase + ", frame_rate: 0/1");
        int count = mode.equals("too-many-frames") ? 129 : 3;
        long[] points = {2000, 3500, 4500};
        long[] durations = {500, 1000, 500};
        for (int index = 0; index < count; index++) {
          long pts = mode.equals("too-many-frames") ? 2000 + index * 10 : points[index];
          long duration = mode.equals("too-many-frames") ? 10 : durations[index];
          System.err.println(
              "[Parsed_showinfo_1 @ test] n: "
                  + index
                  + " pts: "
                  + pts
                  + " pts_time:"
                  + pts / (double) ticksPerSecond
                  + " duration: "
                  + duration
                  + " duration_time:"
                  + duration / (double) ticksPerSecond
                  + " fmt:rgb24 s:2x1");
          if (!mode.equals("png-mismatch") || index != 2) {
            System.out.write(png(index == 0 ? 0xffff0000 : 0xff0000ff));
          }
        }
      } else if (command.contains("s16le")) {
        if (command.stream().noneMatch(value -> value.contains("first_pts=0"))
            || command.stream().anyMatch(value -> value.contains("STARTPTS"))) {
          System.exit(5);
        }
        System.out.write(alignedPcm());
      } else {
        System.exit(6);
      }
    }
  }
}
