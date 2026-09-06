package com.evidence.rag.ingestion;

import com.evidence.rag.config.RagProperties;
import com.evidence.rag.management.ManagementModule;
import com.evidence.rag.web.ProblemHandler;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "rag.ingestion", name = "enabled", havingValue = "true")
public class IngestionConfiguration {
  @Bean(destroyMethod = "close")
  IngestionRuntime ingestionRuntime(
      ManagementModule authority,
      RagProperties properties,
      IngestionSettings settings,
      Environment environment) {
    String bind = environment.getProperty("server.address");
    if (!("127.0.0.1".equals(bind) || "::1".equals(bind)))
      throw new IllegalArgumentException("Development ingestion requires literal loopback binding");
    return new IngestionRuntime(
        authority, properties.workspaceId(), Duration.ofMillis(settings.parseTimeoutMs()));
  }

  @Bean
  ServletRegistrationBean<UploadServlet> uploadServlet(
      ManagementModule authority,
      IngestionSettings settings,
      JsonMapper json,
      ProblemHandler errors,
      IngestionRuntime runtime) {
    var registration =
        new ServletRegistrationBean<>(
            new UploadServlet(authority, settings.uploadTimeoutMs(), json, errors),
            "/v1/documents");
    registration.setAsyncSupported(true);
    return registration;
  }
}
