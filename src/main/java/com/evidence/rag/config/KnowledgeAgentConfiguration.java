package com.evidence.rag.config;

import com.evidence.rag.client.model.AgentHttpClient;
import com.evidence.rag.service.EvidenceService;
import com.evidence.rag.service.KnowledgeAgentService;
import com.evidence.rag.service.KnowledgeTraceService;
import com.evidence.rag.service.ManagedTextRuntime;
import com.evidence.rag.service.ProductHelpService;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Opt-in local DB-GPT sidecar; no startup requests and no model/authority duplication. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "rag.knowledge-agent", name = "enabled", havingValue = "true")
public class KnowledgeAgentConfiguration {
  @Bean(destroyMethod = "close")
  AgentHttpClient knowledgeAgentClient(Environment environment) {
    return new AgentHttpClient(
        URI.create(environment.getRequiredProperty("rag.knowledge-agent.base-url")),
        environment.getRequiredProperty("rag.knowledge-agent.service-token"),
        timeout(environment));
  }

  @Bean(destroyMethod = "close")
  KnowledgeAgentService knowledgeAgentService(
      EvidenceService evidence,
      ProductHelpService retrieval,
      ManagedTextRuntime runtime,
      KnowledgeTraceService traces,
      AgentHttpClient client,
      Environment environment) {
    return new KnowledgeAgentService(
        evidence,
        retrieval,
        runtime,
        traces,
        client,
        timeout(environment),
        environment.getProperty("rag.knowledge-agent.max-concurrent", Integer.class, 2));
  }

  private static Duration timeout(Environment environment) {
    return Duration.ofMillis(
        environment.getProperty("rag.knowledge-agent.timeout-ms", Long.class, 180000L));
  }
}
