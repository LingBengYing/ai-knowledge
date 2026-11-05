package com.evidence.rag.config;

import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.job.DocumentCleanupJob;
import com.evidence.rag.model.domain.LibraryOperationGate;
import com.evidence.rag.model.dto.RuntimeCapabilitiesResult;
import com.evidence.rag.service.AudioVectorIndexingService;
import com.evidence.rag.service.DocumentCleanupService;
import com.evidence.rag.service.ImageVectorIndexingService;
import com.evidence.rag.service.LegacyTextProfileGuard;
import com.evidence.rag.service.ManagedTextRuntime;
import com.evidence.rag.service.RuntimeService;
import com.evidence.rag.service.VideoCompilationService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class RuntimeConfiguration {
  @Bean("runtimeService")
  RuntimeService configuredCleanupRuntimeService(
      RagSettings p,
      IngestionSettings ingestion,
      IndexingSettings indexing,
      AnswersSettings answers,
      DocumentRemovalSettings removal,
      Environment environment,
      ObjectProvider<ImageVectorIndexingService> imageVectors,
      ObjectProvider<AudioVectorIndexingService> audioVectors,
      ObjectProvider<DocumentCleanupService> cleanup,
      ObjectProvider<DocumentCleanupJob> cleanupJob,
      ObjectProvider<LibraryOperationGate> operations,
      ObjectProvider<ManagedTextRuntime> managed,
      ObjectProvider<LegacyTextProfileGuard> legacy,
      ObjectProvider<VisionModels> visual,
      ObjectProvider<VideoCompilationService> video) {
    var base =
        create(
            p,
            ingestion,
            indexing,
            answers,
            removal,
            environment,
            imageVectors.getIfAvailable() != null,
            audioVectors.getIfAvailable() != null,
            cleanup.getIfAvailable() != null
                && cleanupJob.getIfAvailable() != null
                && operations.getIfAvailable() != null);
    var runtime = managed.getIfAvailable();
    boolean textEvidenceOnly =
        environment.getProperty("rag.video.text-evidence-only", Boolean.class, false);
    // A batch only completes through automatic import indexing; without it every file halts.
    boolean autoIndex =
        environment.getProperty("rag.import-auto-index.enabled", Boolean.class, true);
    if (runtime == null) {
      return new RuntimeService(
          () ->
              textEvidenceCapabilities(
                  reindexCapabilities(base.capabilities(), autoIndex), textEvidenceOnly));
    }
    var guard = legacy.getIfAvailable();
    boolean visualPresent = visual.getIfAvailable() != null;
    boolean videoPresent = video.getIfAvailable() != null;
    return new RuntimeService(
        () ->
            managedCapabilities(
                textEvidenceCapabilities(
                    reindexCapabilities(base.capabilities(), autoIndex), textEvidenceOnly),
                runtime.currentVersion() != null,
                guard != null && guard.compatible(),
                visualPresent,
                videoPresent));
  }

  private static RuntimeCapabilitiesResult textEvidenceCapabilities(
      RuntimeCapabilitiesResult base, boolean textEvidenceOnly) {
    if (!textEvidenceOnly) {
      return base;
    }
    var enabled = new ArrayList<>(base.capabilities());
    var unavailable = new ArrayList<>(base.unavailable());
    disable(enabled, unavailable, List.of("image_vector_retrieval", "audio_vector_retrieval"));
    return new RuntimeCapabilitiesResult(
        base.authMode(), base.workspaceId(), base.migrationStage(), enabled, unavailable);
  }

  static RuntimeCapabilitiesResult reindexCapabilities(
      RuntimeCapabilitiesResult base, boolean autoIndex) {
    var enabled = new ArrayList<>(base.capabilities());
    var unavailable = new ArrayList<>(base.unavailable());
    enabled.add("wiki_maintenance");
    if (enabled.contains("ingestions")) {
      enabled.add("document_replacements");
    } else {
      unavailable.add("document_replacements");
    }
    // Open batches (append entries while uploading, then seal) come with batch upload itself.
    if (autoIndex && enabled.contains("ingestions") && enabled.contains("indexings")) {
      enabled.add("batch_upload");
      enabled.add("batch_streaming_upload");
      enabled.add("batch_organization");
      enabled.add("batch_sources");
    } else {
      unavailable.add("batch_upload");
      unavailable.add("batch_streaming_upload");
      unavailable.add("batch_organization");
      unavailable.add("batch_sources");
    }
    if (enabled.contains("text_index") && enabled.contains("indexings")) {
      enabled.add("text_reindex");
      enabled.add("text_reindex_with_vectors");
      enabled.add("batch_text_reindex");
    } else {
      unavailable.add("text_reindex");
      unavailable.add("text_reindex_with_vectors");
      unavailable.add("batch_text_reindex");
    }
    return new RuntimeCapabilitiesResult(
        base.authMode(), base.workspaceId(), base.migrationStage(), enabled, unavailable);
  }

  static RuntimeCapabilitiesResult managedCapabilities(
      RuntimeCapabilitiesResult base,
      boolean textReady,
      boolean legacyCompatible,
      boolean visualPresent,
      boolean videoPresent) {
    var enabled = new ArrayList<>(base.capabilities());
    var unavailable = new ArrayList<>(base.unavailable());
    enabled.add("model_configuration");
    enabled.add("retrieval_settings");
    enabled.add("model_index_rebuild");
    if (textReady) {
      enabled.add("retrieval_test");
      enabled.add("knowledge_answers");
    } else {
      disable(
          enabled,
          unavailable,
          List.of(
              "text_index",
              "indexings",
              "batch_upload",
              "batch_streaming_upload",
              "batch_organization",
              "batch_sources",
              "text_reindex",
              "text_reindex_with_vectors",
              "batch_text_reindex",
              "audio_index",
              "audio_answers",
              "audio_sources"));
      unavailable.add("retrieval_test");
      unavailable.add("knowledge_answers");
    }
    if (!legacyCompatible || !visualPresent) {
      disable(enabled, unavailable, List.of("visual_image_upload", "visual_answers"));
    }
    if (!textReady || !visualPresent) {
      disable(enabled, unavailable, List.of("visual_sources"));
    }
    if (!legacyCompatible || !videoPresent) {
      disable(enabled, unavailable, List.of("video_upload", "video_index"));
    }
    if (!legacyCompatible) {
      disable(enabled, unavailable, List.of("image_vector_retrieval", "audio_vector_retrieval"));
    }
    return new RuntimeCapabilitiesResult(
        base.authMode(),
        base.workspaceId(),
        textReady ? base.migrationStage() : "text_configuration_required",
        enabled,
        unavailable.stream().distinct().toList());
  }

  private static void disable(List<String> enabled, List<String> unavailable, List<String> names) {
    for (String name : names) {
      if (enabled.remove(name) && !unavailable.contains(name)) {
        unavailable.add(name);
      }
    }
  }

  RuntimeService configuredRuntimeService(
      RagSettings p,
      IngestionSettings ingestion,
      IndexingSettings indexing,
      AnswersSettings answers,
      DocumentRemovalSettings removal,
      Environment environment,
      ObjectProvider<ImageVectorIndexingService> imageVectors,
      ObjectProvider<AudioVectorIndexingService> audioVectors) {
    return create(
        p,
        ingestion,
        indexing,
        answers,
        removal,
        environment,
        imageVectors.getIfAvailable() != null,
        audioVectors.getIfAvailable() != null,
        false);
  }

  RuntimeService runtimeService(
      RagSettings p,
      IngestionSettings ingestion,
      IndexingSettings indexing,
      AnswersSettings answers,
      DocumentRemovalSettings removal,
      Environment environment) {
    return create(p, ingestion, indexing, answers, removal, environment, false, false, false);
  }

  private RuntimeService create(
      RagSettings p,
      IngestionSettings ingestion,
      IndexingSettings indexing,
      AnswersSettings answers,
      DocumentRemovalSettings removal,
      Environment environment,
      boolean imageVectorAvailable,
      boolean audioVectorAvailable,
      boolean cleanupAvailable) {
    return new RuntimeService(
        p.authMode(),
        p.workspaceId(),
        ingestion.enabled(),
        indexing.enabled(),
        answers.enabled(),
        removal.enabled(),
        ImageOcrConfiguration.options(environment) != null,
        environment.getProperty("rag.visual.enabled", Boolean.class, false),
        environment.getProperty("rag.audio.enabled", Boolean.class, false),
        environment.getProperty("rag.video.enabled", Boolean.class, false),
        environment.getProperty("rag.synopsis.enabled", Boolean.class, false),
        false,
        PdfOcrConfiguration.options(environment) != null,
        imageVectorAvailable
            && environment.getProperty("rag.image-embedding.enabled", Boolean.class, false),
        audioVectorAvailable
            && environment.getProperty("rag.audio-embedding.enabled", Boolean.class, false),
        cleanupAvailable
            && removal.enabled()
            && environment.getProperty("rag.document-cleanup.enabled", Boolean.class, false));
  }
}
