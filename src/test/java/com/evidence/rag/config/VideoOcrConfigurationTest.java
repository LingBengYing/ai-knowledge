package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.ImageOcrOptions;
import com.evidence.rag.service.VideoCompilationService;
import com.evidence.rag.support.VideoCompilationFixture;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.worker.parser.ImageOcr;
import com.evidence.rag.worker.parser.ProcessImageParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

class VideoOcrConfigurationTest {
  @TempDir Path directory;

  @Test
  void ocrStaysOffByDefaultAndDoesNotChangeLegacyVideoCompiler() throws Exception {
    try (var context = context(local())) {
      context.refresh();
      var resources = context.getBean(VideoConfiguration.VideoResources.class);
      assertNull(resources.ocr);
      assertTrue(resources.compiler.revision().startsWith("java-video-compiler-v1:"));
    }
  }

  @Test
  void enablingOnlyVideoOcrDoesNotEnableVideoOrRequireUnusedSettings() {
    try (var context =
        context(new MockEnvironment().withProperty("rag.video.ocr.enabled", "true"))) {
      context.refresh();
      assertTrue(context.getBeansOfType(VideoCompilationService.class).isEmpty());
    }
  }

  @Test
  void optInCreatesV2CompilerAndClosesOwnedOcrWithoutRegisteringCompetingBeans() throws Exception {
    ProcessImageParser ocr;
    try (var context = context(ocr())) {
      context.refresh();
      var resources = context.getBean(VideoConfiguration.VideoResources.class);
      assertTrue(resources.compiler.revision().startsWith("java-video-compiler-v2:"));
      ocr = resources.ocr;
      assertNotNull(ocr);
      assertEquals("java-image-ocr-v2-tsv:5.5.3:eng", ocr.revision());
      assertTrue(context.getBeansOfType(ImageOcr.class).isEmpty());
      assertTrue(context.getBeansOfType(ProcessImageParser.class).isEmpty());
      assertTrue(context.getBeansOfType(ImageOcrOptions.class).isEmpty());
    }
    assertEquals(
        "parser_closed",
        assertThrows(TextParser.Failure.class, () -> ocr.read(VideoCompilationFixture.image()))
            .code());
  }

  @Test
  void invalidEnabledOcrFailsConfigurationInsteadOfSilentlyFallingBackToV1() throws Exception {
    for (String[] setting :
        new String[][] {
          {"rag.video.ocr.executable", ""},
          {"rag.video.ocr.executable", "relative/tesseract"},
          {"rag.video.ocr.language", "eng;bad"},
          {"rag.video.ocr.revision", "latest"},
          {"rag.video.ocr.deadline-ms", "9"},
          {"rag.video.ocr.deadline-ms", "60001"}
        }) {
      var environment = ocr().withProperty(setting[0], setting[1]);
      var invalid =
          assertThrows(
              IllegalArgumentException.class,
              () -> new VideoConfiguration().videoResources(environment));
      assertEquals("Invalid local video configuration", invalid.getMessage());
      assertNull(invalid.getCause());
    }
  }

  private MockEnvironment ocr() throws Exception {
    return local()
        .withProperty("rag.video.ocr.enabled", "true")
        .withProperty("rag.video.ocr.executable", directory.resolve("local-fixture").toString())
        .withProperty("rag.video.ocr.language", "eng")
        .withProperty("rag.video.ocr.revision", "5.5.3")
        .withProperty("rag.video.ocr.deadline-ms", "1000");
  }

  private MockEnvironment local() throws Exception {
    Path executable = directory.resolve("local-fixture");
    Files.writeString(executable, "Synthetic executable fixture, never launched");
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

  private static AnnotationConfigApplicationContext context(MockEnvironment environment) {
    var context = new AnnotationConfigApplicationContext();
    context.setEnvironment(environment);
    context.register(VideoConfiguration.class);
    return context;
  }
}
