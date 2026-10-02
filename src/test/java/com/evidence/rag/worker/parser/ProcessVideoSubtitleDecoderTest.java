package com.evidence.rag.worker.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.tool.parser.TextParser;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class ProcessVideoSubtitleDecoderTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"mov_text", "subrip", "webvtt"})
  void allOriginalPacketsTracksAndExactVideoEpochArePreserved(String codec) throws Exception {
    try (var decoder = decoder(codec, true, Duration.ofSeconds(8))) {
      var decoded = decoder.decode("synthetic.mp4", "video/mp4", original());
      assertTrue(decoder.revision().matches("java-video-decoder-v2:[a-f0-9]{64}"));
      assertEquals(decoder.revision(), decoded.decoderRevision());
      assertEquals(2_000_033, decoded.timelineOriginUs());
      assertEquals(4_999_967, decoded.durationUs());
      assertEquals(1, decoded.frames().size());
      assertNull(decoded.audio());
      var subtitles = decoded.subtitles();
      assertEquals(60001, subtitles.videoEpochPts());
      assertEquals(1, subtitles.videoTimeBaseNumerator());
      assertEquals(30000, subtitles.videoTimeBaseDenominator());
      assertEquals(
          List.of(2, 4), subtitles.tracks().stream().map(track -> track.streamIndex()).toList());
      var first = subtitles.tracks().getFirst();
      assertEquals(codec, first.codec());
      assertEquals("eng", first.language());
      assertEquals(3, first.cues().size());
      assertEquals("", first.cues().getFirst().text());
      assertEquals(0, first.cues().getFirst().pts());
      var alpha = first.cues().get(1);
      assertEquals(1, alpha.ordinal());
      assertEquals("SUB-482 样式🙂", alpha.text());
      assertEquals(ModelValues.sha256(payload(codec, alpha.text(), true)), alpha.payloadSha256());
      assertEquals(499966, subtitles.startUs(first, alpha));
      assertEquals(1249967, subtitles.endUs(first, alpha));
      assertEquals("SUB-900 tail", first.cues().get(2).text());
      var second = subtitles.tracks().get(1);
      assertNull(second.language());
      assertEquals(2, second.cues().size());
      assertEquals(second.cues().get(0).pts(), second.cues().get(1).pts());
      assertEquals("<b>subtitle markup is data</b>", second.cues().get(0).text());
      assertEquals(List.of(0, 1), second.cues().stream().map(cue -> cue.ordinal()).toList());
      assertTrue(Files.readString(directory.resolve("phases")).contains("subtitles\nframes"));
      assertFalse(Files.exists(Path.of(Files.readString(directory.resolve("source-path")))));
    }
  }

  @Test
  void explicitSubtitleDecoderReturnsCompleteEmptyCompilationForVideoWithoutTracks()
      throws Exception {
    try (var decoder = decoder("none", true, Duration.ofSeconds(8))) {
      var decoded = decoder.decode("synthetic.mp4", "video/mp4", original());
      assertNotNull(decoded.subtitles());
      assertEquals(List.of(), decoded.subtitles().tracks());
      assertEquals(2_000_000, decoded.durationUs());
      assertEquals(
          "probe\ntimeline\nsubtitles\nframes\n", Files.readString(directory.resolve("phases")));
    }
  }

  @Test
  void legacyAndExplicitFalseKeepExactlyTheOriginalRevisionAndRejectSubtitleTracks()
      throws Exception {
    Path java = java();
    List<String> args = List.of("none", directory.toString(), ModelValues.sha256(original()));
    try (var legacy =
            new ProcessVideoDecoder(
                java, java, Duration.ofSeconds(8), 2, PipelineFixture.class.getName(), args);
        var disabled = decoder("none", false, Duration.ofSeconds(8));
        var enabled = decoder("none", true, Duration.ofSeconds(8));
        var unsupported = decoder("mov_text", false, Duration.ofSeconds(8))) {
      String protocol =
          "java-video-decoder-v1:actual-pts:scene-0.3:interval-first-real:png:video-epoch:complete-16khz-mono-s16le";
      String executable = ModelValues.sha256(Files.readAllBytes(java));
      String expected =
          "java-video-decoder-v1:"
              + ModelValues.sha256(
                  (protocol + "\u0000" + 2 + "\u0000" + executable + "\u0000" + executable)
                      .getBytes(StandardCharsets.UTF_8));
      assertEquals(expected, legacy.revision());
      assertEquals(expected, disabled.revision());
      assertNotEquals(expected, enabled.revision());
      assertNull(disabled.decode("synthetic.mp4", "video/mp4", original()).subtitles());
      assertEquals(
          "unsupported_document",
          assertThrows(
                  TextParser.Failure.class,
                  () -> unsupported.decode("synthetic.mp4", "video/mp4", original()))
              .code());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missing-track", "extra-track", "duplicate-track", "changed-codec", "changed-timebase",
        "changed-language", "unknown-packet-stream", "decimal-pts", "missing-duration",
            "negative-duration",
        "size-mismatch", "bad-hex-offset", "bad-hex", "short-mov-text", "short-style-atom",
        "style-atom-overrun", "invalid-utf8", "early-text", "too-long-tail", "missing-packets",
        "non-object", "trailing-json", "oversized-payload", "packet-budget", "text-budget"
      })
  void malformedOrIncompleteSubtitleOutputFailsBeforeFrameDecode(String mode) throws Exception {
    try (var decoder = decoder(mode, true, Duration.ofSeconds(8))) {
      assertEquals(
          "parser_invalid_output",
          assertThrows(
                  TextParser.Failure.class,
                  () -> decoder.decode("synthetic.mp4", "video/mp4", original()))
              .code());
      assertFalse(Files.exists(directory.resolve("frames")));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"unsupported-codec", "too-many-tracks", "unknown-stream"})
  void unsupportedStreamSetsAreRejectedAsAWhole(String mode) throws Exception {
    try (var decoder = decoder(mode, true, Duration.ofSeconds(8))) {
      assertEquals(
          "unsupported_document",
          assertThrows(
                  TextParser.Failure.class,
                  () -> decoder.decode("synthetic.mp4", "video/mp4", original()))
              .code());
      assertFalse(Files.exists(directory.resolve("frames")));
    }
  }

  @Test
  void subtitleProbeSharesDeadlineAndItsChildIsConfirmedExited() throws Exception {
    try (var decoder = decoder("waiting-subtitles", true, Duration.ofSeconds(2))) {
      assertEquals(
          "parser_timeout",
          assertThrows(
                  TextParser.Failure.class,
                  () -> decoder.decode("synthetic.mp4", "video/mp4", original()))
              .code());
      long pid = Long.parseLong(Files.readString(directory.resolve("subtitle-pid")));
      assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
      assertFalse(Files.exists(directory.resolve("frames")));
    }
  }

  private ProcessVideoDecoder decoder(String mode, boolean enabled, Duration deadline)
      throws Exception {
    return new ProcessVideoDecoder(
        java(),
        java(),
        deadline,
        2,
        enabled,
        PipelineFixture.class.getName(),
        List.of(mode, directory.toString(), ModelValues.sha256(original())));
  }

  private static Path java() throws Exception {
    return Path.of(System.getProperty("java.home"), "bin", "java").toRealPath();
  }

  private static byte[] original() {
    return ProcessVideoDecoderTest.original();
  }

  private static byte[] payload(String codec, String text, boolean styled) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    if (!codec.equals("mov_text")) {
      return bytes;
    }
    byte[] atom =
        styled
            ? HexFormat.of().parseHex("000000167374796c00010000000700010110ffffffff")
            : new byte[0];
    return ByteBuffer.allocate(2 + bytes.length + atom.length)
        .putShort((short) bytes.length)
        .put(bytes)
        .put(atom)
        .array();
  }

  private static String hexDump(byte[] bytes) {
    var result = new StringBuilder("\n");
    char[] hex = "0123456789abcdef".toCharArray();
    for (int offset = 0; offset < bytes.length; offset += 16) {
      String position = Integer.toHexString(offset);
      result.append("0".repeat(8 - position.length())).append(position).append(": ");
      int size = Math.min(16, bytes.length - offset);
      for (int index = 0; index < 16; index++) {
        if (index < size) {
          int value = bytes[offset + index] & 255;
          result.append(hex[value >>> 4]).append(hex[value & 15]);
        } else {
          result.append("  ");
        }
        if (index % 2 == 1) {
          result.append(' ');
        }
      }
      result.append(' ');
      for (int index = 0; index < size; index++) {
        int value = bytes[offset + index] & 255;
        result.append(value >= 32 && value <= 126 ? (char) value : '.');
      }
      result.append('\n');
    }
    return result.toString();
  }

  /** The same real child seam as native decoding, with synthetic packet protocol bytes. */
  public static final class PipelineFixture {
    public static void main(String[] args) throws Exception {
      String mode = args[0];
      Path markers = Path.of(args[1]);
      var command = Arrays.asList(args).subList(4, args.length);
      if (System.getenv().keySet().stream().anyMatch(key -> !key.equals("__CF_USER_TEXT_ENCODING"))
          || !command.contains("-protocol_whitelist")
          || !command.contains("-format_whitelist")
          || command.contains("-t")
          || command.contains("-shortest")) {
        System.exit(3);
      }
      Path source =
          Path.of(
              command.contains("-i") ? command.get(command.indexOf("-i") + 1) : command.getLast());
      if (!ModelValues.sha256(Files.readAllBytes(source)).equals(args[2])) {
        System.exit(4);
      }
      Files.writeString(markers.resolve("source-path"), source.toString());
      String codec = List.of("mov_text", "subrip", "webvtt").contains(mode) ? mode : "mov_text";
      if (command.contains("-show_packets")) {
        mark(markers, "subtitles");
        if (!command.contains("-show_data")
            || !command.contains("-show_streams")
            || !command.contains("-select_streams")
            || !command.get(command.indexOf("-select_streams") + 1).equals("s")) {
          System.exit(5);
        }
        if (mode.equals("waiting-subtitles")) {
          Files.writeString(
              markers.resolve("subtitle-pid"), Long.toString(ProcessHandle.current().pid()));
          Thread.sleep(30_000);
        }
        System.out.print(packetOutput(mode, codec));
      } else if (command.contains("-show_frames")) {
        mark(markers, "timeline");
        System.out.print(
            "{\"streams\":[{\"time_base\":\"1/30000\"}],\"frames\":[{\"pts\":60001,\"duration\":60000,\"width\":2,\"height\":1}]}");
      } else if (command.contains("-show_entries")) {
        mark(markers, "probe");
        var streams = new ArrayList<Map<String, Object>>();
        streams.add(
            new LinkedHashMap<>(
                Map.of(
                    "index",
                    0,
                    "codec_type",
                    "video",
                    "width",
                    2,
                    "height",
                    1,
                    "time_base",
                    "1/30000")));
        if (!mode.equals("none")) {
          streams.add(
              stream(2, mode.equals("unsupported-codec") ? "ass" : codec, "1/1000000", "eng"));
          streams.add(stream(4, "webvtt", "1/1000", null));
        }
        if (mode.equals("too-many-tracks")) {
          for (int index = 5; index < 8; index++) {
            streams.add(stream(index, "webvtt", "1/1000", null));
          }
        }
        if (mode.equals("unknown-stream")) {
          streams.add(new LinkedHashMap<>(Map.of("index", 8, "codec_type", "data")));
        }
        System.out.print(
            JsonMapper.builder()
                .build()
                .writeValueAsString(
                    Map.of("streams", streams, "format", Map.of("format_name", "mov,mp4"))));
      } else if (command.contains("image2pipe")) {
        mark(markers, "frames");
        Files.writeString(markers.resolve("frames"), "called");
        System.err.println(
            "[Parsed_showinfo_1 @ test] config in time_base: 1/30000, frame_rate: 0/1");
        System.err.println(
            "[Parsed_showinfo_1 @ test] n: 0 pts: 60001 pts_time:2.000033 duration: 60000 duration_time:2 fmt:rgb24 s:2x1");
        System.out.write(ProcessVideoDecoderTest.png(0xff0000ff));
      } else {
        System.exit(6);
      }
    }

    private static void mark(Path markers, String phase) throws Exception {
      Files.writeString(
          markers.resolve("phases"),
          phase + "\n",
          java.nio.file.StandardOpenOption.CREATE,
          java.nio.file.StandardOpenOption.APPEND);
    }

    private static Map<String, Object> stream(
        int index, String codec, String timeBase, String language) {
      var stream = new LinkedHashMap<String, Object>();
      stream.put("index", index);
      stream.put("codec_type", "subtitle");
      stream.put("codec_name", codec);
      stream.put("time_base", timeBase);
      if (language != null) {
        stream.put("tags", Map.of("language", language));
      }
      return stream;
    }

    private static Map<String, Object> packet(int stream, long pts, long duration, byte[] data) {
      var packet = new LinkedHashMap<String, Object>();
      packet.put("stream_index", stream);
      packet.put("pts", pts);
      packet.put("duration", duration);
      packet.put("size", Integer.toString(data.length));
      packet.put("data", hexDump(data));
      return packet;
    }

    private static String packetOutput(String mode, String codec) throws Exception {
      var streams = new ArrayList<Map<String, Object>>();
      var packets = new ArrayList<Map<String, Object>>();
      if (!mode.equals("none")) {
        streams.add(stream(2, codec, "1/1000000", "eng"));
        streams.add(stream(4, "webvtt", "1/1000", null));
        packets.add(packet(2, 0, 2_500_000, payload(codec, "", false)));
        packets.add(packet(2, 2_500_000, 750_000, payload(codec, "SUB-482 样式🙂", true)));
        packets.add(
            packet(4, 3000, 1000, payload("webvtt", "<b>subtitle markup is data</b>", false)));
        packets.add(packet(4, 3000, 1500, payload("webvtt", "overlapping second cue", false)));
        packets.add(packet(2, 6_000_000, 1_000_000, payload(codec, "SUB-900 tail", false)));
      }
      var changed = packets.isEmpty() ? new LinkedHashMap<String, Object>() : packets.get(1);
      switch (mode) {
        case "missing-track" -> streams.removeLast();
        case "extra-track" -> streams.add(stream(6, "webvtt", "1/1000", null));
        case "duplicate-track" -> streams.add(streams.getFirst());
        case "changed-codec" -> streams.getFirst().put("codec_name", "subrip");
        case "changed-timebase" -> streams.getFirst().put("time_base", "1/1000");
        case "changed-language" -> streams.getFirst().put("tags", Map.of("language", "zho"));
        case "unknown-packet-stream" -> changed.put("stream_index", 9);
        case "decimal-pts" -> changed.put("pts", 2500000.5);
        case "missing-duration" -> changed.remove("duration");
        case "negative-duration" -> changed.put("duration", -1);
        case "size-mismatch" -> changed.put("size", "1");
        case "bad-hex-offset" ->
            changed.put("data", changed.get("data").toString().replace("00000000:", "00000001:"));
        case "bad-hex" ->
            changed.put("data", changed.get("data").toString().replaceFirst("5355", "zzzz"));
        case "short-mov-text" -> replace(changed, new byte[] {0, 8, 65});
        case "short-style-atom" -> replace(changed, new byte[] {0, 1, 65, 0, 0, 0});
        case "style-atom-overrun" ->
            replace(changed, new byte[] {0, 1, 65, 0, 0, 0, 16, 115, 116, 121, 108});
        case "invalid-utf8" -> replace(changed, new byte[] {0, 1, (byte) 0xff});
        case "early-text" -> changed.put("pts", 1);
        case "too-long-tail" -> packets.getLast().put("pts", 602_000_034L);
        case "oversized-payload" -> replace(changed, new byte[2 * 1024 * 1024 + 1]);
        case "packet-budget" -> {
          while (packets.size() <= 2048) {
            packets.add(packet(2, 7_000_000, 1, payload(codec, "x", false)));
          }
        }
        case "text-budget" -> replace(changed, payload(codec, "x".repeat(4097), false));
        default -> {}
      }
      if (mode.equals("non-object")) {
        return "[]";
      }
      var root = new LinkedHashMap<String, Object>();
      root.put("streams", streams);
      if (!mode.equals("missing-packets")) {
        root.put("packets", packets);
      }
      String value = JsonMapper.builder().build().writeValueAsString(root);
      return mode.equals("trailing-json") ? value + "{}" : value;
    }

    private static void replace(Map<String, Object> packet, byte[] data) {
      packet.put("size", Integer.toString(data.length));
      packet.put("data", hexDump(data));
    }
  }
}
