package com.evidence.rag.config;

import com.evidence.rag.job.DocumentCleanupJob;
import com.evidence.rag.model.domain.LibraryOperationGate;
import com.evidence.rag.model.dto.RuntimeCapabilities;
import com.evidence.rag.service.AudioVectorIndexingService;
import com.evidence.rag.service.DocumentCleanupService;
import com.evidence.rag.service.ImageVectorIndexingService;
import com.evidence.rag.service.LegacyTextProfileGuard;
import com.evidence.rag.service.ManagedTextRuntime;
import com.evidence.rag.service.QueryAttachmentService;
import com.evidence.rag.service.RuntimeService;
import com.evidence.rag.service.SoundAnswerService;
import com.evidence.rag.service.SoundLibraryService;
import com.evidence.rag.service.VideoAvAnswerService;
import com.evidence.rag.service.VideoAvLibraryService;
import com.evidence.rag.service.VideoCompilationService;
import com.evidence.rag.service.VisualAnswerService;
import com.evidence.rag.web.BoundedMediaQueryServlet;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class RuntimeConfiguration {
  @Bean("runtimeService")
  RuntimeService configuredCleanupRuntimeService(
      RagProperties p,
      IngestionSettings ingestion,
      IndexingSettings indexing,
      AnswersSettings answers,
      DocumentRemovalSettings removal,
      Environment environment,
      ObjectProvider<ImageVectorIndexingService> imageVectors,
      ObjectProvider<AudioVectorIndexingService> audioVectors,
      ObjectProvider<SoundLibraryService> soundLibrary,
      ObjectProvider<SoundAnswerService> soundAnswers,
      ObjectProvider<VideoAvLibraryService> videoAvLibrary,
      ObjectProvider<VideoAvAnswerService> videoAvAnswers,
      @Qualifier("videoAvQueryServlet")
          ObjectProvider<ServletRegistrationBean<BoundedMediaQueryServlet>> videoAvQueries,
      ObjectProvider<DocumentCleanupService> cleanup,
      ObjectProvider<DocumentCleanupJob> cleanupJob,
      ObjectProvider<LibraryOperationGate> operations,
      ObjectProvider<ManagedTextRuntime> managed,
      ObjectProvider<LegacyTextProfileGuard> legacy,
      ObjectProvider<VisualAnswerService> visual,
      ObjectProvider<VideoCompilationService> video,
      ObjectProvider<QueryAttachmentService> attachments) {
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
            soundLibrary.getIfAvailable() != null && soundAnswers.getIfAvailable() != null,
            videoAvLibrary.getIfAvailable() != null && videoAvAnswers.getIfAvailable() != null,
            videoAvQueries.getIfAvailable() != null,
            cleanup.getIfAvailable() != null
                && cleanupJob.getIfAvailable() != null
                && operations.getIfAvailable() != null);
    var runtime = managed.getIfAvailable();
    if (runtime == null) {
      return new RuntimeService(() -> reindexCapabilities(base.capabilities()));
    }
    var guard = legacy.getIfAvailable();
    boolean visualPresent = visual.getIfAvailable() != null;
    boolean videoPresent = video.getIfAvailable() != null;
    boolean attachmentsPresent = attachments.getIfAvailable() != null;
    return new RuntimeService(
        () ->
            managedCapabilities(
                reindexCapabilities(base.capabilities()),
                runtime.currentVersion() != null,
                guard != null && guard.compatible(),
                visualPresent,
                videoPresent,
                attachmentsPresent));
  }

  private static RuntimeCapabilities reindexCapabilities(RuntimeCapabilities base) {
    var enabled = new ArrayList<>(base.capabilities());
    var unavailable = new ArrayList<>(base.unavailable());
    if (enabled.contains("text_index") && enabled.contains("indexings")) {
      enabled.add("text_reindex");
    } else {
      unavailable.add("text_reindex");
    }
    return new RuntimeCapabilities(
        base.authMode(), base.workspaceId(), base.migrationStage(), enabled, unavailable);
  }

  static RuntimeCapabilities managedCapabilities(
      RuntimeCapabilities base,
      boolean textReady,
      boolean legacyCompatible,
      boolean visualPresent,
      boolean videoPresent,
      boolean attachmentsPresent) {
    var enabled = new ArrayList<>(base.capabilities());
    var unavailable = new ArrayList<>(base.unavailable());
    enabled.add("model_configuration");
    if (textReady) {
      enabled.add("retrieval_test");
    } else {
      disable(
          enabled,
          unavailable,
          List.of(
              "text_index",
              "indexings",
              "text_reindex",
              "answers",
              "sources",
              "source_image_content",
              "audio_index",
              "audio_answers",
              "audio_sources"));
      unavailable.add("retrieval_test");
    }
    if (!legacyCompatible || !visualPresent) {
      disable(enabled, unavailable, List.of("visual_image_upload", "visual_answers"));
    }
    if (!textReady || !visualPresent) {
      disable(enabled, unavailable, List.of("visual_sources"));
    }
    if (!legacyCompatible || !videoPresent) {
      disable(enabled, unavailable, List.of("video_upload", "video_index", "video_answers"));
    }
    if (!textReady) {
      disable(enabled, unavailable, List.of("video_sources"));
    }
    if (!legacyCompatible || !attachmentsPresent) {
      disable(
          enabled,
          unavailable,
          List.of("query_attachments", "image_vector_retrieval", "audio_vector_retrieval"));
    }
    return new RuntimeCapabilities(
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

  RuntimeService configuredVideoAvRuntimeService(
      RagProperties p,
      IngestionSettings ingestion,
      IndexingSettings indexing,
      AnswersSettings answers,
      DocumentRemovalSettings removal,
      Environment environment,
      ObjectProvider<ImageVectorIndexingService> imageVectors,
      ObjectProvider<AudioVectorIndexingService> audioVectors,
      ObjectProvider<SoundLibraryService> soundLibrary,
      ObjectProvider<SoundAnswerService> soundAnswers,
      ObjectProvider<VideoAvLibraryService> videoAvLibrary,
      ObjectProvider<VideoAvAnswerService> videoAvAnswers,
      @Qualifier("videoAvQueryServlet")
          ObjectProvider<ServletRegistrationBean<BoundedMediaQueryServlet>> videoAvQueries) {
    return create(
        p,
        ingestion,
        indexing,
        answers,
        removal,
        environment,
        imageVectors.getIfAvailable() != null,
        audioVectors.getIfAvailable() != null,
        soundLibrary.getIfAvailable() != null && soundAnswers.getIfAvailable() != null,
        videoAvLibrary.getIfAvailable() != null && videoAvAnswers.getIfAvailable() != null,
        videoAvQueries.getIfAvailable() != null);
  }

  RuntimeService configuredSoundRuntimeService(
      RagProperties p,
      IngestionSettings ingestion,
      IndexingSettings indexing,
      AnswersSettings answers,
      DocumentRemovalSettings removal,
      Environment environment,
      ObjectProvider<ImageVectorIndexingService> imageVectors,
      ObjectProvider<AudioVectorIndexingService> audioVectors,
      ObjectProvider<SoundLibraryService> soundLibrary,
      ObjectProvider<SoundAnswerService> soundAnswers) {
    return create(
        p,
        ingestion,
        indexing,
        answers,
        removal,
        environment,
        imageVectors.getIfAvailable() != null,
        audioVectors.getIfAvailable() != null,
        soundLibrary.getIfAvailable() != null && soundAnswers.getIfAvailable() != null);
  }

  RuntimeService configuredRuntimeService(
      RagProperties p,
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
      RagProperties p,
      IngestionSettings ingestion,
      IndexingSettings indexing,
      AnswersSettings answers,
      DocumentRemovalSettings removal,
      Environment environment) {
    return create(p, ingestion, indexing, answers, removal, environment, false, false, false);
  }

  private RuntimeService create(
      RagProperties p,
      IngestionSettings ingestion,
      IndexingSettings indexing,
      AnswersSettings answers,
      DocumentRemovalSettings removal,
      Environment environment,
      boolean imageVectorAvailable,
      boolean audioVectorAvailable,
      boolean soundAvailable) {
    return create(
        p,
        ingestion,
        indexing,
        answers,
        removal,
        environment,
        imageVectorAvailable,
        audioVectorAvailable,
        soundAvailable,
        false);
  }

  private RuntimeService create(
      RagProperties p,
      IngestionSettings ingestion,
      IndexingSettings indexing,
      AnswersSettings answers,
      DocumentRemovalSettings removal,
      Environment environment,
      boolean imageVectorAvailable,
      boolean audioVectorAvailable,
      boolean soundAvailable,
      boolean videoAvAvailable) {
    return create(
        p,
        ingestion,
        indexing,
        answers,
        removal,
        environment,
        imageVectorAvailable,
        audioVectorAvailable,
        soundAvailable,
        videoAvAvailable,
        false);
  }

  private RuntimeService create(
      RagProperties p,
      IngestionSettings ingestion,
      IndexingSettings indexing,
      AnswersSettings answers,
      DocumentRemovalSettings removal,
      Environment environment,
      boolean imageVectorAvailable,
      boolean audioVectorAvailable,
      boolean soundAvailable,
      boolean videoAvAvailable,
      boolean videoAvQueryAvailable) {
    return create(
        p,
        ingestion,
        indexing,
        answers,
        removal,
        environment,
        imageVectorAvailable,
        audioVectorAvailable,
        soundAvailable,
        videoAvAvailable,
        videoAvQueryAvailable,
        false);
  }

  private RuntimeService create(
      RagProperties p,
      IngestionSettings ingestion,
      IndexingSettings indexing,
      AnswersSettings answers,
      DocumentRemovalSettings removal,
      Environment environment,
      boolean imageVectorAvailable,
      boolean audioVectorAvailable,
      boolean soundAvailable,
      boolean videoAvAvailable,
      boolean videoAvQueryAvailable,
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
        environment.getProperty("rag.query-attachments.enabled", Boolean.class, false),
        PdfOcrConfiguration.options(environment) != null,
        environment.getProperty("rag.voice-questions.enabled", Boolean.class, false),
        imageVectorAvailable
            && environment.getProperty("rag.image-embedding.enabled", Boolean.class, false),
        audioVectorAvailable
            && environment.getProperty("rag.audio-embedding.enabled", Boolean.class, false),
        soundAvailable && environment.getProperty("rag.sound.enabled", Boolean.class, false),
        videoAvAvailable && environment.getProperty("rag.video-av.enabled", Boolean.class, false),
        videoAvAvailable
            && videoAvQueryAvailable
            && environment.getProperty("rag.video-av.enabled", Boolean.class, false),
        cleanupAvailable
            && removal.enabled()
            && environment.getProperty("rag.document-cleanup.enabled", Boolean.class, false));
  }
}
