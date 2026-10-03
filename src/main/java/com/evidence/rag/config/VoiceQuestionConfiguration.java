package com.evidence.rag.config;

import com.evidence.rag.service.AudioCompilationService;
import com.evidence.rag.service.VoiceQuestionService;
import com.evidence.rag.web.BoundedMediaQueryServlet;
import com.evidence.rag.web.ProblemHandler;
import com.evidence.rag.web.converter.VoiceQuestionRequestMapper;
import com.evidence.rag.web.converter.VoiceQuestionResponseMapper;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;

/** Explicit local voice input preparation reusing the existing audio Module. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "rag.voice-questions", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(VoiceQuestionSettings.class)
public class VoiceQuestionConfiguration {
  @Bean
  VoiceQuestionService voiceQuestionService(
      AudioCompilationService audio, VoiceQuestionSettings settings, Environment environment) {
    requireLocal(environment);
    return new VoiceQuestionService(audio, Duration.ofMillis(settings.processingTimeoutMs()));
  }

  @Bean
  ServletRegistrationBean<BoundedMediaQueryServlet> voiceQuestionServlet(
      VoiceQuestionService service,
      VoiceQuestionSettings settings,
      JsonMapper json,
      ProblemHandler errors) {
    var registration =
        new ServletRegistrationBean<>(
            new BoundedMediaQueryServlet(
                (actor, body) ->
                    VoiceQuestionResponseMapper.response(
                        service.transcribe(actor, VoiceQuestionRequestMapper.command(body))),
                VoiceQuestionRequestMapper.MAX_REQUEST_BYTES,
                settings.receiveTimeoutMs(),
                settings.processingTimeoutMs(),
                settings.maxConcurrent(),
                json,
                errors),
            "/v1/voice-questions");
    registration.setName("voiceQuestionServlet");
    registration.setAsyncSupported(true);
    return registration;
  }

  private static void requireLocal(Environment environment) {
    String address = environment.getProperty("server.address");
    String mode = environment.getProperty("rag.environment", "development");
    if (!("127.0.0.1".equals(address) || "::1".equals(address))
        || !("development".equals(mode) || "test".equals(mode))) {
      throw invalidConfiguration();
    }
    for (String dependency : new String[] {"audio", "ingestion", "answers"}) {
      if (!environment.getProperty("rag." + dependency + ".enabled", Boolean.class, false)) {
        throw invalidConfiguration();
      }
    }
  }

  private static IllegalArgumentException invalidConfiguration() {
    return new IllegalArgumentException("Invalid local voice question configuration");
  }
}
