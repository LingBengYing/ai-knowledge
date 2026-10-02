package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.AudioModels;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.service.AudioCompilationService;
import com.evidence.rag.tool.parser.AudioPcm;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.worker.parser.AudioDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

class AudioConfigurationTest {
  @TempDir Path directory;

  @Test
  void disabledAudioDoesNotLoadResourcesOrRequireAnyProviderSettings() {
    try (var context = context(new MockEnvironment().withProperty("server.address", "0.0.0.0"))) {
      context.refresh();
      assertTrue(context.getBeansOfType(AudioDecoder.class).isEmpty());
      assertTrue(context.getBeansOfType(AudioModels.class).isEmpty());
      assertTrue(context.getBeansOfType(AudioCompilationService.class).isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"127.0.0.1", "::1"})
  void localOptInCreatesIndependentAudioPipelineWithoutAnswersOrRequests(String address)
      throws Exception {
    var environment = local().withProperty("server.address", address);
    try (var context = context(environment)) {
      context.refresh();
      var decoder = context.getBean(AudioDecoder.class);
      var models = context.getBean(AudioModels.class);
      var compiler = context.getBean(AudioCompilationService.class);
      assertNotNull(compiler);
      assertEquals(
          new AudioCompilationService(decoder, models, 15, Duration.ofMinutes(10)).revision(),
          compiler.revision());
      assertTrue(compiler.revision().startsWith("java-audio-compiler-v1:"));
      assertFalse(models.toString().contains("synthetic-audio-credential"));
      assertTrue(context.getBeansOfType(TextAdapterSettings.class).isEmpty());
      assertTrue(context.getBeansOfType(TextModels.class).isEmpty());
    }
  }

  @Test
  void springClosesNativeDecoderAndModelClientWithItsContext() throws Exception {
    AudioDecoder decoder;
    AudioModels models;
    try (var context = context(local())) {
      context.refresh();
      decoder = context.getBean(AudioDecoder.class);
      models = context.getBean(AudioModels.class);
    }
    byte[] wav = AudioPcm.wav(new byte[2], 0, 2);
    assertEquals(
        "parser_closed",
        assertThrows(TextParser.Failure.class, () -> decoder.decode("voice.wav", "audio/wav", wav))
            .code());
    assertEquals(
        "model_closed",
        assertThrows(TextModels.Failure.class, () -> models.transcribe(wav)).code());
  }

  @Test
  void customSegmentLengthIsBoundIntoTheCompiledProfile() throws Exception {
    var environment =
        local()
            .withProperty("rag.environment", "development")
            .withProperty("rag.audio.chunk-seconds", "8")
            .withProperty("rag.audio.compilation-budget-ms", "120000");
    var configuration = new AudioConfiguration();
    try (var decoder = configuration.audioDecoder(environment);
        var models = configuration.audioModels(environment)) {
      var compiler = configuration.audioCompilationService(decoder, models, environment);
      assertEquals(
          new AudioCompilationService(decoder, models, 8, Duration.ofMinutes(2)).revision(),
          compiler.revision());
    }
  }

  @Test
  void invalidRuntimeConfigurationIsSanitizedForEachResource() throws Exception {
    var configuration = new AudioConfiguration();
    for (String[] invalid :
        new String[][] {
          {"server.address", "localhost"},
          {"server.address", "0.0.0.0"},
          {"rag.environment", "production"},
          {"rag.ingestion.enabled", "false"},
          {"rag.ingestion.enabled", "malformed"}
        }) {
      var environment = local().withProperty(invalid[0], invalid[1]);
      assertSanitized(() -> configuration.audioDecoder(environment));
      assertSanitized(() -> configuration.audioModels(environment));
    }
  }

  @Test
  void requiredCodecPathsAndIndependentAsrLimitsFailBeforeAnyRequest() throws Exception {
    var configuration = new AudioConfiguration();
    for (String[] invalid :
        new String[][] {
          {"rag.audio.ffmpeg-executable", "relative-ffmpeg"},
          {"rag.audio.ffprobe-executable", ""},
          {"rag.audio.decode-deadline-ms", "60001"}
        }) {
      var environment = local().withProperty(invalid[0], invalid[1]);
      assertSanitized(() -> configuration.audioDecoder(environment));
    }
    for (String[] invalid :
        new String[][] {
          {"rag.audio.base-url", "private bad endpoint"},
          {"rag.audio.model", ""},
          {"rag.audio.api-key", ""},
          {"rag.audio.deadline-ms", "60001"},
          {"rag.audio.max-response-bytes", "1023"},
          {"rag.audio.allow-loopback-http", "false"}
        }) {
      var environment = local().withProperty(invalid[0], invalid[1]);
      assertSanitized(() -> configuration.audioModels(environment));
    }
  }

  @Test
  void compilationLimitsAreValidatedDuringComposition() throws Exception {
    var configuration = new AudioConfiguration();
    var environment = local();
    try (var decoder = configuration.audioDecoder(environment);
        var models = configuration.audioModels(environment)) {
      for (String[] invalid :
          new String[][] {
            {"rag.audio.chunk-seconds", "0"},
            {"rag.audio.chunk-seconds", "31"},
            {"rag.audio.compilation-budget-ms", "9"},
            {"rag.audio.compilation-budget-ms", "600001"}
          }) {
        var malformed = local().withProperty(invalid[0], invalid[1]);
        assertSanitized(() -> configuration.audioCompilationService(decoder, models, malformed));
      }
    }
  }

  private MockEnvironment local() throws Exception {
    Path executable = directory.resolve("audio-codec-fixture");
    Files.writeString(executable, "synthetic codec fixture, never launched");
    assertTrue(executable.toFile().setExecutable(true));
    return new MockEnvironment()
        .withProperty("rag.audio.enabled", "true")
        .withProperty("server.address", "127.0.0.1")
        .withProperty("rag.environment", "test")
        .withProperty("rag.ingestion.enabled", "true")
        .withProperty("rag.answers.enabled", "false")
        .withProperty("rag.audio.ffmpeg-executable", executable.toString())
        .withProperty("rag.audio.ffprobe-executable", executable.toString())
        .withProperty("rag.audio.base-url", "http://127.0.0.1:1/v1")
        .withProperty("rag.audio.model", "synthetic-asr")
        .withProperty("rag.audio.api-key", "synthetic-audio-credential")
        .withProperty("rag.audio.allow-loopback-http", "true");
  }

  private static AnnotationConfigApplicationContext context(MockEnvironment environment) {
    var context = new AnnotationConfigApplicationContext();
    context.setEnvironment(environment);
    context.register(AudioConfiguration.class);
    return context;
  }

  private static void assertSanitized(org.junit.jupiter.api.function.Executable action) {
    var failure = assertThrows(IllegalArgumentException.class, action);
    assertEquals("Invalid local audio configuration", failure.getMessage());
    assertNull(failure.getCause());
  }
}
