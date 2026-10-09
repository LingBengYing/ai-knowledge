package com.evidence.rag.config;

import com.evidence.rag.client.vector.MilvusProjectionCleanup;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.client.vector.ProjectionCleanup;
import com.evidence.rag.job.DocumentCleanupJob;
import com.evidence.rag.repository.DocumentCleanupRepository;
import com.evidence.rag.repository.DocumentLifecycleRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.ModelRebuildRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.DocumentCleanupService;
import com.evidence.rag.service.ManagedTextRuntime;
import java.util.ArrayList;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Opt-in local control and actual persistent execution; no model configuration is required. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DocumentCleanupSettings.class)
public class DocumentCleanupConfiguration {
  @Bean
  @ConditionalOnProperty(prefix = "rag.document-cleanup", name = "enabled", havingValue = "true")
  DocumentCleanupRepository documentCleanupRepository(
      SqliteAuthorityStore store,
      RagProperties properties,
      DocumentRemovalSettings removal,
      Environment environment) {
    if (!removal.enabled()
        || !("development".equals(properties.environment())
            || "test".equals(properties.environment()))
        || !("127.0.0.1".equals(environment.getProperty("server.address"))
            || "::1".equals(environment.getProperty("server.address")))) {
      throw new IllegalArgumentException(
          "Document cleanup requires enabled local development or test removal");
    }
    return new DocumentCleanupRepository(store);
  }

  @Bean
  @ConditionalOnProperty(prefix = "rag.document-cleanup", name = "enabled", havingValue = "true")
  ProjectionCleanup projectionCleanup(
      ObjectProvider<TextAdapterSettings> text,
      ObjectProvider<ImageEmbeddingSettings> images,
      ObjectProvider<AudioEmbeddingSettings> audio,
      ObjectProvider<SoundSettings> sound,
      ObjectProvider<VideoAvSettings> video,
      ObjectProvider<ManagedTextSettings> managed,
      ObjectProvider<ManagedTextRuntime> managedRuntime,
      SqliteAuthorityStore store,
      RagProperties properties) {
    var targets = new ArrayList<MilvusRestProjection.Settings>();
    text.ifAvailable(settings -> targets.add(settings.projection()));
    images.ifAvailable(settings -> targets.add(settings.projection()));
    audio.ifAvailable(settings -> targets.add(settings.projection()));
    sound.ifAvailable(settings -> targets.add(settings.projection()));
    video.ifAvailable(
        settings -> {
          targets.add(settings.visualProjection());
          targets.add(settings.audioProjection());
        });
    var runtime = managedRuntime.getIfAvailable();
    return new MilvusProjectionCleanup(
        targets,
        target -> {
          var settings = managed.getIfAvailable();
          if (settings == null || !properties.workspaceId().equals(target.workspaceId())) {
            return Optional.empty();
          }
          // Initial managed activation need not create a rebuild row. Match its installed
          // projection identity, never infer an old target from today's model role settings.
          var active = runtime == null ? null : runtime.currentTarget();
          boolean current =
              active != null
                  && active.projectionIdentity().equals(target.projectionIdentity())
                  && active.embeddingIdentity().equals(target.embeddingIdentity())
                  && active.dimensions() == target.dimensions();
          boolean rebuilt =
              target.collection().matches("java_text_v[0-9]+_[a-f0-9]+")
                  && store.transaction(
                      () -> new ModelRebuildRepository(store).registeredTarget(target));
          if (!current && !rebuilt) {
            return Optional.empty();
          }
          var connection = settings.projection();
          if (connection == null
              || !connection.endpoint().resolve("/").toASCIIString().equals(target.endpoint())
              || !connection.database().equals(target.database())) {
            return Optional.empty();
          }
          var candidate =
              new MilvusRestProjection.Settings(
                  connection.endpoint(),
                  connection.token(),
                  connection.database(),
                  target.collection(),
                  target.workspaceId(),
                  target.embeddingIdentity(),
                  target.dimensions(),
                  settings.deadline(),
                  settings.maxBytes(),
                  settings.loopback());
          return MilvusProjectionCleanup.qualified(candidate).equals(target)
              ? Optional.of(candidate)
              : Optional.empty();
        });
  }

  @Bean
  @ConditionalOnProperty(prefix = "rag.document-cleanup", name = "enabled", havingValue = "true")
  DocumentCleanupService documentCleanupService(
      SqliteAuthorityStore store,
      DocumentCleanupRepository cleanup,
      DocumentLifecycleRepository lifecycle,
      ManagementRepository management,
      DocumentPermissionPolicy permissions,
      ProjectionCleanup projection) {
    return new DocumentCleanupService(
        store, cleanup, lifecycle, management, permissions, projection);
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(prefix = "rag.document-cleanup", name = "enabled", havingValue = "true")
  DocumentCleanupJob documentCleanupJob(DocumentCleanupService service) {
    return new DocumentCleanupJob(service);
  }
}
