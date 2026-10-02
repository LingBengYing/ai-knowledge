package com.evidence.rag.support;

import com.evidence.rag.model.domain.AudioTranscriptSpan;
import com.evidence.rag.model.domain.AudioTranscription;
import com.evidence.rag.model.domain.ImageRecall;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VideoCompilation;
import com.evidence.rag.model.domain.VideoFrame;
import com.evidence.rag.model.domain.VideoFrameRecall;
import com.evidence.rag.model.domain.VisualImage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** Entirely synthetic pixels and transcript; not real ASR or visual quality evidence. */
public final class VideoCompilationFixture {
  public static final byte[] ORIGINAL = "synthetic video original".getBytes(StandardCharsets.UTF_8);
  public static final String SOURCE = ModelValues.sha256(ORIGINAL);
  public static final String COMPILER = "java-video-compiler-v1:" + "c".repeat(64);

  private VideoCompilationFixture() {}

  public static VideoCompilation compilation(boolean audio) {
    return new VideoCompilation(
        SOURCE,
        "video-decoder-v1",
        COMPILER,
        2_000_000,
        4_000_000,
        List.of(frame(0, 0, 200_000), frame(1, 1_200_000, 200_000)),
        audio
            ? new AudioTranscription(
                SOURCE,
                "video-decoder-v1",
                "asr-v1",
                "transcription-v1",
                64_000,
                List.of(
                    new AudioTranscriptSpan(0, 0, 1000, "蓝色圆形。"),
                    new AudioTranscriptSpan(1, 1000, 2000, "\u2003"),
                    new AudioTranscriptSpan(2, 2000, 4000, "预算42万元。")))
            : null);
  }

  public static VideoFrameRecall frame(int ordinal, long presentation, long duration) {
    return new VideoFrameRecall(
        new VideoFrame(ordinal, presentation, duration, image(), 2, 2),
        new ImageRecall("合成蓝色区域 " + ordinal, "description-v1"));
  }

  public static VisualImage image() {
    try {
      var output = new ByteArrayOutputStream();
      var pixels = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
      pixels.setRGB(0, 0, 0x1122ff);
      try (var stream = new MemoryCacheImageOutputStream(output)) {
        ImageIO.write(pixels, "png", stream);
      }
      return new VisualImage("image/png", output.toByteArray());
    } catch (IOException error) {
      throw new AssertionError(error);
    }
  }
}
