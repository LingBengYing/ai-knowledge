package com.evidence.rag.config;

import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.model.TextModelConnectionProbe;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.job.IndexingJob;
import com.evidence.rag.job.ModelRebuildJob;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelConfigurationState;
import com.evidence.rag.repository.ModelConfigurationRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.repository.TextModelTargetRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.security.authorization.ModelConfigurationPermissionPolicy;
import com.evidence.rag.service.AnswerService;
import com.evidence.rag.service.EvidenceService;
import com.evidence.rag.service.IndexingService;
import com.evidence.rag.service.IndexingTaskProcessor;
import com.evidence.rag.service.KnowledgeAnswerService;
import com.evidence.rag.service.KnowledgeTraceService;
import com.evidence.rag.service.LegacyTextProfileGuard;
import com.evidence.rag.service.ManagedTextRuntime;
import com.evidence.rag.service.ModelConfigurationService;
import com.evidence.rag.service.ModelRebuildService;
import com.evidence.rag.service.ProductHelpService;
import com.evidence.rag.service.ReindexVectorVerifier;
import com.evidence.rag.service.RetrievalSettingsService;
import com.evidence.rag.service.TextRetrievalTestService;
import com.evidence.rag.service.TextRuntimeSnapshot;
import com.evidence.rag.service.VisualAnswerService;
import java.time.Duration;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

/** Managed text setup is optional; constructing a bundle makes no remote calls. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "rag.model-configuration", name = "enabled", havingValue = "true")
public class ModelConfigurationConfiguration {
  private final ObjectProvider<ReindexVectorVerifier> receiptVerifiers;

  /** Compatibility for directly constructed configuration fixtures without media continuation. */
  public ModelConfigurationConfiguration() {
    this.receiptVerifiers = null;
  }

  @Autowired
  public ModelConfigurationConfiguration(ObjectProvider<ReindexVectorVerifier> receiptVerifiers) {
    this.receiptVerifiers = Objects.requireNonNull(receiptVerifiers);
  }

  @Bean
  ManagedTextSettings managedTextSettings(ConfigurableEnvironment environment) {
    return new ManagedTextSettings(environment);
  }

  @Bean(destroyMethod = "close")
  ModelConfigurationRepository modelConfigurationRepository(
      SqliteAuthorityStore store, ConfigurableEnvironment environment) {
    var repository =
        new ModelConfigurationRepository(
            ManagedTextSettings.privateFile(store.libraryPath().getParent()), store);
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
        settings.projection(),
        settings.deepseekProvider());
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
      ManagedMediaTextFactory media) {
    var runtime =
        ManagedTextRuntime.anchored(
            store,
            (version, roles, savedAnchor) -> {
              var adapters = settings.adapters(roles, properties.workspaceId(), savedAnchor);
              var indexed = settings.indexAdapters(roles, savedAnchor, properties.workspaceId());
              var anchor =
                  savedAnchor == null ? settings.anchor(version, roles, indexed) : savedAnchor;
              var models = new OpenAiCompatibleModels(adapters.models());
              MilvusRestProjection projection = null;
              AnswerService answers = null;
              VisualAnswerService visual = null;
              try {
                projection = new MilvusRestProjection(adapters.projection());
                var target = anchor.target();
                var receiptVerifier =
                    receiptVerifiers == null ? null : receiptVerifiers.getObject();
                var processor =
                    receiptVerifier == null
                        ? new IndexingTaskProcessor(
                            indexing,
                            properties.workspaceId(),
                            indexed.models(),
                            indexed.projection(),
                            target,
                            Duration.ofMillis(indexLimits.timeoutMs()))
                        : new IndexingTaskProcessor(
                            indexing,
                            properties.workspaceId(),
                            indexed.models(),
                            indexed.projection(),
                            target,
                            Duration.ofMillis(indexLimits.timeoutMs()),
                            receiptVerifier);
                var mediaBundle = media.build(models, projection, anchor);
                var currentQueries = mediaBundle.queries();
                var currentVideo = mediaBundle.video();
                visual = mediaBundle.visual();
                answers =
                    new AnswerService(
                        evidence,
                        models,
                        projection,
                        target,
                        Duration.ofMillis(answerLimits.timeoutMs()),
                        answerLimits.maxConcurrent(),
                        currentVideo,
                        currentQueries,
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
                    anchor,
                    visual,
                    true);
              } catch (RuntimeException failed) {
                if (answers != null) {
                  answers.close();
                }
                if (visual != null) {
                  visual.close();
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
      ManagedTextRuntime runtime,
      ConfigurableEnvironment environment,
      RagProperties properties,
      ManagedMediaTextFactory media) {
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
    return new LegacyTextProfileGuard(runtime, target, media.visualPresent());
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

  @Bean
  ModelRebuildService modelRebuildService(
      SqliteAuthorityStore store,
      ModelConfigurationRepository repository,
      DocumentPermissionPolicy permissions,
      ManagedTextRuntime runtime,
      IndexingService indexing,
      RagProperties properties,
      ManagedTextSettings settings) {
    return new ModelRebuildService(
        store,
        repository,
        permissions,
        runtime,
        indexing,
        properties.workspaceId(),
        settings.administrators(),
        (version, roles) -> settings.rebuildAnchor(version, roles, properties.workspaceId()));
  }

  @Bean(destroyMethod = "close")
  ModelRebuildJob modelRebuildJob(ModelRebuildService service) {
    return new ModelRebuildJob(service);
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
      EvidenceService evidence,
      ManagedTextRuntime runtime,
      ConfigurableEnvironment environment,
      RetrievalSettingsService retrievalSettings) {
    return new TextRetrievalTestService(
        evidence,
        runtime,
        Duration.ofMillis(
            environment.getProperty(
                "rag.model-configuration.retrieval-timeout-ms", Long.class, 60000L)),
        environment.getProperty(
            "rag.model-configuration.retrieval-max-concurrent", Integer.class, 2),
        retrievalSettings::snapshot);
  }

  @Bean(destroyMethod = "close")
  ProductHelpService productHelpService(
      EvidenceService evidence,
      ManagedTextRuntime runtime,
      ConfigurableEnvironment environment,
      RetrievalSettingsService retrievalSettings) {
    return new ProductHelpService(
        evidence,
        runtime,
        Duration.ofMillis(
            environment.getProperty(
                "rag.model-configuration.retrieval-timeout-ms", Long.class, 60000L)),
        retrievalSettings::snapshot);
  }

  @Bean
  KnowledgeTraceService knowledgeTraceService(
      SqliteAuthorityStore store, EvidenceService evidence) {
    return new KnowledgeTraceService(store, evidence);
  }

  @Bean(destroyMethod = "close")
  KnowledgeAnswerService knowledgeAnswerService(
      EvidenceService evidence,
      ProductHelpService retrieval,
      ManagedTextRuntime runtime,
      KnowledgeTraceService traces,
      AnswersSettings limits) {
    return new KnowledgeAnswerService(
        evidence,
        retrieval,
        runtime,
        traces,
        Duration.ofMillis(limits.timeoutMs()),
        limits.maxConcurrent());
  }
}
