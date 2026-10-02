package com.evidence.rag.config;

import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.job.IngestionJob;
import com.evidence.rag.service.AudioCompilationService;
import com.evidence.rag.service.IngestionService;
import com.evidence.rag.service.IngestionTaskProcessor;
import com.evidence.rag.service.VideoCompilationService;
import com.evidence.rag.web.ProblemHandler;
import com.evidence.rag.web.UploadServlet;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "rag.ingestion", name = "enabled", havingValue = "true")
public class IngestionConfiguration {
  @Bean
  IngestionTaskProcessor ingestionTaskProcessor(
      IngestionService authority,
      RagProperties properties,
      IngestionSettings settings,
      Environment environment,
      ObjectProvider<VisionModels> vision,
      ObjectProvider<AudioCompilationService> audio,
      ObjectProvider<VideoCompilationService> video) {
    String bind = environment.getProperty("server.address");
    if (!("127.0.0.1".equals(bind) || "::1".equals(bind))) {
      throw new IllegalArgumentException("Development ingestion requires literal loopback binding");
    }
    return new IngestionTaskProcessor(
        authority,
        properties.workspaceId(),
        Duration.ofMillis(settings.parseTimeoutMs()),
        ImageOcrConfiguration.options(environment),
        vision.getIfAvailable(),
        audio.getIfAvailable(),
        video.getIfAvailable());
  }

  @Bean(destroyMethod = "close")
  IngestionJob ingestionJob(IngestionTaskProcessor processor) {
    return new IngestionJob(processor);
  }

  @Bean
  ServletRegistrationBean<UploadServlet> uploadServlet(
      IngestionService authority,
      IngestionSettings settings,
      JsonMapper json,
      ProblemHandler errors,
      IngestionJob runtime) {
    var registration =
        new ServletRegistrationBean<>(
            new UploadServlet(authority, settings.uploadTimeoutMs(), json, errors),
            "/v1/documents");
    registration.setAsyncSupported(true);
    return registration;
  }
}
