package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.AudioModels;
import com.evidence.rag.model.domain.DecodedAudio;
import com.evidence.rag.service.AudioCompilationService;
import com.evidence.rag.service.VoiceQuestionService;
import com.evidence.rag.web.BoundedMediaQueryServlet;
import com.evidence.rag.web.ProblemHandler;
import com.evidence.rag.worker.parser.AudioDecoder;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

class VoiceQuestionConfigurationTest {
  @Test
  void disabledFeatureRequiresNoAudioProviderOrTransportBeans() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.setEnvironment(new MockEnvironment().withProperty("server.address", "0.0.0.0"));
      context.register(VoiceQuestionConfiguration.class);
      context.refresh();
      assertTrue(context.getBeansOfType(VoiceQuestionService.class).isEmpty());
      assertTrue(context.getBeansOfType(ServletRegistrationBean.class).isEmpty());
      assertTrue(context.getBeansOfType(AudioModels.class).isEmpty());
    }
  }

  @Test
  void enabledSpringCompositionReusesAudioWithoutVisualVideoRankingOrStartupCalls() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.setEnvironment(local());
      context.registerBean(AudioCompilationService.class, VoiceQuestionConfigurationTest::audio);
      context.registerBean(JsonMapper.class, () -> JsonMapper.builder().build());
      context.registerBean(ProblemHandler.class, ProblemHandler::new);
      context.register(VoiceQuestionConfiguration.class);
      context.refresh();
      assertNotNull(context.getBean(VoiceQuestionService.class));
      var settings = context.getBean(VoiceQuestionSettings.class);
      assertEquals(new VoiceQuestionSettings(true, 30000, 120000, 2), settings);
      var registration = context.getBean("voiceQuestionServlet", ServletRegistrationBean.class);
      assertEquals(List.of("/v1/voice-questions"), List.copyOf(registration.getUrlMappings()));
      assertEquals("voiceQuestionServlet", registration.getServletName());
      assertTrue(registration.getServlet() instanceof BoundedMediaQueryServlet);
      assertTrue(registration.isAsyncSupported());
      assertFalse(context.containsBean("queryPreparationService"));
      registration.getServlet().destroy();
    }
  }

  @Test
  void onlyExistingAudioIngestionAnswersAndLiteralLocalDevelopmentBindingsAreRequired() {
    var configuration = new VoiceQuestionConfiguration();
    var settings = new VoiceQuestionSettings(true, 30000, 120000, 2);
    assertNotNull(
        configuration.voiceQuestionService(
            audio(),
            settings,
            local()
                .withProperty("server.address", "::1")
                .withProperty("rag.environment", "development")));
    for (String dependency : List.of("audio", "ingestion", "answers")) {
      assertInvalid(local().withProperty("rag." + dependency + ".enabled", "false"));
    }
    assertInvalid(local().withProperty("server.address", "0.0.0.0"));
    assertInvalid(local().withProperty("server.address", "localhost"));
    assertInvalid(local().withProperty("rag.environment", "production"));
  }

  @Test
  void voiceProcessingIsTighterThanTheOriginalAttachmentBudgetEvenWhenDisabled() {
    assertEquals(10, new VoiceQuestionSettings(true, 10, 10, 1).processingTimeoutMs());
    assertEquals(120000, new VoiceQuestionSettings(false, 60000, 120000, 8).processingTimeoutMs());
    for (int[] values :
        List.of(
            new int[] {9, 1000, 1},
            new int[] {60001, 1000, 1},
            new int[] {1000, 9, 1},
            new int[] {1000, 120001, 1},
            new int[] {1000, 1000, 0},
            new int[] {1000, 1000, 9})) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new VoiceQuestionSettings(false, values[0], values[1], values[2]));
    }
  }

  private static void assertInvalid(MockEnvironment environment) {
    var failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new VoiceQuestionConfiguration()
                    .voiceQuestionService(
                        audio(), new VoiceQuestionSettings(true, 30000, 120000, 2), environment));
    assertEquals("Invalid local voice question configuration", failure.getMessage());
    assertNull(failure.getCause());
  }

  private static MockEnvironment local() {
    return new MockEnvironment()
        .withProperty("rag.voice-questions.enabled", "true")
        .withProperty("rag.voice-questions.receive-timeout-ms", "30000")
        .withProperty("rag.voice-questions.processing-timeout-ms", "120000")
        .withProperty("rag.voice-questions.max-concurrent", "2")
        .withProperty("rag.audio.enabled", "true")
        .withProperty("rag.ingestion.enabled", "true")
        .withProperty("rag.answers.enabled", "true")
        .withProperty("server.address", "127.0.0.1")
        .withProperty("rag.environment", "test");
  }

  private static AudioCompilationService audio() {
    AudioDecoder decoder =
        new AudioDecoder() {
          @Override
          public String revision() {
            return "configuration-decoder-v1";
          }

          @Override
          public DecodedAudio decode(String filename, String mime, byte[] source) {
            throw new AssertionError("Configuration must not decode audio");
          }

          @Override
          public void close() {}
        };
    AudioModels models =
        new AudioModels() {
          @Override
          public String revision() {
            return "configuration-model-v1";
          }

          @Override
          public Transcript transcribe(byte[] wav) {
            throw new AssertionError("Configuration must not invoke ASR");
          }

          @Override
          public void close() {}
        };
    return new AudioCompilationService(decoder, models, 15, Duration.ofMinutes(10));
  }
}
