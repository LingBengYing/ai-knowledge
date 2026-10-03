package com.evidence.rag.config;

import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.model.TextModelConnectionProbe;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.job.IndexingJob;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelConfigurationState;
import com.evidence.rag.repository.ModelConfigurationRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.repository.TextModelTargetRepository;
import com.evidence.rag.security.authorization.ModelConfigurationPermissionPolicy;
import com.evidence.rag.service.AnswerService;
import com.evidence.rag.service.EvidenceService;
import com.evidence.rag.service.IndexingService;
import com.evidence.rag.service.IndexingTaskProcessor;
import com.evidence.rag.service.LegacyTextProfileGuard;
import com.evidence.rag.service.ManagedTextRuntime;
import com.evidence.rag.service.ModelConfigurationService;
import com.evidence.rag.service.QueryAttachmentService;
import com.evidence.rag.service.TextRetrievalTestService;
import com.evidence.rag.service.TextRuntimeSnapshot;
import com.evidence.rag.service.VideoAnswerProposalService;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

/** Managed text setup is optional; constructing a bundle makes no remote calls. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "rag.model-configuration", name = "enabled", havingValue = "true")
public class ModelConfigurationConfiguration {
  @Bean
  ManagedTextSettings managedTextSettings(ConfigurableEnvironment environment) {
    return new ManagedTextSettings(environment);
  }

  @Bean(destroyMethod = "close")
  ModelConfigurationRepository modelConfigurationRepository(
      SqliteAuthorityStore store, ConfigurableEnvironment environment) {
    var repository =
        new ModelConfigurationRepository(
            ManagedTextSettings.privateFile(store.libraryPath().getParent()));
    try {
      var state = repository.read();
      if (state.version() == 0) {
        var initial = ManagedTextSettings.bootstrap(environment);
        if (initial != null) {
          repository.bootstrapIfAbsent(initial);
        }
      }
    } catch (ApplicationException unavailable) {
      if (!"model_configuration_unavailable".equals(unavailable.code())) {
        repository.close();
        throw unavailable;
      }
      // Keep management accessible. Bad private settings are never overwritten or bootstrapped.
    }
    return repository;
  }

  @Bean
  ModelConfigurationPermissionPolicy modelConfigurationPermissionPolicy(
      RagProperties properties, ManagedTextSettings settings) {
    return new ModelConfigurationPermissionPolicy(
        properties.workspaceId(), settings.administrators());
  }

  @Bean
  TextModelConnectionProbe textModelConnectionProbe(ManagedTextSettings settings) {
    return new TextModelConnectionProbe(
        settings.provider(),
        settings.deadline(),
        settings.maxBytes(),
        settings.loopback(),
        settings.projection());
  }

  @Bean(destroyMethod = "close")
  ManagedTextRuntime managedTextRuntime(
      SqliteAuthorityStore store,
      IndexingService indexing,
      EvidenceService evidence,
      RagProperties properties,
      ManagedTextSettings settings,
      AnswersSettings answerLimits,
      IndexingSettings indexLimits,
      ModelConfigurationRepository repository,
      ObjectProvider<VideoAnswerProposalService> videos,
      ObjectProvider<QueryAttachmentService> attachments) {
    var runtime =
        ManagedTextRuntime.anchored(
            store,
            (version, roles, savedAnchor) -> {
              var adapters = settings.adapters(roles, properties.workspaceId());
              var indexed = settings.indexAdapters(roles, savedAnchor, properties.workspaceId());
              var anchor =
                  savedAnchor == null ? settings.anchor(version, roles, indexed) : savedAnchor;
              var models = new OpenAiCompatibleModels(adapters.models());
              MilvusRestProjection projection = null;
              AnswerService answers = null;
              try {
                projection = new MilvusRestProjection(adapters.projection());
                var target = anchor.target();
                var processor =
                    new IndexingTaskProcessor(
                        indexing,
                        properties.workspaceId(),
                        indexed.models(),
                        indexed.projection(),
                        target,
                        Duration.ofMillis(indexLimits.timeoutMs()));
                // Media proposal modules keep their independently configured legacy graph.
                boolean sameLegacy = false;
                try {
                  var legacy =
                      ManagedTextSettings.legacy(settings.environment(), properties.workspaceId());
                  try (var identity = new OpenAiCompatibleModels(legacy.models())) {
                    sameLegacy =
                        models.revision().equals(identity.revision())
                            && target.equals(
                                new IndexTarget(
                                    legacy.projection().embeddingIdentity(),
                                    legacy.projection().identity(),
                                    identity.revision(),
                                    legacy.projection().dimension()));
                  }
                } catch (RuntimeException absent) {
                  /* No legacy graph is an ordinary first-setup state. */
                }
                answers =
                    new AnswerService(
                        evidence,
                        models,
                        projection,
                        target,
                        Duration.ofMillis(answerLimits.timeoutMs()),
                        answerLimits.maxConcurrent(),
                        sameLegacy ? videos.getIfAvailable() : null,
                        sameLegacy ? attachments.getIfAvailable() : null,
                        anchor);
                var ownedProjection = projection;
                return new TextRuntimeSnapshot(
                    version,
                    models,
                    projection,
                    target,
                    answers,
                    processor,
                    () -> {
                      try {
                        models.close();
                      } finally {
                        ownedProjection.close();
                      }
                    },
                    anchor);
              } catch (RuntimeException failed) {
                if (answers != null) {
                  answers.close();
                }
                if (projection != null) {
                  projection.close();
                }
                models.close();
                throw failed;
              }
            });
    ModelConfigurationState state;
    try {
      state = repository.read();
    } catch (ApplicationException unavailable) {
      if (!"model_configuration_unavailable".equals(unavailable.code())) {
        runtime.close();
        throw unavailable;
      }
      return runtime;
    }
    if (state.active() != null && settings.projection() != null) {
      try (var lease = store.operationGate().tryMaintenance().orElseThrow()) {
        var initial = runtime.prepare(state.activeVersion(), state.active(), state.indexAnchor());
        try {
          store.transaction(
              () -> {
                new TextModelTargetRepository(store)
                    .requireCompatible(properties.workspaceId(), initial.target());
                return null;
              });
          runtime.install(initial, lease, () -> {});
        } catch (RuntimeException failed) {
          initial.close();
          runtime.close();
          throw failed;
        }
      }
    }
    return runtime;
  }

  @Bean
  LegacyTextProfileGuard legacyTextProfileGuard(
      ManagedTextRuntime runtime, ConfigurableEnvironment environment, RagProperties properties) {
    IndexTarget target = null;
    try {
      var legacy = ManagedTextSettings.legacy(environment, properties.workspaceId());
      try (var models = new OpenAiCompatibleModels(legacy.models())) {
        target =
            new IndexTarget(
                legacy.projection().embeddingIdentity(),
                legacy.projection().identity(),
                models.revision(),
                legacy.projection().dimension());
      }
    } catch (RuntimeException absent) {
      /* Missing legacy media configuration does not block basic text setup. */
    }
    return new LegacyTextProfileGuard(runtime, target);
  }

  @Bean
  ModelConfigurationService modelConfigurationService(
      SqliteAuthorityStore store,
      ModelConfigurationRepository repository,
      ModelConfigurationPermissionPolicy policy,
      ManagedTextRuntime runtime,
      TextModelConnectionProbe probe) {
    return new ModelConfigurationService(store, repository, policy, runtime, probe);
  }

  @Bean(name = "indexingJob", destroyMethod = "close")
  @ConditionalOnProperty(prefix = "rag.indexing", name = "enabled", havingValue = "true")
  IndexingJob managedIndexingJob(ManagedTextRuntime runtime, SqliteAuthorityStore store) {
    return IndexingJob.managed(
        () -> runtime.currentVersion() == null ? null : runtime.capture().indexing(),
        store.operationGate());
  }

  @Bean(destroyMethod = "close")
  TextRetrievalTestService textRetrievalTestService(
      EvidenceService evidence, ManagedTextRuntime runtime, ConfigurableEnvironment environment) {
    return new TextRetrievalTestService(
        evidence,
        runtime,
        Duration.ofMillis(
            environment.getProperty(
                "rag.model-configuration.retrieval-timeout-ms", Long.class, 60000L)),
        environment.getProperty(
            "rag.model-configuration.retrieval-max-concurrent", Integer.class, 2));
  }
}
