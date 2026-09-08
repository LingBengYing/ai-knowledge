package com.evidence.rag.config;

import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.repository.EvidenceRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.AnswerService;
import com.evidence.rag.service.EvidenceService;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit local answer composition; authorization precedes every query preparation. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "rag.answers", name = "enabled", havingValue = "true")
public class AnswersConfiguration {
  @Bean
  EvidenceRepository evidenceRepository(SqliteAuthorityStore store) {
    return new EvidenceRepository(store);
  }

  @Bean
  EvidenceService evidenceService(
      SqliteAuthorityStore store,
      EvidenceRepository evidence,
      ManagementRepository management,
      DocumentPermissionPolicy permissions) {
    return new EvidenceService(store, evidence, management, permissions);
  }

  @Bean(destroyMethod = "close")
  AnswerService answerService(
      EvidenceService evidence,
      TextModels models,
      RetrievalProjection projection,
      TextAdapterSettings settings,
      AnswersSettings limits) {
    var target =
        new IndexTarget(
            settings.projection().embeddingIdentity(),
            settings.projection().identity(),
            models.revision(),
            settings.projection().dimension());
    return new AnswerService(
        evidence,
        models,
        projection,
        target,
        Duration.ofMillis(limits.timeoutMs()),
        limits.maxConcurrent());
  }
}
