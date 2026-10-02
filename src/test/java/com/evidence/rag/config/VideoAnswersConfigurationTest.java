package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.AudioModels;
import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.VideoAssessment;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.AnswerService;
import com.evidence.rag.service.AudioCompilationService;
import com.evidence.rag.service.VideoAnswerProposalService;
import com.evidence.rag.service.VideoCompilationService;
import com.evidence.rag.service.VisualAnswerService;
import com.evidence.rag.support.AnswerTestContext;
import com.evidence.rag.support.AuthorityTestContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

class VideoAnswersConfigurationTest {
  @TempDir Path directory;

  @Test
  void answersWithVideoComposeWithoutEnablingOldAudioOrImageModules() throws Exception {
    try (var authority = new AuthorityTestContext(directory);
        var context = context(authority, local(true), true)) {
      context.refresh();
      assertEquals(1, context.getBeansOfType(VideoAnswerProposalService.class).size());
      assertEquals(1, context.getBeansOfType(VideoCompilationService.class).size());
      assertEquals(1, context.getBeansOfType(TextModels.class).size());
      assertTrue(context.getBeansOfType(AudioCompilationService.class).isEmpty());
      assertTrue(context.getBeansOfType(VisualAnswerService.class).isEmpty());
      assertTrue(context.getBeansOfType(AudioModels.class).isEmpty());
      assertTrue(context.getBeansOfType(VisionModels.class).isEmpty());
      var result =
          context
              .getBean(AnswerService.class)
              .answerVideo(new Actor("org-main", "owner"), empty(), VideoAssessment.Mode.JOINT);
      assertEquals("abstained", result.status());
      assertEquals("empty_scope", result.reason());
    }
  }

  @Test
  void textOnlyConfigurationStillStartsWithoutAnyVideoConfiguration() throws Exception {
    try (var authority = new AuthorityTestContext(directory);
        var context = context(authority, local(false), true)) {
      context.refresh();
      assertTrue(context.getBeansOfType(VideoAnswerProposalService.class).isEmpty());
      assertTrue(context.getBeansOfType(VideoCompilationService.class).isEmpty());
      var answers = context.getBean(AnswerService.class);
      assertEquals("empty_scope", answers.answer(new Actor("org-main", "owner"), empty()).reason());
      assertEquals(
          "answers_unavailable",
          assertThrows(
                  ApplicationException.class,
                  () ->
                      answers.answerVideo(
                          new Actor("org-main", "owner"), empty(), VideoAssessment.Mode.JOINT))
              .code());
    }
  }

  @Test
  void videoCompositionRequiresExplicitFactTextCapability() throws Exception {
    try (var authority = new AuthorityTestContext(directory);
        var context = context(authority, local(true), false)) {
      var error = assertThrows(RuntimeException.class, context::refresh);
      Throwable cause = error;
      while (cause.getCause() != null) {
        cause = cause.getCause();
      }
      assertEquals("Video answers require fact-scoped text models", cause.getMessage());
      assertFalse(error.toString().contains("synthetic-video-credential"));
    }
  }

  private AnnotationConfigApplicationContext context(
      AuthorityTestContext authority, MockEnvironment environment, boolean factCapability) {
    var context = new AnnotationConfigApplicationContext();
    context.setEnvironment(environment);
    var settings = settings();
    context.getBeanFactory().registerSingleton("authorityStore", authority.store());
    context.registerBean(
        ManagementRepository.class, () -> new ManagementRepository(authority.store()));
    context.registerBean(DocumentPermissionPolicy.class, DocumentPermissionPolicy::new);
    context.registerBean(TextAdapterSettings.class, () -> settings);
    context.registerBean(AnswersSettings.class, () -> new AnswersSettings(true, 5000, 1));
    if (factCapability) {
      context.registerBean(
          OpenAiCompatibleModels.class, () -> new OpenAiCompatibleModels(settings.models()));
    } else {
      context.registerBean(TextModels.class, AnswerTestContext.RecordingModels::new);
    }
    context.registerBean(
        MilvusRestProjection.class, () -> new MilvusRestProjection(settings.projection()));
    context.register(AnswersConfiguration.class, VideoConfiguration.class);
    return context;
  }

  private MockEnvironment local(boolean video) throws Exception {
    var environment = new MockEnvironment().withProperty("rag.answers.enabled", "true");
    if (!video) {
      return environment;
    }
    Path executable = directory.resolve("never-launched-video-codec");
    Files.writeString(executable, "synthetic fixture, never launched");
    assertTrue(executable.toFile().setExecutable(true));
    return environment
        .withProperty("rag.video.enabled", "true")
        .withProperty("rag.environment", "test")
        .withProperty("server.address", "127.0.0.1")
        .withProperty("rag.ingestion.enabled", "true")
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

  private static AnswerCommand empty() {
    return new AnswerCommand("指示灯的颜色是什么？", DocumentSelection.selected(List.of()));
  }

  private static TextAdapterSettings settings() {
    var values = new HashMap<String, String>();
    for (String kind : List.of("EMBEDDING", "RERANK", "GENERATION")) {
      values.put("RAG_" + kind + "_BASE_URL", "http://127.0.0.1:1/v1");
      values.put("RAG_" + kind + "_MODEL", "synthetic-model");
      values.put("RAG_" + kind + "_API_KEY", "synthetic-video-credential");
    }
    values.put("RAG_EMBEDDING_DIMENSIONS", "2");
    values.put("RAG_EMBEDDING_REVISION", "synthetic-embedding-v1");
    values.put("RAG_MILVUS_ENDPOINT", "http://127.0.0.1:1");
    values.put("RAG_MILVUS_TOKEN", "synthetic-milvus-credential");
    values.put("RAG_MILVUS_COLLECTION", "java_video_config_test");
    values.put("RAG_WORKSPACE_ID", "org-main");
    values.put("RAG_TEXT_ALLOW_LOOPBACK_HTTP", "true");
    return TextAdapterSettings.load(values);
  }
}
