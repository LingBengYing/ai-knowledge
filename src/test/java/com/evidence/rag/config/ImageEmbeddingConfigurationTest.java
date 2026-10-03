package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.ImageEmbeddingModels;
import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.repository.EvidenceRepository;
import com.evidence.rag.repository.ImageVectorRepository;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.ImageVectorIndexingService;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

class ImageEmbeddingConfigurationTest {
  @TempDir Path directory;

  @Test
  void disabledFeatureNeedsNoProviderOrLibraryBeans() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.setEnvironment(new MockEnvironment());
      context.register(ImageEmbeddingConfiguration.class);
      context.refresh();
      assertTrue(context.getBeansOfType(ImageEmbeddingModels.class).isEmpty());
      assertTrue(context.getBeansOfType(ImageEmbeddingSettings.class).isEmpty());
      assertTrue(context.getBeansOfType(ImageVectorIndexingService.class).isEmpty());
    }
  }

  @Test
  void localExplicitConfigurationCreatesMatchingIndependentTargetsWithoutCallingThem() {
    var configuration = new ImageEmbeddingConfiguration();
    var settings = configuration.imageEmbeddingSettings(local(), properties(), text());
    try (var models = configuration.imageEmbeddingModels(settings);
        var projection = configuration.imageVectorProjection(settings)) {
      var target = configuration.imageVectorTarget(settings);
      assertEquals(models.revision(), target.embeddingIdentity());
      assertEquals(models.revision(), target.modelRevision());
      assertEquals(models.dimensions(), target.dimensions());
      assertEquals(projection.identity(), target.projectionIdentity());
      assertEquals("java_image_fixture", settings.projection().collection());
      assertNotEquals(text().projection().identity(), projection.identity());
      assertEquals(Duration.ofMillis(120000), settings.processingBudget());
      assertEquals(2, settings.maxConcurrent());
      assertFalse(settings.toString().contains("synthetic-image-key"));
      assertFalse(settings.toString().contains("127.0.0.1"));
    }
    assertEquals(
        2,
        configuration
            .imageEmbeddingSettings(
                local()
                    .withProperty("server.address", "::1")
                    .withProperty("rag.environment", "development"),
                properties(),
                text())
            .target()
            .dimensions());
  }

  @Test
  void enabledSpringGraphComposesBuildAndQueryResourcesWithoutRemotePreparation() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.setEnvironment(local());
      context.registerBean(RagProperties.class, ImageEmbeddingConfigurationTest::properties);
      context.registerBean(TextAdapterSettings.class, () -> text());
      context.registerBean(SqliteAuthorityStore.class, () -> new SqliteAuthorityStore(directory));
      context.registerBean(
          ImageVectorRepository.class,
          () -> new ImageVectorRepository(context.getBean(SqliteAuthorityStore.class)));
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
      context.register(ImageEmbeddingConfiguration.class);
      context.refresh();
      assertEquals(1, context.getBeansOfType(ImageVectorIndexingService.class).size());
      var models = context.getBean(ImageEmbeddingModels.class);
      var target = context.getBean("imageVectorTarget", IndexTarget.class);
      assertEquals(models.revision(), target.embeddingIdentity());
      assertEquals(
          target.projectionIdentity(),
          context.getBean("imageVectorProjection", MilvusRestProjection.class).identity());
      assertEquals(
          text().projection().embeddingIdentity(),
          context.getBean(IndexTarget.class).embeddingIdentity());
    }
  }

  @Test
  void nonlocalOrMissingPrerequisitesFailWithSanitizedErrors() {
    for (String dependency : List.of("indexing", "visual", "answers", "query-attachments")) {
      invalid(local().withProperty("rag." + dependency + ".enabled", "false"));
    }
    for (String bind : List.of("0.0.0.0", "localhost", "127.0.0.2", "::")) {
      invalid(local().withProperty("server.address", bind));
    }
    invalid(local().withProperty("rag.environment", "production"));
  }

  @Test
  void independentCollectionPinnedProfileAndBuildLimitsAreRequired() {
    for (String collection : List.of("java_text_fixture", "java_vectors", "other_image")) {
      invalid(local().withProperty("rag.image-embedding.milvus.collection", collection));
    }
    for (String revision : List.of("", "latest", "unknown")) {
      invalid(local().withProperty("rag.image-embedding.revision", revision));
    }
    for (String deadline : List.of("9", "120001", "invalid")) {
      invalid(local().withProperty("rag.image-embedding.processing-timeout-ms", deadline));
    }
    for (String concurrent : List.of("0", "9")) {
      invalid(local().withProperty("rag.image-embedding.max-concurrent", concurrent));
    }
    invalid(
        local()
            .withProperty("rag.image-embedding.base-url", "https://private.invalid/?key=secret"));
    invalid(local().withProperty("rag.image-embedding.dimensions", "1"));
    invalid(local().withProperty("rag.image-embedding.milvus.deadline-ms", "60001"));
    invalid(
        local().withProperty("rag.image-embedding.milvus.collection", "java_image_fixture"),
        text("java_image_fixture"));
    var first =
        new ImageEmbeddingConfiguration().imageEmbeddingSettings(local(), properties(), text());
    var changed =
        new ImageEmbeddingConfiguration()
            .imageEmbeddingSettings(
                local().withProperty("rag.image-embedding.revision", "image-v2"),
                properties(),
                text());
    assertNotEquals(first.target(), changed.target());
  }

  private static void invalid(MockEnvironment environment) {
    invalid(environment, text());
  }

  private static void invalid(MockEnvironment environment, TextAdapterSettings text) {
    var failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new ImageEmbeddingConfiguration()
                    .imageEmbeddingSettings(environment, properties(), text));
    assertEquals("Invalid local image embedding configuration", failure.getMessage());
    assertNull(failure.getCause());
  }

  private static MockEnvironment local() {
    return new MockEnvironment()
        .withProperty("server.address", "127.0.0.1")
        .withProperty("rag.environment", "test")
        .withProperty("rag.image-embedding.enabled", "true")
        .withProperty("rag.indexing.enabled", "true")
        .withProperty("rag.visual.enabled", "true")
        .withProperty("rag.answers.enabled", "true")
        .withProperty("rag.query-attachments.enabled", "true")
        .withProperty("rag.image-embedding.base-url", "http://127.0.0.1:1/v1")
        .withProperty("rag.image-embedding.model", "synthetic-image-model")
        .withProperty("rag.image-embedding.api-key", "synthetic-image-key")
        .withProperty("rag.image-embedding.revision", "image-v1")
        .withProperty("rag.image-embedding.dimensions", "2")
        .withProperty("rag.image-embedding.allow-loopback-http", "true")
        .withProperty("rag.image-embedding.milvus.endpoint", "http://127.0.0.1:1")
        .withProperty("rag.image-embedding.milvus.collection", "java_image_fixture")
        .withProperty("rag.image-embedding.milvus.allow-loopback-http", "true");
  }

  private static RagProperties properties() {
    return new RagProperties(
        "test",
        "org-main",
        "development_headers",
        "",
        "",
        "",
        Path.of("unused-image-configuration"));
  }

  private static TextAdapterSettings text() {
    return text("java_text_fixture");
  }

  private static TextAdapterSettings text(String collection) {
    var endpoint =
        new OpenAiCompatibleModels.Endpoint(
            URI.create("http://127.0.0.1:1/v1"), "text-model", "synthetic-text-key");
    return new TextAdapterSettings(
        new OpenAiCompatibleModels.Configuration(
            endpoint, endpoint, endpoint, 2, Duration.ofSeconds(3), 65536, true),
        new MilvusRestProjection.Settings(
            URI.create("http://127.0.0.1:1"),
            "",
            "default",
            collection,
            "org-main",
            "text-embedding-v1",
            2,
            Duration.ofSeconds(3),
            65536,
            true));
  }
}
