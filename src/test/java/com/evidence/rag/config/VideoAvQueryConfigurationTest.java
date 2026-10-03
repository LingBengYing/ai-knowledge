package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.repository.VideoAvRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.RuntimeService;
import com.evidence.rag.web.BoundedMediaQueryServlet;
import com.evidence.rag.web.ProblemHandler;
import com.evidence.rag.worker.parser.ProcessVideoAvDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

class VideoAvQueryConfigurationTest {
  @TempDir Path directory;

  @Test
  void actualGraphRegistersOnlyTheExactAsyncRouteAndAdvertisesItWithoutOldAttachments()
      throws Exception {
    try (var context = context(false)) {
      var registration = context.getBean("videoAvQueryServlet", ServletRegistrationBean.class);
      assertEquals(
          List.of("/v1/video-av-query-answers"), List.copyOf(registration.getUrlMappings()));
      assertTrue(registration.isAsyncSupported());
      assertTrue(registration.getServlet() instanceof BoundedMediaQueryServlet);
      var capabilities = context.getBean(RuntimeService.class).capabilities().capabilities();
      assertTrue(capabilities.contains("video_av_answers"));
      assertTrue(capabilities.contains("video_av_query_attachments"));
      assertFalse(capabilities.contains("query_attachments"));
      assertFalse(capabilities.contains("sound_query_attachments"));
    }
  }

  @Test
  void existingLibraryGraphWithoutTheActualRouteCannotAdvertiseAttachmentQueries()
      throws Exception {
    try (var context = context(true)) {
      assertFalse(context.containsBean("videoAvQueryServlet"));
      var capabilities = context.getBean(RuntimeService.class).capabilities().capabilities();
      assertTrue(capabilities.contains("video_av_answers"));
      assertFalse(capabilities.contains("video_av_query_attachments"));
    }
  }

  @Test
  void defaultDisabledNeedsNoModelsAndRegistersNoAttachmentServlet() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.setEnvironment(new MockEnvironment());
      context.register(VideoAvConfiguration.class);
      context.refresh();
      assertFalse(context.containsBean("videoAvQueryServlet"));
      assertTrue(context.getBeansOfType(BoundedMediaQueryServlet.class).isEmpty());
    }
  }

  private AnnotationConfigApplicationContext context(boolean removeQueryRoute) throws Exception {
    var context = new AnnotationConfigApplicationContext();
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
    context.registerBean(IngestionSettings.class, () -> new IngestionSettings(true, 30000, 30000));
    context.registerBean(IndexingSettings.class, () -> new IndexingSettings(false, 30000));
    context.registerBean(AnswersSettings.class, () -> new AnswersSettings(false, 30000, 2));
    context.registerBean(DocumentRemovalSettings.class, () -> new DocumentRemovalSettings(false));
    context.registerBean(ProblemHandler.class, () -> new ProblemHandler());
    context.registerBean(JsonMapper.class, () -> JsonMapper.builder().build());
    context.register(VideoAvConfiguration.class, RuntimeConfiguration.class);
    if (removeQueryRoute) {
      context.addBeanFactoryPostProcessor(
          factory ->
              ((BeanDefinitionRegistry) factory).removeBeanDefinition("videoAvQueryServlet"));
    }
    try {
      context.refresh();
      return context;
    } catch (RuntimeException failure) {
      context.close();
      throw failure;
    }
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
