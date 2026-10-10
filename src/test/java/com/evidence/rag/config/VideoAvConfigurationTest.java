package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.model.VideoAvEmbeddingModels;
import com.evidence.rag.client.model.VideoAvModels;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.VideoAvTargets;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.repository.VideoAvRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.RuntimeService;
import com.evidence.rag.service.VideoAvAnswerService;
import com.evidence.rag.service.VideoAvCompilationService;
import com.evidence.rag.service.VideoAvLibraryService;
import com.evidence.rag.web.ProblemHandler;
import com.evidence.rag.worker.parser.ProcessVideoAvDecoder;
import com.evidence.rag.worker.parser.VideoDecoder;
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

class VideoAvConfigurationTest {
  @TempDir Path directory;

  @Test
  void disabledModuleRequiresNoModelCodecOrOtherFeatureConfiguration() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.setEnvironment(new MockEnvironment());
      context.register(VideoAvConfiguration.class);
      context.refresh();
      assertTrue(context.getBeansOfType(VideoAvModels.class).isEmpty());
      assertTrue(context.getBeansOfType(VideoAvLibraryService.class).isEmpty());
    }
  }

  @Test
  void completeGraphHasTwoDistinctPinnedSpacesAndFourActualCapabilities() throws Exception {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.setEnvironment(environment());
      context.registerBean(RagProperties.class, () -> properties());
      context.registerBean(
          SqliteAuthorityStore.class, () -> new SqliteAuthorityStore(directory.resolve("store")));
      context.registerBean(
          ManagementRepository.class,
          () -> new ManagementRepository(context.getBean(SqliteAuthorityStore.class)));
      context.registerBean(
          VideoAvRepository.class,
          () -> new VideoAvRepository(context.getBean(SqliteAuthorityStore.class)));
      context.registerBean(DocumentPermissionPolicy.class, () -> new DocumentPermissionPolicy());
      context.registerBean(
          IngestionSettings.class, () -> new IngestionSettings(true, 30000, 30000));
      context.registerBean(IndexingSettings.class, () -> new IndexingSettings(false, 30000));
      context.registerBean(AnswersSettings.class, () -> new AnswersSettings(false, 30000, 2));
      context.registerBean(DocumentRemovalSettings.class, () -> new DocumentRemovalSettings(false));
      context.registerBean(ProblemHandler.class, () -> new ProblemHandler());
      context.registerBean(JsonMapper.class, () -> JsonMapper.builder().build());
      context.register(VideoAvConfiguration.class, RuntimeConfiguration.class);
      context.refresh();
      assertNotNull(context.getBean(VideoAvLibraryService.class));
      assertNotNull(context.getBean(VideoAvAnswerService.class));
      assertTrue(context.getBeansOfType(VideoDecoder.class).isEmpty());
      assertTrue(context.getBeansOfType(TextModels.class).isEmpty());
      var settings = context.getBean(VideoAvSettings.class);
      assertEquals(
          settings.embedding().revision(),
          context.getBean(VideoAvEmbeddingModels.class).revision());
      assertEquals(settings.models().revision(), context.getBean(VideoAvModels.class).revision());
      assertFalse(
          settings.visualProjection().collection().equals(settings.audioProjection().collection()));
      var caps = context.getBean(RuntimeService.class).capabilities().capabilities();
      assertTrue(
          caps.containsAll(
              List.of(
                  "video_av_upload", "video_av_index", "video_av_answers", "video_av_sources")));
      assertFalse(caps.contains("sound_answers"));
      assertTrue(caps.contains("video_av_query_attachments"));
    }
  }

  @Test
  void localDependencyAndDecoderRevisionAreRequiredBeforeModelConstruction() throws Exception {
    var config = new VideoAvConfiguration();
    for (String[] change :
        new String[][] {
          {"server.address", "localhost"},
          {"server.address", "0.0.0.0"},
          {"rag.environment", "production"},
          {"rag.ingestion.enabled", "false"},
          {"rag.video-av.decoder-revision", "stale"},
          {"rag.video-av.chunk-seconds", "31"}
        }) {
      var env = environment().withProperty(change[0], change[1]);
      var error =
          assertThrows(IllegalArgumentException.class, () -> config.videoAvCompilationService(env));
      assertEquals("Invalid local video audiovisual configuration", error.getMessage());
      assertNull(error.getCause());
    }
  }

  @Test
  void wrongCollectionsProfilesAndBudgetCannotAssembleUsableClients() throws Exception {
    var config = new VideoAvConfiguration();
    var env = environment();
    try (var compiler = config.videoAvCompilationService(env)) {
      var settings = settings(config, env, compiler);
      for (String[] change :
          new String[][] {
            {"rag.video-av.milvus.audio-collection", "java_video_av_video_fixture"},
            {"rag.video-av.milvus.video-collection", "java_text"},
            {"rag.video-av.revision", "latest"},
            {"rag.video-av.decoder-revision", "stale"},
            {"rag.video-av.processing-timeout-ms", "120001"},
            {"rag.video-av.max-concurrent", "3"}
          }) {
        var altered = environment().withProperty(change[0], change[1]);
        assertThrows(IllegalArgumentException.class, () -> settings(config, altered, compiler));
      }
      var stale =
          new IndexTarget(
              "different-profile",
              settings.targets().visual().projectionIdentity(),
              "different-profile",
              3);
      var staleAudio =
          new IndexTarget(
              "different-profile",
              settings.targets().audio().projectionIdentity(),
              "different-profile",
              3);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new VideoAvSettings(
                  settings.models(),
                  settings.embedding(),
                  settings.visualProjection(),
                  settings.audioProjection(),
                  new VideoAvTargets(stale, staleAudio),
                  Duration.ofSeconds(30),
                  2));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new VideoAvSettings(
                  settings.models(),
                  settings.embedding(),
                  settings.visualProjection(),
                  settings.audioProjection(),
                  settings.targets(),
                  Duration.ofMillis(9),
                  2));
      assertEquals("VideoAvSettings[redacted]", settings.toString());
    }
  }

  @Test
  void closedCompilerCannotCreateSettingsAndIpv6LiteralRemainsLocal() throws Exception {
    var config = new VideoAvConfiguration();
    var env = environment().withProperty("server.address", "::1");
    var compiler = config.videoAvCompilationService(env);
    assertNotNull(settings(config, env, compiler));
    compiler.close();
    assertThrows(IllegalArgumentException.class, () -> settings(config, env, compiler));
  }

  @Test
  void oldConstructorsNeverAdvertiseNewCapability() {
    assertFalse(
        new RuntimeService("development_headers", "org", true, false)
            .capabilities()
            .capabilities()
            .contains("video_av_answers"));
    for (boolean ingestion : new boolean[] {false, true}) {
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
      assertEquals(ingestion, runtime.capabilities().capabilities().contains("video_av_answers"));
    }
  }

  private VideoAvSettings settings(
      VideoAvConfiguration config, MockEnvironment env, VideoAvCompilationService compiler) {
    var beans = new DefaultListableBeanFactory();
    return config.videoAvSettings(
        env,
        properties(),
        compiler,
        beans.getBeanProvider(TextAdapterSettings.class),
        beans.getBeanProvider(ImageEmbeddingSettings.class),
        beans.getBeanProvider(AudioEmbeddingSettings.class),
        beans.getBeanProvider(SoundSettings.class));
  }

  private RagProperties properties() {
    return new RagProperties(
        "test", "org", "development_headers", "", "", "", directory.resolve("data"));
  }

  private MockEnvironment environment() throws Exception {
    Path codec = directory.resolve("synthetic-codec");
    Files.writeString(codec, "synthetic fixture is never executed");
    assertTrue(codec.toFile().setExecutable(true));
    String revision;
    try (var decoder = new ProcessVideoAvDecoder(codec, codec, Duration.ofSeconds(30), 30)) {
      revision = decoder.revision();
    }
    return new MockEnvironment()
        .withProperty("rag.video-av.enabled", "true")
        .withProperty("server.address", "127.0.0.1")
        .withProperty("rag.environment", "test")
        .withProperty("rag.ingestion.enabled", "true")
        .withProperty("rag.video-av.ffmpeg-executable", codec.toString())
        .withProperty("rag.video-av.ffprobe-executable", codec.toString())
        .withProperty("rag.video-av.decoder-revision", revision)
        .withProperty("rag.video-av.base-url", "http://127.0.0.1:1")
        .withProperty("rag.video-av.model", "av-fixture")
        .withProperty("rag.video-av.api-key", "fixture-key")
        .withProperty("rag.video-av.revision", "v1")
        .withProperty("rag.video-av.allow-loopback-http", "true")
        .withProperty("rag.video-av.embedding.base-url", "http://127.0.0.1:1")
        .withProperty("rag.video-av.embedding.model", "embedding-fixture")
        .withProperty("rag.video-av.embedding.api-key", "fixture-key")
        .withProperty("rag.video-av.embedding.revision", "v1")
        .withProperty("rag.video-av.embedding.dimensions", "3")
        .withProperty("rag.video-av.embedding.allow-loopback-http", "true")
        .withProperty("rag.video-av.milvus.endpoint", "http://127.0.0.1:1")
        .withProperty("rag.video-av.milvus.video-collection", "java_video_av_video_fixture")
        .withProperty("rag.video-av.milvus.audio-collection", "java_video_av_audio_fixture")
        .withProperty("rag.video-av.milvus.allow-loopback-http", "true");
  }
}
