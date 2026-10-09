package com.evidence.rag.config;

import com.evidence.rag.job.ImportAutoIndexJob;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.service.ImportAutoIndexService;
import com.evidence.rag.service.IndexingTaskProcessor;
import com.evidence.rag.service.ManagedTextRuntime;
import com.evidence.rag.service.SoundLibraryService;
import com.evidence.rag.service.VideoAvLibraryService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "rag.import-auto-index",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ImportAutoIndexConfiguration {
  @Bean
  ImportAutoIndexService importAutoIndexService(
      SqliteAuthorityStore store,
      ObjectProvider<ManagedTextRuntime> managed,
      ObjectProvider<IndexingTaskProcessor> legacy,
      ObjectProvider<SoundLibraryService> sounds,
      ObjectProvider<VideoAvLibraryService> videos,
      Environment environment) {
    return new ImportAutoIndexService(
        store,
        () -> {
          if (!environment.getProperty("rag.indexing.enabled", Boolean.class, false)) {
            return null;
          }
          var runtime = managed.getIfAvailable();
          return runtime == null
              ? legacy.getIfAvailable()
              : runtime.currentVersion() == null ? null : runtime.capture().indexing();
        },
        sounds.getIfAvailable(),
        videos.getIfAvailable());
  }

  @Bean(destroyMethod = "close")
  ImportAutoIndexJob importAutoIndexJob(ImportAutoIndexService service) {
    return new ImportAutoIndexJob(service);
  }
}
