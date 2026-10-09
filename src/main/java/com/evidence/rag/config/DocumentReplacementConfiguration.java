package com.evidence.rag.config;

import com.evidence.rag.repository.DocumentUpdateRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.DocumentReplacementService;
import com.evidence.rag.service.IndexingTaskProcessor;
import com.evidence.rag.service.IngestionService;
import com.evidence.rag.service.ManagedTextRuntime;
import com.evidence.rag.service.SoundLibraryService;
import com.evidence.rag.service.VideoAvLibraryService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class DocumentReplacementConfiguration {
  @Bean("documentReplacementUploadTimeoutMs")
  Integer documentReplacementUploadTimeoutMs(IngestionSettings settings) {
    return settings.uploadTimeoutMs();
  }

  @Bean
  DocumentUpdateRepository documentUpdateRepository(SqliteAuthorityStore store) {
    return new DocumentUpdateRepository(store);
  }

  @Bean
  DocumentReplacementService documentReplacementService(
      SqliteAuthorityStore store,
      DocumentUpdateRepository updates,
      ManagementRepository management,
      DocumentPermissionPolicy permissions,
      IngestionService ingestion,
      IngestionSettings settings,
      ObjectProvider<ManagedTextRuntime> managed,
      ObjectProvider<IndexingTaskProcessor> indexing,
      ObjectProvider<SoundLibraryService> sound,
      ObjectProvider<VideoAvLibraryService> videoAv) {
    return new DocumentReplacementService(
        store,
        updates,
        management,
        permissions,
        ingestion,
        () -> {
          var runtime = managed.getIfAvailable();
          return runtime == null ? indexing.getIfAvailable() : runtime.capture().indexing();
        },
        sound.getIfAvailable(),
        videoAv.getIfAvailable(),
        settings.enabled());
  }
}
