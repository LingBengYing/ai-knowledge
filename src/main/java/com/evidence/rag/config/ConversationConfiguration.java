package com.evidence.rag.config;

import com.evidence.rag.repository.ConversationRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.service.ConversationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Dialogue browsing is local and remains available when the agent/model service is disabled. */
@Configuration(proxyBeanMethods = false)
public class ConversationConfiguration {
  @Bean
  ConversationRepository conversationRepository(SqliteAuthorityStore store) {
    return new ConversationRepository(store);
  }

  @Bean
  ConversationService conversationService(
      SqliteAuthorityStore store,
      ConversationRepository repository,
      RagProperties properties,
      Environment environment) {
    return new ConversationService(
        store,
        repository,
        properties.workspaceId(),
        environment.getProperty(
            "rag.knowledge-agent.context-character-threshold", Integer.class, 24000),
        environment.getProperty("rag.knowledge-agent.context-recent-turns", Integer.class, 4));
  }
}
