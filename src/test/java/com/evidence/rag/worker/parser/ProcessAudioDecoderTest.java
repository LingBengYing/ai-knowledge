package com.evidence.rag.worker.parser;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.tool.parser.TextParser;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ProcessAudioDecoderTest {
  @TempDir Path directory;

  @Test
  void probesOriginalThenReturnsCompletePcmWithSourceAndDecoderIdentity() throws Exception {
    byte[] original = wav(321);
    try (var decoder = decoder("normal", original, Duration.ofSeconds(5))) {
      var decoded = decoder.decode("voice.wav", "audio/wav", original);
      assertArrayEquals(Arrays.copyOfRange(original, 44, original.length), decoded.pcm());
      assertEquals(21, decoded.durationMs());
      assertEquals(sha(original), decoded.sourceSha256());
      assertEquals(decoder.revision(), decoded.decoderRevision());
      assertTrue(Files.exists(directory.resolve("probe")));
      assertTrue(Files.exists(directory.resolve("decode")));
    }
  }

  static Stream<Arguments> supportedCompressedEnvelopes() {
    return Stream.of(
        Arguments.of("voice.mp3", "audio/mpeg", "ID3\u0004\u0000\u0000\u0000\u0000", "mp3"),
        Arguments.of("voice.flac", "audio/flac", "fLaC\u0000\u0000\u0000\u0000", "flac"),
        Arguments.of("voice.ogg", "audio/ogg", "OggS\u0000\u0000\u0000\u0000", "ogg"),
        Arguments.of(
            "voice.m4a",
            "audio/mp4",
            "\u0000\u0000\u0000\u0010ftypM4A \u0000\u0000\u0000\u0000",
            "m4a"),
        Arguments.of(
            "voice.mp4",
            "audio/mp4",
            "\u0000\u0000\u0000\u0010ftypisom\u0000\u0000\u0000\u0000",
            "mp4"),
        Arguments.of(
            "voice.webm", "audio/webm", "\u001aE\u00df\u00a3\u0000\u0000\u0000\u0000", "webm"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("supportedCompressedEnvelopes")
  void supportedProbeFormatsReturnCompletePcmAndRetainOriginalBytes(
      String filename, String mime, String envelope, String format) throws Exception {
    byte[] original = envelope.getBytes(StandardCharsets.ISO_8859_1);
    byte[] normalized = wav(321);
    try (var decoder = decoder("format-" + format, original, Duration.ofSeconds(5))) {
      var decoded = decoder.decode(filename, mime, original);
      assertArrayEquals(original, Files.readAllBytes(directory.resolve("probe")));
      assertArrayEquals(Arrays.copyOfRange(normalized, 44, normalized.length), decoded.pcm());
      assertEquals(21, decoded.durationMs());
      assertEquals(sha(original), decoded.sourceSha256());
      assertEquals(decoder.revision(), decoded.decoderRevision());
      assertTrue(Files.exists(directory.resolve("decode")));
    }
  }

  @Test
  void videoOrMultipleAudioStreamsNeverReachDecoder() throws Exception {
    byte[] original = wav(16);
    for (String mode : List.of("video", "multiple")) {
      try (var decoder = decoder(mode, original, Duration.ofSeconds(5))) {
        assertEquals(
            "unsupported_document",
            assertThrows(
                    TextParser.Failure.class,
                    () -> decoder.decode("voice.wav", "audio/wav", original))
                .code());
        assertFalse(Files.exists(directory.resolve("decode")));
      }
    }
  }

  @Test
  void outputBeyondTenMinutesFailsInsteadOfReturningATruncatedSuccess() throws Exception {
    byte[] original = wav(16);
    try (var decoder = decoder("oversized", original, Duration.ofSeconds(5))) {
      assertEquals(
          "parser_invalid_output",
          assertThrows(
                  TextParser.Failure.class,
                  () -> decoder.decode("voice.wav", "audio/wav", original))
              .code());
      assertTrue(Files.exists(directory.resolve("decode")));
    }
  }

  @Test
  void timeoutConfirmsProbeExitBeforeReturning() throws Exception {
    byte[] original = wav(16);
    try (var decoder = decoder("waiting", original, Duration.ofSeconds(1))) {
      assertEquals(
          "parser_timeout",
          assertThrows(
                  TextParser.Failure.class,
                  () -> decoder.decode("voice.wav", "audio/wav", original))
              .code());
      assertChildExited();
      assertFalse(Files.exists(directory.resolve("decode")));
    }
  }

  @Test
  void closeCancelsAndJoinsTheActiveProbeBeforeReleasingAdmission() throws Exception {
    byte[] original = wav(16);
    try (var decoder = decoder("waiting", original, Duration.ofSeconds(10))) {
      var work = new FutureTask<>(() -> decoder.decode("voice.wav", "audio/wav", original));
      Thread thread = Thread.ofVirtual().start(work);
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!Files.exists(directory.resolve("pid")) && System.nanoTime() < until) {
        Thread.sleep(10);
      }
      assertTrue(Files.exists(directory.resolve("pid")));
      decoder.close();
      var failed = assertThrows(ExecutionException.class, () -> work.get(3, TimeUnit.SECONDS));
      assertEquals("parser_closed", ((TextParser.Failure) failed.getCause()).code());
      assertTrue(thread.join(Duration.ofSeconds(2)));
      assertChildExited();
    }
    try (var next = decoder("normal", original, Duration.ofSeconds(5))) {
      assertEquals(1, next.decode("voice.wav", "audio/wav", original).durationMs());
    }
  }

  private ProcessAudioDecoder decoder(String mode, byte[] original, Duration deadline)
      throws Exception {
    Path java = Path.of(System.getProperty("java.home"), "bin", "java").toRealPath();
    return new ProcessAudioDecoder(
        java,
        java,
        deadline,
        PipelineFixture.class.getName(),
        List.of(mode, directory.toString(), sha(original)));
  }

  private void assertChildExited() throws Exception {
    long pid = Long.parseLong(Files.readString(directory.resolve("pid")));
    assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
  }

  static byte[] wav(int samples) {
    var bytes = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
    bytes.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + samples * 2);
    bytes.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
    bytes.putShort((short) 1).putShort((short) 1).putInt(16000).putInt(32000);
    bytes.putShort((short) 2).putShort((short) 16);
    bytes.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(samples * 2);
    for (int index = 0; index < samples; index++) {
      bytes.putShort((short) (index % 500));
    }
    return bytes.array();
  }

  static String sha(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  /** Real subprocesses stand in for ffprobe/ffmpeg, using the same transport and lifecycle. */
  public static final class PipelineFixture {
    public static void main(String[] args) throws Exception {
      String mode = args[0], phase = args[3];
      Path markers = Path.of(args[1]);
      var command = Arrays.asList(args).subList(4, args.length);
      if (System.getenv().keySet().stream().anyMatch(k -> !k.equals("__CF_USER_TEXT_ENCODING"))
          || !command.contains("-protocol_whitelist")
          || !command.contains("-format_whitelist")) {
        System.exit(3);
      }
      Path source =
          Path.of(
              phase.equals("probe") ? command.getLast() : command.get(command.indexOf("-i") + 1));
      byte[] original = Files.readAllBytes(source);
      if (!sha(original).equals(args[2])) {
        System.exit(4);
      }
      Files.writeString(markers.resolve("pid"), Long.toString(ProcessHandle.current().pid()));
      if (phase.equals("probe")) {
        Files.write(markers.resolve("probe"), original);
        if (mode.equals("waiting")) {
          Thread.sleep(30000);
        }
        String streams =
            switch (mode) {
              case "video" -> "{\"index\":0,\"codec_type\":\"video\"}";
              case "multiple" ->
                  "{\"index\":0,\"codec_type\":\"audio\"},{\"index\":1,\"codec_type\":\"audio\"}";
              default -> "{\"index\":0,\"codec_type\":\"audio\"}";
            };
        String format =
            switch (mode) {
              case "format-mp3" -> "mp3";
              case "format-flac" -> "flac";
              case "format-ogg" -> "ogg";
              case "format-m4a", "format-mp4" -> "mov,mp4,m4a,3gp,3g2,mj2";
              case "format-webm" -> "matroska,webm";
              default -> "wav";
            };
        System.out.print(
            "{\"streams\":[" + streams + "],\"format\":{\"format_name\":\"" + format + "\"}}");
      } else {
        if (!Files.exists(markers.resolve("probe"))
            || command.contains("-t")
            || !command.contains("16000")
            || !command.contains("pcm_s16le")
            || !command.contains("s16le")) {
          System.exit(5);
        }
        Files.writeString(markers.resolve("decode"), "called");
        if (mode.equals("oversized")) {
          System.out.write(new byte[19_200_002]);
        } else if (mode.startsWith("format-")) {
          // Controlled PCM output validates transport, not the compressed codec itself.
          byte[] normalized = wav(321);
          System.out.write(normalized, 44, normalized.length - 44);
        } else {
          System.out.write(original, 44, original.length - 44);
        }
      }
    }
  }
}
