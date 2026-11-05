package com.evidence.rag.config;

import com.evidence.rag.repository.EvidenceRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.EvidenceService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit local answer composition; authorization precedes every query preparation. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("${rag.answers.enabled:false} || ${rag.model-configuration.enabled:false}")
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
}
