package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.AudioModels;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.service.AudioCompilationService;
import com.evidence.rag.service.RuntimeService;
import com.evidence.rag.service.VideoCompilationService;
import com.evidence.rag.support.VideoCompilationFixture;
import com.evidence.rag.tool.parser.AudioPcm;
import com.evidence.rag.tool.parser.TextParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

class VideoConfigurationTest {
  @TempDir Path directory;

  @Test
  void disabledVideoNeedsNoCodecOrProviderConfiguration() {
    try (var context = context(new MockEnvironment())) {
      context.refresh();
      assertTrue(context.getBeansOfType(VideoCompilationService.class).isEmpty());
    }
  }

  @Test
  void videoIngestionStartsWithoutEnablingAudioVisualOrTextAnswers() throws Exception {
    try (var context = context(local())) {
      context.refresh();
      assertEquals(1, context.getBeansOfType(VideoCompilationService.class).size());
      assertTrue(
          context
              .getBean(VideoCompilationService.class)
              .revision()
              .startsWith("java-video-compiler-v1:"));
      assertTrue(context.getBeansOfType(AudioCompilationService.class).isEmpty());
      assertTrue(context.getBeansOfType(TextModels.class).isEmpty());
      assertTrue(context.getBeansOfType(TextAdapterSettings.class).isEmpty());
      // Video-owned clients must not become unqualified candidates for the old ingestion paths.
      assertTrue(context.getBeansOfType(AudioModels.class).isEmpty());
      assertTrue(context.getBeansOfType(VisionModels.class).isEmpty());
      assertFalse(
          context
              .getBean(VideoCompilationService.class)
              .toString()
              .contains("synthetic-video-credential"));
    }
  }

  private MockEnvironment local() throws Exception {
    Path executable = directory.resolve("video-codec-fixture");
    Files.writeString(executable, "synthetic codec fixture, never launched");
    assertTrue(executable.toFile().setExecutable(true));
    return new MockEnvironment()
        .withProperty("rag.video.enabled", "true")
        .withProperty("server.address", "127.0.0.1")
        .withProperty("rag.environment", "test")
        .withProperty("rag.ingestion.enabled", "true")
        .withProperty("rag.answers.enabled", "false")
        .withProperty("rag.video.ffmpeg-executable", executable.toString())
        .withProperty("rag.video.ffprobe-executable", executable.toString())
        .withProperty("rag.video.asr.base-url", "http://127.0.0.1:1/v1")
        .withProperty("rag.video.asr.model", "synthetic-asr")
        .withProperty("rag.video.asr.api-key", "synthetic-video-credential")
        .withProperty("rag.video.asr.allow-loopback-http", "true")
        .withProperty("rag.video.vision.base-url", "http://127.0.0.1:1/v1")
        .withProperty("rag.video.vision.model", "synthetic-vision")
        .withProperty("rag.video.vision.api-key", "synthetic-video-credential")
        .withProperty("rag.video.vision.allow-loopback-http", "true");
  }

  @Test
  void contextClosesAllVideoOwnedResources() throws Exception {
    VideoConfiguration.VideoResources resources;
    try (var context = context(local().withProperty("server.address", "::1"))) {
      context.refresh();
      resources = context.getBean(VideoConfiguration.VideoResources.class);
    }
    byte[] source = new byte[16];
    System.arraycopy(new byte[] {'f', 't', 'y', 'p'}, 0, source, 4, 4);
    assertEquals(
        "parser_closed",
        assertThrows(
                TextParser.Failure.class,
                () -> resources.decoder.decode("clip.mp4", "video/mp4", source))
            .code());
    assertEquals(
        "model_closed",
        assertThrows(
                TextModels.Failure.class,
                () -> resources.audio.transcribe(AudioPcm.wav(new byte[2], 0, 2)))
            .code());
    assertEquals(
        "model_closed",
        assertThrows(
                TextModels.Failure.class,
                () -> resources.vision.describe(VideoCompilationFixture.image()))
            .code());
  }

  @Test
  void invalidConfigurationFailsWithoutLeakingCredentialsAtAnyCompositionStage() throws Exception {
    for (String[] setting :
        new String[][] {
          {"server.address", "localhost"}, {"rag.environment", "production"},
          {"rag.ingestion.enabled", "false"}, {"rag.video.frame-interval-seconds", "0"},
          {"rag.video.asr.api-key", ""}, {"rag.video.vision.api-key", ""},
          {"rag.video.chunk-seconds", "31"}, {"rag.video.compilation-budget-ms", "9"}
        }) {
      var environment = local().withProperty(setting[0], setting[1]);
      var invalid =
          assertThrows(
              IllegalArgumentException.class,
              () -> new VideoConfiguration().videoResources(environment));
      assertEquals("Invalid local video configuration", invalid.getMessage());
      assertNull(invalid.getCause());
    }
  }

  @Test
  void videoCapabilitiesNeverAdvertiseUnimplementedAnswersOrSources() {
    for (boolean ingestion : new boolean[] {false, true}) {
      for (boolean indexing : new boolean[] {false, true}) {
        var result =
            new RuntimeService(
                    "development_headers",
                    "org-main",
                    ingestion,
                    indexing,
                    false,
                    false,
                    false,
                    false,
                    false,
                    true)
                .capabilities();
        assertEquals(ingestion, result.capabilities().contains("video_upload"));
        assertEquals(indexing, result.capabilities().contains("video_index"));
        assertFalse(result.capabilities().contains("video_answers"));
        assertFalse(result.capabilities().contains("video_sources"));
        assertFalse(result.capabilities().contains("answers"));
      }
    }
  }

  private static AnnotationConfigApplicationContext context(MockEnvironment environment) {
    var context = new AnnotationConfigApplicationContext();
    context.setEnvironment(environment);
    context.register(VideoConfiguration.class);
    return context;
  }
}
