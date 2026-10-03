package com.evidence.rag.worker.parser;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.evidence.rag.VideoAvTestFixture;
import com.evidence.rag.tool.parser.TextParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProcessVideoAvDecoderTest {
  @TempDir Path directory;

  @Test
  void continuousFrameIntervalsAndFullAudioTailShareExactEpochAndNoFakeVideo() throws Exception {
    try (var decoder = decoder("audio-tail", Duration.ofSeconds(10), 1)) {
      var result = decoder.decode("synthetic.mp4", "video/mp4", VideoAvTestFixture.raw());
      assertEquals(2000000, result.epoch().timelineOriginUs());
      assertEquals(16000, result.epoch().ticksPerSecond());
      assertEquals(2, result.windows().size());
      assertEquals(4, result.windows().getFirst().video().frameCount());
      assertEquals(
          List.of(0L, 4000L, 8000L, 12000L),
          result.windows().getFirst().video().frames().stream()
              .map(frame -> frame.localTick())
              .toList());
      assertNull(result.windows().getLast().video());
      assertEquals(24001, result.windows().getLast().audio().endSample());
      assertArrayEquals(new byte[16002], result.windows().getLast().audio().pcm());
      assertFalse(Files.exists(Path.of(Files.readString(directory.resolve("job")))));
    }
  }

  @Test
  void continuousVariableFrameDurationsRetainAllFramesAndOriginalPixelHashes() throws Exception {
    try (var decoder = decoder("vfr", Duration.ofSeconds(10), 1)) {
      var result = decoder.decode("synthetic.mp4", "video/mp4", VideoAvTestFixture.raw());
      assertEquals(
          List.of(2000L, 4000L, 2000L, 8000L),
          result.windows().getFirst().video().frames().stream()
              .map(frame -> frame.durationTick())
              .toList());
      assertEquals(
          "a".repeat(64), result.windows().getFirst().video().frames().getLast().pixelSha256());
      assertFalse(result.hasAudio());
      assertNull(result.windows().getFirst().audio());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"gap", "overlap", "long-hold"})
  void unsupportedTimelineNeverDeletesOrRetimesFramesToSucceed(String mode) throws Exception {
    try (var decoder = decoder(mode, Duration.ofSeconds(10), 1)) {
      assertEquals(
          "unsupported_document",
          assertThrows(
                  TextParser.Failure.class,
                  () -> decoder.decode("synthetic.mp4", "video/mp4", VideoAvTestFixture.raw()))
              .code());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"pixel-mismatch", "clip-duration-mismatch", "hash-table-overflow"})
  void completeOutputMismatchAndBoundedFramehashOverflowFailWholeCompilation(String mode)
      throws Exception {
    try (var decoder = decoder(mode, Duration.ofSeconds(10), 1)) {
      assertThrows(
          TextParser.Failure.class,
          () -> decoder.decode("synthetic.mp4", "video/mp4", VideoAvTestFixture.raw()));
      assertFalse(Files.exists(Path.of(Files.readString(directory.resolve("job")))));
    }
  }

  @Test
  void chunkSizeAndExecutableBytesBindRevisionWhileBadEnvelopeLaunchesNoChild() throws Exception {
    try (var one = decoder("vfr", Duration.ofSeconds(10), 1);
        var two = decoder("vfr", Duration.ofSeconds(10), 2)) {
      assertNotEquals(one.revision(), two.revision());
      assertTrue(one.revision().matches("java-video-av-decoder-v1:[a-f0-9]{64}"));
      assertEquals(
          "unsupported_document",
          assertThrows(
                  TextParser.Failure.class,
                  () -> one.decode("file.txt", "text/plain", new byte[] {1}))
              .code());
      assertFalse(Files.exists(directory.resolve("pid")));
    }
  }

  @Test
  void nativeDeadlineConfirmsProbeTerminationAndPrivateDirectoryCleanup() throws Exception {
    try (var decoder = decoder("waiting", Duration.ofSeconds(1), 1)) {
      assertEquals(
          "parser_timeout",
          assertThrows(
                  TextParser.Failure.class,
                  () -> decoder.decode("synthetic.mp4", "video/mp4", VideoAvTestFixture.raw()))
              .code());
      assertDead();
      assertFalse(Files.exists(Path.of(Files.readString(directory.resolve("job")))));
    }
  }

  @Test
  void closeCancelsActiveNativeChildAndPreventsReuse() throws Exception {
    var decoder = decoder("waiting", Duration.ofSeconds(10), 1);
    var future = new FutureTask<>(() -> failure(decoder));
    Thread.ofVirtual().start(future);
    try {
      awaitPid();
      decoder.close();
      assertEquals("parser_closed:false", future.get(3, TimeUnit.SECONDS));
      assertDead();
      assertThrows(
          TextParser.Failure.class,
          () -> decoder.decode("synthetic.mp4", "video/mp4", VideoAvTestFixture.raw()));
    } finally {
      decoder.close();
    }
  }

  @Test
  void interruptionPreservesCallerFlagAndReapsNativeChild() throws Exception {
    try (var decoder = decoder("waiting", Duration.ofSeconds(10), 1)) {
      var future = new FutureTask<>(() -> failure(decoder));
      var caller = Thread.ofVirtual().start(future);
      awaitPid();
      caller.interrupt();
      assertEquals("parser_interrupted:true", future.get(3, TimeUnit.SECONDS));
      assertDead();
    }
  }

  private static String failure(ProcessVideoAvDecoder decoder) {
    try {
      decoder.decode("synthetic.mp4", "video/mp4", VideoAvTestFixture.raw());
      return "unexpected";
    } catch (TextParser.Failure failure) {
      return failure.code() + ":" + Thread.currentThread().isInterrupted();
    }
  }

  private ProcessVideoAvDecoder decoder(String mode, Duration deadline, int chunk)
      throws Exception {
    Path java = Path.of(System.getProperty("java.home"), "bin", "java").toRealPath();
    return new ProcessVideoAvDecoder(
        java,
        java,
        deadline,
        chunk,
        PipelineFixture.class.getName(),
        List.of(mode, directory.toString()));
  }

  private void awaitPid() throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (System.nanoTime() < until) {
      if (Files.exists(directory.resolve("pid"))
          && Files.readString(directory.resolve("pid")).matches("[1-9][0-9]*")) {
        return;
      }
      Thread.sleep(10);
    }
    fail("Native child did not record PID");
  }

  private void assertDead() throws Exception {
    assertFalse(
        ProcessHandle.of(Long.parseLong(Files.readString(directory.resolve("pid"))))
            .map(ProcessHandle::isAlive)
            .orElse(false));
  }

  /** Actual isolated process transport, synthetic ffprobe/framehash/media responses only. */
  public static final class PipelineFixture {
    public static void main(String[] args) throws Exception {
      String mode = args[0], phase = args[2];
      Path markers = Path.of(args[1]);
      var command = Arrays.asList(args).subList(3, args.length);
      Path input =
          Path.of(
              command.contains("-i") ? command.get(command.indexOf("-i") + 1) : command.getLast());
      boolean clip = input.getFileName().toString().equals("clip.mp4");
      Files.writeString(markers.resolve("pid"), Long.toString(ProcessHandle.current().pid()));
      Files.writeString(markers.resolve("job"), input.getParent().toString());
      if (mode.equals("waiting")) {
        Thread.sleep(30000);
      }
      if (phase.equals("av-streams")) {
        System.out.print(
            "{\"streams\":[{\"codec_type\":\"video\",\"width\":16,\"height\":16,\"time_base\":\"1/1000\"}"
                + (!clip && mode.equals("audio-tail")
                    ? ",{\"codec_type\":\"audio\",\"sample_rate\":\"16000\",\"time_base\":\"1/16000\"}"
                    : "")
                + "],\"format\":{\"format_name\":\"mov,mp4\"}}");
      } else if (phase.equals("av-timeline")) {
        long[] offsets =
            mode.equals("vfr") ? new long[] {0, 125, 375, 500} : new long[] {0, 250, 500, 750};
        long[] durations =
            mode.equals("vfr") ? new long[] {125, 250, 125, 500} : new long[] {250, 250, 250, 250};
        if (!clip && mode.equals("gap")) {
          offsets[1] = 300;
        }
        if (!clip && mode.equals("overlap")) {
          offsets[1] = 200;
        }
        if (!clip && mode.equals("long-hold")) {
          offsets = new long[] {0, 1100, 1350, 1600};
          durations = new long[] {1100, 250, 250, 250};
        }
        if (clip && mode.equals("clip-duration-mismatch")) {
          durations[3] = 249;
        }
        var text = new StringBuilder("{\"streams\":[{\"time_base\":\"1/1000\"}],\"frames\":[");
        for (int i = 0; i < 4; i++) {
          if (i > 0) {
            text.append(',');
          }
          text.append("{\"pts\":")
              .append((clip ? 0 : 2000) + offsets[i])
              .append(",\"duration\":")
              .append(durations[i])
              .append(",\"width\":16,\"height\":16}");
        }
        System.out.print(text.append("]}"));
      } else if (phase.equals("av-framehash")) {
        if (mode.equals("hash-table-overflow")) {
          System.out.write(new byte[16 * 1024 * 1024 + 1]);
        } else {
          for (int i = 0; i < 4; i++) {
            System.out.println(
                "0, "
                    + i
                    + ", "
                    + i
                    + ", 1, 1024, "
                    + (clip && mode.equals("pixel-mismatch") ? "b" : "a").repeat(64));
          }
        }
      } else if (phase.equals("av-audio-timeline")) {
        System.out.print(
            "{\"streams\":[{\"time_base\":\"1/16000\",\"sample_rate\":\"16000\"}],\"frames\":[{\"pts\":32000,\"nb_samples\":24001}]}");
      } else if (phase.equals("av-audio")) {
        System.out.write(new byte[48002]);
      } else if (phase.equals("av-clip")) {
        Files.write(Path.of(command.getLast()), VideoAvTestFixture.raw());
      } else {
        System.exit(3);
      }
    }
  }
}
