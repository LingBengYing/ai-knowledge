package com.evidence.rag.worker.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.ModelValues;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Explicit native video acceptance using only fresh synthetic media and real codec binaries. */
class VideoDecoderNativeIT {
  @TempDir Path directory;

  @Test
  void actualVfrFramesSceneChangesAndDelayedAudioKeepOneVideoEpoch() throws Exception {
    Path ffmpeg = configuredPath("RAG_VIDEO_DECODER_IT_FFMPEG");
    Path ffprobe = configuredPath("RAG_VIDEO_DECODER_IT_FFPROBE");
    byte[] original = generate(ffmpeg, "delayed", 4);
    try (var decoder = new ProcessVideoDecoder(ffmpeg, ffprobe, Duration.ofSeconds(15), 2)) {
      var decoded = decoder.decode("synthetic.mp4", "video/mp4", original);
      assertEquals(ModelValues.sha256(original), decoded.sourceSha256());
      assertEquals(decoder.revision(), decoded.decoderRevision());
      assertEquals(0, decoded.timelineOriginUs());
      assertEquals(6_000_000, decoded.durationUs());
      assertEquals(
          List.of(0L, 2_000_000L, 3_000_000L, 5_000_000L),
          decoded.frames().stream().map(frame -> frame.presentationUs()).toList());
      assertEquals(4, decoded.frames().size());
      for (int index = 0; index < decoded.frames().size(); index++) {
        var frame = decoded.frames().get(index);
        assertEquals(index, frame.ordinal());
        assertEquals(96, frame.width());
        assertEquals(64, frame.height());
        assertTrue(frame.durationUs() > 0);
        var image = ImageIO.read(new ByteArrayInputStream(frame.image().content()));
        int rgb = image.getRGB(48, 32);
        int red = (rgb >> 16) & 255;
        int blue = rgb & 255;
        assertTrue(index < 2 ? red > 240 && blue < 20 : blue > 240 && red < 20);
      }
      assertEquals(decoded.sourceSha256(), decoded.audio().sourceSha256());
      assertEquals(decoded.decoderRevision(), decoded.audio().decoderRevision());
      long firstAudible = audibleBounds(decoded.audio().pcm())[0];
      assertTrue(firstAudible >= 19_900 && firstAudible <= 20_100);
      assertTrue(decoded.audio().pcm().length / 2 > 83_900);
    }
  }

  @Test
  void actualVideoWithoutAnAudioStreamRemainsVisuallyUsable() throws Exception {
    Path ffmpeg = configuredPath("RAG_VIDEO_DECODER_IT_FFMPEG");
    Path ffprobe = configuredPath("RAG_VIDEO_DECODER_IT_FFPROBE");
    byte[] original = generate(ffmpeg, "silent", 0);
    try (var decoder = new ProcessVideoDecoder(ffmpeg, ffprobe, Duration.ofSeconds(15), 2)) {
      var decoded = decoder.decode("synthetic.mp4", "video/mp4", original);
      assertEquals(6_000_000, decoded.durationUs());
      assertEquals(4, decoded.frames().size());
      assertNull(decoded.audio());
    }
  }

  @Test
  void actualAudioTailBeyondLastVideoFrameIsPreservedRatherThanTrimmed() throws Exception {
    Path ffmpeg = configuredPath("RAG_VIDEO_DECODER_IT_FFMPEG");
    Path ffprobe = configuredPath("RAG_VIDEO_DECODER_IT_FFPROBE");
    byte[] original = generate(ffmpeg, "long-tail", 6);
    try (var decoder = new ProcessVideoDecoder(ffmpeg, ffprobe, Duration.ofSeconds(15), 2)) {
      var decoded = decoder.decode("synthetic.mp4", "video/mp4", original);
      byte[] pcm = decoded.audio().pcm();
      assertTrue(audibleBounds(pcm)[1] >= 115_000, "Real tail near 7.25s must remain");
      assertTrue(decoded.durationUs() >= 7_250_000);
      assertEquals((pcm.length / 2L * 1_000_000 + 15_999) / 16_000, decoded.durationUs());
      assertEquals(5_000_000, decoded.frames().getLast().presentationUs());
    }
  }

  private byte[] generate(Path ffmpeg, String name, int audioSeconds) throws Exception {
    Path output = directory.resolve(name + ".mp4");
    var args =
        new ArrayList<>(
            List.of(
                ffmpeg.toString(),
                "-hide_banner",
                "-nostdin",
                "-n",
                "-f",
                "lavfi",
                "-i",
                "color=c=red:s=96x64:r=10:d=3",
                "-f",
                "lavfi",
                "-i",
                "color=c=blue:s=96x64:r=10:d=3"));
    if (audioSeconds > 0) {
      args.addAll(
          List.of(
              "-itsoffset",
              "1.25",
              "-f",
              "lavfi",
              "-i",
              "sine=frequency=440:sample_rate=16000:duration=" + audioSeconds));
    }
    args.addAll(
        List.of(
            "-filter_complex",
            "[0:v][1:v]concat=n=2:v=1:a=0,select='not(eq(mod(n,10),1)+eq(mod(n,10),2))'[v]",
            "-map",
            "[v]"));
    if (audioSeconds > 0) {
      args.addAll(List.of("-map", "2:a", "-c:a", "aac"));
    }
    args.addAll(
        List.of(
            "-c:v",
            "libx264",
            "-threads",
            "1",
            "-bf",
            "0",
            "-pix_fmt",
            "yuv420p",
            "-fps_mode",
            "vfr",
            "-enc_time_base:v",
            "filter",
            output.toString()));
    var builder =
        new ProcessBuilder(args)
            .directory(directory.toFile())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD);
    builder.environment().clear();
    Process process = builder.start();
    process.getOutputStream().close();
    try {
      assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Synthetic video generation timed out");
      assertEquals(0, process.exitValue(), "Synthetic video generation failed");
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        assertTrue(process.waitFor(2, TimeUnit.SECONDS));
      }
    }
    assertFalse(process.isAlive());
    return Files.readAllBytes(output);
  }

  private static long[] audibleBounds(byte[] pcm) {
    var samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
    long first = -1;
    long last = -1;
    for (long index = 0; samples.hasRemaining(); index++) {
      if (Math.abs((int) samples.getShort()) > 256) {
        if (first < 0) {
          first = index;
        }
        last = index;
      }
    }
    return new long[] {first, last};
  }

  private static Path configuredPath(String variable) {
    if (!"true".equals(System.getenv("RAG_VIDEO_DECODER_IT_ENABLED"))) {
      throw new IllegalArgumentException("Explicit native video decoder IT opt-in is required");
    }
    String value = System.getenv(variable);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Explicit native video decoder paths are required");
    }
    return Path.of(value);
  }
}
