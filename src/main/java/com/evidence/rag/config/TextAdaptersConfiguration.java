package com.evidence.rag.config;

import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.ConfigurableEnvironment;

/** Loads explicit settings once; client construction never creates or loads remote resources. */
@Configuration(proxyBeanMethods = false)
@Conditional(LegacyTextCondition.class)
@ConditionalOnExpression("${rag.indexing.enabled:false} || ${rag.answers.enabled:false}")
public class TextAdaptersConfiguration {
  @Bean
  TextAdapterSettings textAdapterSettings(
      ConfigurableEnvironment environment, RagProperties properties) {
    return IndexingConfiguration.loadAdapters(environment, properties.workspaceId());
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(prefix = "rag.answers", name = "enabled", havingValue = "true")
  OpenAiCompatibleModels answerModels(TextAdapterSettings settings) {
    return new OpenAiCompatibleModels(settings.models());
  }

  @Bean(destroyMethod = "close")
  @Primary
  @ConditionalOnProperty(prefix = "rag.answers", name = "enabled", havingValue = "true")
  MilvusRestProjection answerProjection(TextAdapterSettings settings) {
    return new MilvusRestProjection(settings.projection());
  }
}
