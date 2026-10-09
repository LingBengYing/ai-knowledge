package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.AudioEmbeddingModels;
import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.model.SiliconFlowImageEmbeddingModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.model.domain.DecodedAudio;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.repository.AudioVectorRepository;
import com.evidence.rag.repository.EvidenceRepository;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.AudioVectorIndexingService;
import com.evidence.rag.service.ImageVectorIndexingService;
import com.evidence.rag.service.ManagedTextRuntime;
import com.evidence.rag.service.RuntimeService;
import com.evidence.rag.worker.parser.AudioDecoder;
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

class AudioEmbeddingConfigurationTest {
  @TempDir Path directory;
  private static final String DECODER = "synthetic-decoder-v1";

  @Test
  void defaultOffNeedsNoModelDecoderOrRepository() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.setEnvironment(new MockEnvironment());
      context.register(AudioEmbeddingConfiguration.class);
      context.refresh();
      assertTrue(context.getBeansOfType(AudioEmbeddingModels.class).isEmpty());
      assertTrue(context.getBeansOfType(AudioEmbeddingSettings.class).isEmpty());
      assertTrue(context.getBeansOfType(AudioVectorIndexingService.class).isEmpty());
    }
  }

  @Test
  void explicitLocalConfigurationBindsDecoderAndIndependentAudioTargetWithoutDispatch() {
    var config = new AudioEmbeddingConfiguration();
    var settings = settings(local(), text(), null);
    try (var models = config.audioEmbeddingModels(settings);
        var projection = config.audioVectorProjection(settings)) {
      assertEquals(DECODER, models.decoderRevision());
      assertEquals(models.revision(), settings.target().embeddingIdentity());
      assertEquals(models.revision(), settings.target().modelRevision());
      assertEquals(projection.identity(), settings.target().projectionIdentity());
      assertEquals(2, settings.target().dimensions());
      assertEquals("java_audio_fixture", settings.projection().collection());
      assertNotEquals(text().projection().identity(), projection.identity());
      assertEquals(Duration.ofSeconds(120), settings.processingBudget());
      assertEquals(2, settings.maxConcurrent());
      assertEquals(settings.target(), config.audioVectorTarget(settings));
      assertFalse(settings.toString().contains("private"));
      assertFalse(settings.toString().contains("127.0.0.1"));
    }
    assertEquals(
        2,
        settings(
                local()
                    .withProperty("server.address", "::1")
                    .withProperty("rag.environment", "development"),
                text(),
                null)
            .target()
            .dimensions());
  }

  @Test
  void enabledGraphComposesCurrentDecoderAndBuildServiceWithNoDecodeOrRemotePreparation() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.setEnvironment(local());
      context.registerBean(RagProperties.class, AudioEmbeddingConfigurationTest::properties);
      context.registerBean(TextAdapterSettings.class, () -> text());
      context.registerBean(AudioDecoder.class, () -> decoder());
      context.registerBean(SqliteAuthorityStore.class, () -> new SqliteAuthorityStore(directory));
      context.registerBean(
          AudioVectorRepository.class,
          () -> new AudioVectorRepository(context.getBean(SqliteAuthorityStore.class)));
      context.registerBean(
          EvidenceRepository.class,
          () -> new EvidenceRepository(context.getBean(SqliteAuthorityStore.class)));
      context.registerBean(
          ManagementRepository.class,
          () -> new ManagementRepository(context.getBean(SqliteAuthorityStore.class)));
      context.registerBean(
          IngestionRepository.class,
          () -> new IngestionRepository(context.getBean(SqliteAuthorityStore.class)));
      context.registerBean(DocumentPermissionPolicy.class, () -> new DocumentPermissionPolicy());
      context.registerBean(
          "indexingTarget",
          IndexTarget.class,
          () -> new IndexingConfiguration().indexingTarget(text()),
          definition -> definition.setPrimary(true));
      context.register(AudioEmbeddingConfiguration.class);
      context.refresh();
      assertEquals(1, context.getBeansOfType(AudioVectorIndexingService.class).size());
      var models = context.getBean(AudioEmbeddingModels.class);
      assertEquals(
          models.revision(),
          context.getBean("audioVectorTarget", IndexTarget.class).modelRevision());
      assertEquals(
          text().projection().identity(), context.getBean(IndexTarget.class).projectionIdentity());
    }
  }

  @Test
  void rejectsNonlocalMissingDependenciesAndDecoderDriftBeforeDecode() {
    for (String dependency :
        List.of("audio", "ingestion", "indexing", "answers", "query-attachments")) {
      invalid(local().withProperty("rag." + dependency + ".enabled", "false"), text(), null);
    }
    for (String bind : List.of("localhost", "127.0.0.2", "0.0.0.0", "::")) {
      invalid(local().withProperty("server.address", bind), text(), null);
    }
    invalid(local().withProperty("rag.environment", "production"), text(), null);
    invalid(
        local().withProperty("rag.audio-embedding.decoder-revision", "other-decoder-v2"),
        text(),
        null);
  }

  @Test
  void separatesAllThreeSpacesAndEnforcesPinnedProfileAndTotalBudget() {
    for (String collection : List.of("java_other", "java_text_fixture", "java_image_fixture")) {
      invalid(
          local().withProperty("rag.audio-embedding.milvus.collection", collection), text(), null);
    }
    invalid(local(), text("java_audio_fixture"), null);
    var imageModels =
        new SiliconFlowImageEmbeddingModels.Configuration(
            new OpenAiCompatibleModels.Endpoint(
                URI.create("http://127.0.0.1:1"), "image-model", "synthetic-key"),
            "image-v1",
            2,
            Duration.ofSeconds(3),
            65536,
            true);
    try (var client = new SiliconFlowImageEmbeddingModels(imageModels)) {
      var projection = projection("java_audio_fixture", client.revision());
      var image =
          new ImageEmbeddingSettings(
              imageModels,
              projection,
              new IndexTarget(client.revision(), projection.identity(), client.revision(), 2),
              Duration.ofSeconds(30),
              2);
      invalid(local(), text(), image);
    }
    for (String revision : List.of("latest", "default", "unknown", "")) {
      invalid(local().withProperty("rag.audio-embedding.revision", revision), text(), null);
    }
    for (String budget : List.of("9", "120001", "bad")) {
      invalid(
          local().withProperty("rag.audio-embedding.processing-timeout-ms", budget), text(), null);
    }
    for (String capacity : List.of("0", "9")) {
      invalid(local().withProperty("rag.audio-embedding.max-concurrent", capacity), text(), null);
    }
    var original = settings(local(), text(), null);
    var changed =
        settings(local().withProperty("rag.audio-embedding.revision", "audio-v2"), text(), null);
    assertNotEquals(original.target(), changed.target());
  }

  @Test
  void enabledIndependentImageProfileCanCoexistWithoutSharingTheAudioCollection() {
    var imageModels =
        new SiliconFlowImageEmbeddingModels.Configuration(
            new OpenAiCompatibleModels.Endpoint(
                URI.create("http://127.0.0.1:1"), "image-model", "synthetic-key"),
            "image-v1",
            2,
            Duration.ofSeconds(3),
            65536,
            true);
    try (var client = new SiliconFlowImageEmbeddingModels(imageModels)) {
      var projection = projection("java_image_fixture", client.revision());
      var image =
          new ImageEmbeddingSettings(
              imageModels,
              projection,
              new IndexTarget(client.revision(), projection.identity(), client.revision(), 2),
              Duration.ofSeconds(30),
              2);
      var audio = settings(local(), text(), image);
      assertEquals("java_audio_fixture", audio.projection().collection());
      assertNotEquals(image.target().embeddingIdentity(), audio.target().embeddingIdentity());
      assertNotEquals(image.target().projectionIdentity(), audio.target().projectionIdentity());
    }
  }

  @Test
  void enabledRuntimePropertyWithoutAnAssembledServiceDoesNotAdvertiseAudioVectors()
      throws Exception {
    var environment = runtimeEnvironment();
    var beans = new DefaultListableBeanFactory();
    var runtime = configuredRuntime(environment, beans);
    assertTrue(runtime.capabilities().capabilities().contains("query_attachments"));
    assertFalse(runtime.capabilities().capabilities().contains("audio_vector_retrieval"));
  }

  @Test
  void actualOfflineServiceAndCurrentOptInAreBothRequiredForRuntimeCapability() throws Exception {
    var environment = runtimeEnvironment();
    var settings = settings(environment, text(), null);
    var beans = new DefaultListableBeanFactory();
    beans.registerSingleton("indexingTarget", new IndexingConfiguration().indexingTarget(text()));
    try (var store = new SqliteAuthorityStore(directory.resolve("authority"))) {
      var service =
          new AudioEmbeddingConfiguration()
              .audioVectorIndexingService(
                  store,
                  new AudioVectorRepository(store),
                  new EvidenceRepository(store),
                  new ManagementRepository(store),
                  new IngestionRepository(store),
                  new DocumentPermissionPolicy(),
                  beans.getBeanProvider(IndexTarget.class),
                  settings,
                  decoder(),
                  beans.getBeanProvider(ManagedTextRuntime.class));
      beans.registerSingleton("audioVectorIndexingService", service);
      assertTrue(
          configuredRuntime(environment, beans)
              .capabilities()
              .capabilities()
              .contains("audio_vector_retrieval"));
      assertFalse(
          configuredRuntime(environment.withProperty("rag.audio-embedding.enabled", "false"), beans)
              .capabilities()
              .capabilities()
              .contains("audio_vector_retrieval"));
      environment
          .withProperty("rag.audio-embedding.enabled", "true")
          .withProperty("rag.audio.enabled", "false");
      assertFalse(
          configuredRuntime(environment, beans)
              .capabilities()
              .capabilities()
              .contains("audio_vector_retrieval"));
    }
  }

  private MockEnvironment runtimeEnvironment() throws Exception {
    Path executable =
        Files.writeString(
            directory.resolve("unused-runtime-ocr"), "synthetic fixture, never executed");
    assertTrue(executable.toFile().setExecutable(true));
    return local()
        .withProperty("rag.visual.enabled", "true")
        .withProperty("rag.video.enabled", "true")
        .withProperty("rag.image-ocr.enabled", "true")
        .withProperty("rag.image-ocr.executable", executable.toString())
        .withProperty("rag.image-ocr.revision", "runtime-fixture-v1");
  }

  private static RuntimeService configuredRuntime(
      MockEnvironment environment, DefaultListableBeanFactory beans) {
    return new RuntimeConfiguration()
        .configuredRuntimeService(
            properties(),
            new IngestionSettings(true, 30000, 30000),
            new IndexingSettings(true, 30000),
            new AnswersSettings(true, 30000, 2),
            new DocumentRemovalSettings(false),
            environment,
            beans.getBeanProvider(ImageVectorIndexingService.class),
            beans.getBeanProvider(AudioVectorIndexingService.class));
  }

  private static AudioEmbeddingSettings settings(
      MockEnvironment environment, TextAdapterSettings text, ImageEmbeddingSettings image) {
    var beans = new DefaultListableBeanFactory();
    if (image != null) {
      beans.registerSingleton("imageEmbeddingSettings", image);
    }
    return new AudioEmbeddingConfiguration()
        .audioEmbeddingSettings(
            environment,
            properties(),
            text,
            decoder(),
            beans.getBeanProvider(ImageEmbeddingSettings.class));
  }

  private static void invalid(
      MockEnvironment environment, TextAdapterSettings text, ImageEmbeddingSettings image) {
    var error =
        assertThrows(IllegalArgumentException.class, () -> settings(environment, text, image));
    assertEquals("Invalid local audio embedding configuration", error.getMessage());
    assertNull(error.getCause());
  }

  private static AudioDecoder decoder() {
    return new AudioDecoder() {
      @Override
      public String revision() {
        return DECODER;
      }

      @Override
      public DecodedAudio decode(String filename, String mime, byte[] source) {
        throw new AssertionError("Configuration must not decode");
      }

      @Override
      public void close() {}
    };
  }

  private static MockEnvironment local() {
    return new MockEnvironment()
        .withProperty("server.address", "127.0.0.1")
        .withProperty("rag.environment", "test")
        .withProperty("rag.audio-embedding.enabled", "true")
        .withProperty("rag.audio.enabled", "true")
        .withProperty("rag.ingestion.enabled", "true")
        .withProperty("rag.indexing.enabled", "true")
        .withProperty("rag.answers.enabled", "true")
        .withProperty("rag.query-attachments.enabled", "true")
        .withProperty("rag.audio-embedding.base-url", "http://127.0.0.1:1")
        .withProperty("rag.audio-embedding.model", "synthetic-audio-model")
        .withProperty("rag.audio-embedding.api-key", "private-synthetic-key")
        .withProperty("rag.audio-embedding.revision", "audio-v1")
        .withProperty("rag.audio-embedding.decoder-revision", DECODER)
        .withProperty("rag.audio-embedding.dimensions", "2")
        .withProperty("rag.audio-embedding.allow-loopback-http", "true")
        .withProperty("rag.audio-embedding.milvus.endpoint", "http://127.0.0.1:1")
        .withProperty("rag.audio-embedding.milvus.collection", "java_audio_fixture")
        .withProperty("rag.audio-embedding.milvus.allow-loopback-http", "true");
  }

  private static RagProperties properties() {
    return new RagProperties(
        "test", "org-main", "development_headers", "", "", "", Path.of("unused-audio-embedding"));
  }

  private static TextAdapterSettings text() {
    return text("java_text_fixture");
  }

  private static TextAdapterSettings text(String collection) {
    var endpoint =
        new OpenAiCompatibleModels.Endpoint(
            URI.create("http://127.0.0.1:1"), "text-model", "synthetic-key");
    return new TextAdapterSettings(
        new OpenAiCompatibleModels.Configuration(
            endpoint, endpoint, endpoint, 2, Duration.ofSeconds(3), 65536, true),
        projection(collection, "text-model-v1"));
  }

  private static MilvusRestProjection.Settings projection(String collection, String identity) {
    return new MilvusRestProjection.Settings(
        URI.create("http://127.0.0.1:1"),
        "",
        "default",
        collection,
        "org-main",
        identity,
        2,
        Duration.ofSeconds(3),
        65536,
        true);
  }
}
