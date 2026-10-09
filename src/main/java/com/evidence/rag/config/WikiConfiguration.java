package com.evidence.rag.config;

import com.evidence.rag.repository.EvidenceRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.repository.SynopsisMaterialRepository;
import com.evidence.rag.repository.WikiCatalogRepository;
import com.evidence.rag.repository.WikiDraftRepository;
import com.evidence.rag.repository.WikiWorkspaceRepository;
import com.evidence.rag.service.ManagedTextRuntime;
import com.evidence.rag.service.WikiCatalogService;
import com.evidence.rag.service.WikiCompilationService;
import com.evidence.rag.service.WikiDraftService;
import com.evidence.rag.service.WikiWorkspaceService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Reading/reviewing Wiki does not require paid models; generation opts in per explicit request. */
@Configuration(proxyBeanMethods = false)
public class WikiConfiguration {
  @Bean
  WikiCatalogRepository wikiCatalogRepository(SqliteAuthorityStore store) {
    return new WikiCatalogRepository(store);
  }

  @Bean
  WikiCatalogService wikiCatalogService(
      SqliteAuthorityStore store, WikiCatalogRepository repository, RagProperties properties) {
    return new WikiCatalogService(
        store, repository, new EvidenceRepository(store), properties.workspaceId());
  }

  @Bean
  WikiDraftRepository wikiDraftRepository(SqliteAuthorityStore store) {
    return new WikiDraftRepository(store);
  }

  @Bean
  WikiDraftService wikiDraftService(
      SqliteAuthorityStore store, WikiDraftRepository repository, RagProperties properties) {
    return new WikiDraftService(store, repository, properties.workspaceId());
  }

  @Bean
  WikiWorkspaceRepository wikiWorkspaceRepository(SqliteAuthorityStore store) {
    return new WikiWorkspaceRepository(store);
  }

  @Bean
  WikiCompilationService wikiCompilationService() {
    return new WikiCompilationService();
  }

  @Bean
  WikiWorkspaceService wikiWorkspaceService(
      SqliteAuthorityStore store,
      WikiWorkspaceRepository repository,
      WikiCompilationService compiler,
      ObjectProvider<ManagedTextRuntime> runtime,
      RagProperties properties) {
    return new WikiWorkspaceService(
        store,
        repository,
        new SynopsisMaterialRepository(store),
        compiler,
        runtime.getIfAvailable(),
        properties.workspaceId());
  }
}
