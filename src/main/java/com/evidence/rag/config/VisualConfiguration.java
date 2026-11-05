package com.evidence.rag.config;

import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.model.OpenAiCompatibleVisionModels;
import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.model.domain.VisualIngestionOptions;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Explicit local opt-in. Creates clients but never submits model or index requests at startup. */
@Configuration(proxyBeanMethods = false)
@Conditional(MediaModulesCondition.class)
@ConditionalOnProperty(prefix = "rag.visual", name = "enabled", havingValue = "true")
public class VisualConfiguration {
  @Bean(destroyMethod = "close")
  OpenAiCompatibleVisionModels visionModels(Environment environment) {
    try {
      if (!RuntimeGuard.isLocalDevelopment(environment, "answers")) {
        throw new IllegalArgumentException();
      }
      var endpoint =
          new OpenAiCompatibleModels.Endpoint(
              URI.create(environment.getRequiredProperty("rag.visual.base-url")),
              environment.getRequiredProperty("rag.visual.model"),
              environment.getRequiredProperty("rag.visual.api-key"));
      return new OpenAiCompatibleVisionModels(
          new OpenAiCompatibleVisionModels.Configuration(
              endpoint,
              Duration.ofMillis(
                  environment.getProperty("rag.visual.deadline-ms", Long.class, 30000L)),
              environment.getProperty("rag.visual.max-response-bytes", Integer.class, 262144),
              environment.getProperty("rag.visual.allow-loopback-http", Boolean.class, false)));
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Invalid local visual model configuration");
    }
  }

  @Bean
  VisualIngestionOptions visualIngestionOptions(VisionModels models) {
    return new VisualIngestionOptions(models.revision());
  }
}
