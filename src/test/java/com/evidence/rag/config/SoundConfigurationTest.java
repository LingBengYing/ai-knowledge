package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.GeminiAudioEmbeddingModels;
import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.model.SiliconFlowImageEmbeddingModels;
import com.evidence.rag.client.model.SoundEmbeddingModels;
import com.evidence.rag.client.model.SoundModels;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SoundRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.RuntimeService;
import com.evidence.rag.service.SoundAnswerService;
import com.evidence.rag.service.SoundCompilationService;
import com.evidence.rag.service.SoundLibraryService;
import com.evidence.rag.web.ProblemHandler;
import com.evidence.rag.worker.parser.AudioDecoder;
import com.evidence.rag.worker.parser.ProcessAudioDecoder;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

class SoundConfigurationTest {
  @TempDir Path directory;

  @Test
  void disabledSoundRequiresNoCodecsModelsOrNetwork() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.setEnvironment(new MockEnvironment());
      context.register(SoundConfiguration.class);
      context.refresh();
      assertTrue(context.getBeansOfType(SoundModels.class).isEmpty());
      assertTrue(context.getBeansOfType(SoundLibraryService.class).isEmpty());
    }
  }

  @Test
  void wholeIndependentGraphStartsWithoutAsrTextOrDecoderTypeAmbiguity() throws Exception {
    var env = environment();
    try (var context = new AnnotationConfigApplicationContext()) {
      context.setEnvironment(env);
      context.registerBean(RagProperties.class, () -> properties());
      context.registerBean(
          SqliteAuthorityStore.class, () -> new SqliteAuthorityStore(directory.resolve("store")));
      context.registerBean(
          ManagementRepository.class,
          () -> new ManagementRepository(context.getBean(SqliteAuthorityStore.class)));
      context.registerBean(
          SoundRepository.class,
          () -> new SoundRepository(context.getBean(SqliteAuthorityStore.class)));
      context.registerBean(DocumentPermissionPolicy.class, () -> new DocumentPermissionPolicy());
      context.registerBean(
          IngestionSettings.class, () -> new IngestionSettings(true, 30000, 30000));
      context.registerBean(IndexingSettings.class, () -> new IndexingSettings(false, 30000));
      context.registerBean(AnswersSettings.class, () -> new AnswersSettings(false, 30000, 2));
      context.registerBean(DocumentRemovalSettings.class, () -> new DocumentRemovalSettings(false));
      context.registerBean(ProblemHandler.class, () -> new ProblemHandler());
      context.registerBean(JsonMapper.class, () -> JsonMapper.builder().build());
      context.register(SoundConfiguration.class, RuntimeConfiguration.class);
      context.refresh();
      assertNotNull(context.getBean(SoundLibraryService.class));
      assertNotNull(context.getBean(SoundAnswerService.class));
      assertTrue(context.getBeansOfType(AudioDecoder.class).isEmpty());
      assertTrue(context.getBeansOfType(TextModels.class).isEmpty());
      var settings = context.getBean(SoundSettings.class);
      assertEquals(
          settings.embedding().revision(), context.getBean(SoundEmbeddingModels.class).revision());
      assertEquals(settings.models().revision(), context.getBean(SoundModels.class).revision());
      var capabilities = context.getBean(RuntimeService.class).capabilities().capabilities();
      assertTrue(
          capabilities.containsAll(
              List.of(
                  "sound_upload",
                  "sound_index",
                  "sound_answers",
                  "sound_sources",
                  "sound_query_attachments")));
      assertFalse(capabilities.contains("audio_answers"));
    }
  }

  @Test
  void publicBindsAndMissingIngestionFailBeforeResourceConstruction() throws Exception {
    var configuration = new SoundConfiguration();
    for (String[] pair :
        new String[][] {
          {"server.address", "localhost"},
          {"server.address", "0.0.0.0"},
          {"rag.environment", "production"},
          {"rag.ingestion.enabled", "false"},
          {"rag.sound.decoder-revision", "wrong-decoder"},
          {"rag.sound.chunk-seconds", "31"}
        }) {
      var env = environment().withProperty(pair[0], pair[1]);
      var ex =
          assertThrows(
              IllegalArgumentException.class, () -> configuration.soundCompilationService(env));
      assertEquals("Invalid local sound configuration", ex.getMessage());
      assertNull(ex.getCause());
    }
  }

  @Test
  void settingsRejectInconsistentSpacesAndInvalidBudgetsWithoutNetwork() throws Exception {
    var env = environment();
    var config = new SoundConfiguration();
    var beans = new DefaultListableBeanFactory();
    try (var compilation = config.soundCompilationService(env)) {
      var settings = settings(config, env, compilation, beans);
      assertEquals("SoundSettings[redacted]", settings.toString());
      for (int accepted : new int[] {1, 2}) {
        assertEquals(
            accepted,
            new SoundSettings(
                    settings.models(),
                    settings.embedding(),
                    settings.projection(),
                    settings.target(),
                    settings.processingBudget(),
                    accepted)
                .maxConcurrent());
      }
      for (int rejected : new int[] {3, 8}) {
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new SoundSettings(
                    settings.models(),
                    settings.embedding(),
                    settings.projection(),
                    settings.target(),
                    settings.processingBudget(),
                    rejected));
      }
      for (String[] pair :
          new String[][] {
            {"rag.sound.milvus.collection", "java_text"},
            {"rag.sound.embedding.dimensions", "3073"},
            {"rag.sound.decoder-revision", "wrong"},
            {"rag.sound.processing-timeout-ms", "120001"},
            {"rag.sound.max-concurrent", "0"},
            {"rag.sound.revision", "latest"}
          }) {
        var invalidEnvironment = environment().withProperty(pair[0], pair[1]);
        assertThrows(
            IllegalArgumentException.class,
            () -> settings(config, invalidEnvironment, compilation, beans));
      }
    }
  }

  @Test
  void profileAndProjectionDriftCannotAssembleAUsableSoundSpace() throws Exception {
    var config = new SoundConfiguration();
    var environment = environment();
    try (var compilation = config.soundCompilationService(environment)) {
      var current = settings(config, environment, compilation, new DefaultListableBeanFactory());
      for (IndexTarget stale :
          List.of(
              new IndexTarget(
                  "other-embedding",
                  current.target().projectionIdentity(),
                  current.target().modelRevision(),
                  3),
              new IndexTarget(
                  current.target().embeddingIdentity(),
                  "a".repeat(64),
                  current.target().modelRevision(),
                  3),
              new IndexTarget(
                  current.target().embeddingIdentity(),
                  current.target().projectionIdentity(),
                  "other-model",
                  3),
              new IndexTarget(
                  current.target().embeddingIdentity(),
                  current.target().projectionIdentity(),
                  current.target().modelRevision(),
                  4))) {
        var failure =
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    new SoundSettings(
                        current.models(),
                        current.embedding(),
                        current.projection(),
                        stale,
                        current.processingBudget(),
                        2));
        assertFalse(failure.getMessage().contains("fixture-key"));
        assertNull(failure.getCause());
      }
    }
  }

  @Test
  void independentCollectionCannotReuseAnyConfiguredTextImageOrSpeechSpace() throws Exception {
    var environment = environment();
    var config = new SoundConfiguration();
    var beans = new DefaultListableBeanFactory();
    var endpoint =
        new OpenAiCompatibleModels.Endpoint(
            URI.create("http://127.0.0.1:1"), "fixture-model", "fixture-key");
    var text =
        new OpenAiCompatibleModels.Configuration(
            endpoint, endpoint, endpoint, 3, Duration.ofSeconds(5), 65536, true);
    beans.registerSingleton(
        "text", new TextAdapterSettings(text, projection("java_text_fixture", "text-v1", 3)));
    var image =
        new SiliconFlowImageEmbeddingModels.Configuration(
            endpoint, "image-v1", 3, Duration.ofSeconds(5), 65536, true);
    try (var imageClient = new SiliconFlowImageEmbeddingModels(image)) {
      var imageProjection = projection("java_image_fixture", imageClient.revision(), 3);
      beans.registerSingleton(
          "image",
          new ImageEmbeddingSettings(
              image,
              imageProjection,
              new IndexTarget(
                  imageClient.revision(), imageProjection.identity(), imageClient.revision(), 3),
              Duration.ofSeconds(5),
              2));
    }
    var audio =
        new GeminiAudioEmbeddingModels.Configuration(
            endpoint, "audio-v1", 3, "decoder-v1", Duration.ofSeconds(5), 65536, true);
    try (var audioClient = new GeminiAudioEmbeddingModels(audio)) {
      var audioProjection = projection("java_audio_fixture", audioClient.revision(), 3);
      beans.registerSingleton(
          "audio",
          new AudioEmbeddingSettings(
              audio,
              audioProjection,
              new IndexTarget(
                  audioClient.revision(), audioProjection.identity(), audioClient.revision(), 3),
              Duration.ofSeconds(5),
              2));
    }
    try (var compilation = config.soundCompilationService(environment)) {
      assertEquals(
          "java_sound_fixture",
          settings(config, environment, compilation, beans).projection().collection());
      for (String collection :
          List.of("java_text_fixture", "java_image_fixture", "java_audio_fixture")) {
        var collision = environment().withProperty("rag.sound.milvus.collection", collection);
        var failure =
            assertThrows(
                IllegalArgumentException.class,
                () -> settings(config, collision, compilation, beans));
        assertEquals("Invalid local sound configuration", failure.getMessage());
        assertNull(failure.getCause());
      }
    }
  }

  @Test
  void literalIpv6DevelopmentBindingWorksButClosedCompilerCannotAssembleModels() throws Exception {
    var environment =
        environment()
            .withProperty("server.address", "::1")
            .withProperty("rag.environment", "development");
    var config = new SoundConfiguration();
    var compilation = config.soundCompilationService(environment);
    assertNotNull(settings(config, environment, compilation, new DefaultListableBeanFactory()));
    compilation.close();
    assertThrows(
        IllegalArgumentException.class,
        () -> settings(config, environment, compilation, new DefaultListableBeanFactory()));
  }

  @Test
  void projectionDimensionIdentityAndShortBudgetAreRejectedBeforeClientCreation() throws Exception {
    var environment = environment();
    var config = new SoundConfiguration();
    try (var compilation = config.soundCompilationService(environment)) {
      var current = settings(config, environment, compilation, new DefaultListableBeanFactory());
      for (var projection :
          List.of(
              projection("java_sound_fixture", current.embedding().revision(), 4),
              projection("java_sound_fixture", "different-space", 3))) {
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new SoundSettings(
                    current.models(),
                    current.embedding(),
                    projection,
                    current.target(),
                    current.processingBudget(),
                    2));
      }
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new SoundSettings(
                  current.models(),
                  current.embedding(),
                  current.projection(),
                  current.target(),
                  Duration.ofMillis(9),
                  2));
    }
  }

  private static MilvusRestProjection.Settings projection(
      String collection, String revision, int dimensions) {
    return new MilvusRestProjection.Settings(
        URI.create("http://127.0.0.1:1"),
        "",
        "default",
        collection,
        "org",
        revision,
        dimensions,
        Duration.ofSeconds(5),
        65536,
        true);
  }

  @Test
  void oldConstructorsNeverAdvertiseSoundAndSoundDoesNotRequireLegacyAnswers() {
    var old = new RuntimeService("development_headers", "org", true, false);
    assertFalse(old.capabilities().capabilities().contains("sound_upload"));
    for (boolean ingestion : new boolean[] {true, false}) {
      var runtime =
          new RuntimeService(
              "development_headers",
              "org",
              ingestion,
              false,
              false,
              false,
              false,
              false,
              false,
              false,
              false,
              false,
              false,
              false,
              false,
              false,
              true);
      assertEquals(ingestion, runtime.capabilities().capabilities().contains("sound_answers"));
    }
  }

  private SoundSettings settings(
      SoundConfiguration config,
      MockEnvironment env,
      SoundCompilationService compilation,
      DefaultListableBeanFactory beans) {
    return config.soundSettings(
        env,
        properties(),
        compilation,
        beans.getBeanProvider(TextAdapterSettings.class),
        beans.getBeanProvider(ImageEmbeddingSettings.class),
        beans.getBeanProvider(AudioEmbeddingSettings.class));
  }

  private RagProperties properties() {
    return new RagProperties(
        "test", "org", "development_headers", "", "", "", directory.resolve("data"));
  }

  private MockEnvironment environment() throws Exception {
    Path executable = directory.resolve("synthetic-codec");
    Files.writeString(executable, "synthetic codec, never executed");
    assertTrue(executable.toFile().setExecutable(true));
    String revision;
    try (var decoder = new ProcessAudioDecoder(executable, executable, Duration.ofSeconds(30))) {
      revision = decoder.revision();
    }
    return new MockEnvironment()
        .withProperty("rag.sound.enabled", "true")
        .withProperty("server.address", "127.0.0.1")
        .withProperty("rag.environment", "test")
        .withProperty("rag.ingestion.enabled", "true")
        .withProperty("rag.sound.ffmpeg-executable", executable.toString())
        .withProperty("rag.sound.ffprobe-executable", executable.toString())
        .withProperty("rag.sound.decoder-revision", revision)
        .withProperty("rag.sound.base-url", "http://127.0.0.1:1")
        .withProperty("rag.sound.model", "sound-fixture")
        .withProperty("rag.sound.api-key", "fixture-key")
        .withProperty("rag.sound.revision", "fixture-v1")
        .withProperty("rag.sound.allow-loopback-http", "true")
        .withProperty("rag.sound.embedding.base-url", "http://127.0.0.1:1")
        .withProperty("rag.sound.embedding.model", "embedding-fixture")
        .withProperty("rag.sound.embedding.api-key", "fixture-key")
        .withProperty("rag.sound.embedding.revision", "fixture-v1")
        .withProperty("rag.sound.embedding.dimensions", "3")
        .withProperty("rag.sound.embedding.allow-loopback-http", "true")
        .withProperty("rag.sound.milvus.endpoint", "http://127.0.0.1:1")
        .withProperty("rag.sound.milvus.collection", "java_sound_fixture")
        .withProperty("rag.sound.milvus.allow-loopback-http", "true");
  }
}
