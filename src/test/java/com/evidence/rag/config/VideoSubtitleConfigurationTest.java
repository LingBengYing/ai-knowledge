package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class VideoSubtitleConfigurationTest {
  @TempDir Path directory;

  @Test
  void optInSubtitleRuntimeUsesDecoderV2AndCompilerV3WithoutOcr() throws Exception {
    try (var resources = new VideoConfiguration().videoResources(local(true))) {
      assertTrue(resources.decoder.revision().startsWith("java-video-decoder-v2:"));
      assertTrue(resources.compiler.revision().startsWith("java-video-compiler-v3:"));
    }
  }

  @Test
  void omittedOrExplicitlyDisabledSubtitlesRetainTheSameFrozenOldCompilerIdentity()
      throws Exception {
    var omitted = local(false);
    try (var first = new VideoConfiguration().videoResources(omitted);
        var second =
            new VideoConfiguration()
                .videoResources(
                    local(false).withProperty("rag.video.subtitles.enabled", "false"))) {
      assertTrue(first.compiler.revision().startsWith("java-video-compiler-v1:"));
      assertEquals(first.compiler.revision(), second.compiler.revision());
      assertEquals(first.decoder.revision(), second.decoder.revision());
    }
  }

  @Test
  void subtitleAndOcrSwitchesComposeIntoOneExplicitV3Compiler() throws Exception {
    var environment =
        local(true)
            .withProperty("rag.video.ocr.enabled", "true")
            .withProperty("rag.video.ocr.executable", directory.resolve("codec").toString())
            .withProperty("rag.video.ocr.language", "eng")
            .withProperty("rag.video.ocr.revision", "5.5.3");
    try (var withOcr = new VideoConfiguration().videoResources(environment);
        var withoutOcr = new VideoConfiguration().videoResources(local(true))) {
      assertTrue(withOcr.compiler.revision().startsWith("java-video-compiler-v3:"));
      org.junit.jupiter.api.Assertions.assertNotEquals(
          withOcr.compiler.revision(), withoutOcr.compiler.revision());
    }
  }

  private MockEnvironment local(boolean subtitles) throws Exception {
    Path executable = directory.resolve("codec");
    Files.writeString(executable, "synthetic executable, never launched");
    assertTrue(executable.toFile().setExecutable(true));
    var result =
        new MockEnvironment()
            .withProperty("rag.video.enabled", "true")
            .withProperty("server.address", "127.0.0.1")
            .withProperty("rag.environment", "test")
            .withProperty("rag.ingestion.enabled", "true")
            .withProperty("rag.video.ffmpeg-executable", executable.toString())
            .withProperty("rag.video.ffprobe-executable", executable.toString())
            .withProperty("rag.video.asr.base-url", "http://127.0.0.1:1/v1")
            .withProperty("rag.video.asr.model", "synthetic-asr")
            .withProperty("rag.video.asr.api-key", "synthetic-subtitle-credential")
            .withProperty("rag.video.asr.allow-loopback-http", "true")
            .withProperty("rag.video.vision.base-url", "http://127.0.0.1:1/v1")
            .withProperty("rag.video.vision.model", "synthetic-vision")
            .withProperty("rag.video.vision.api-key", "synthetic-subtitle-credential")
            .withProperty("rag.video.vision.allow-loopback-http", "true");
    return subtitles ? result.withProperty("rag.video.subtitles.enabled", "true") : result;
  }
}
