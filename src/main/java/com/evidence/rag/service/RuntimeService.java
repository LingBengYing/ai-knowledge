package com.evidence.rag.service;

import com.evidence.rag.model.dto.RuntimeCapabilities;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Publishes explicit local migration capabilities without exposing provider credentials. */
public final class RuntimeService {
  private final RuntimeCapabilities capabilities;
  private Supplier<RuntimeCapabilities> dynamic;

  public RuntimeService(Supplier<RuntimeCapabilities> dynamic) {
    this.capabilities = null;
    this.dynamic = Objects.requireNonNull(dynamic);
  }

  public RuntimeService(String authMode, String workspaceId, boolean ingestion, boolean indexing) {
    this(authMode, workspaceId, ingestion, indexing, false);
  }

  public RuntimeService(
      String authMode, String workspaceId, boolean ingestion, boolean indexing, boolean answers) {
    this(authMode, workspaceId, ingestion, indexing, answers, false);
  }

  public RuntimeService(
      String authMode,
      String workspaceId,
      boolean ingestion,
      boolean indexing,
      boolean answers,
      boolean documentRemoval) {
    this(authMode, workspaceId, ingestion, indexing, answers, documentRemoval, false);
  }

  public RuntimeService(
      String authMode,
      String workspaceId,
      boolean ingestion,
      boolean indexing,
      boolean answers,
      boolean documentRemoval,
      boolean imageOcr) {
    this(authMode, workspaceId, ingestion, indexing, answers, documentRemoval, imageOcr, false);
  }

  public RuntimeService(
      String authMode,
      String workspaceId,
      boolean ingestion,
      boolean indexing,
      boolean answers,
      boolean documentRemoval,
      boolean imageOcr,
      boolean visual) {
    this(
        authMode,
        workspaceId,
        ingestion,
        indexing,
        answers,
        documentRemoval,
        imageOcr,
        visual,
        false);
  }

  public RuntimeService(
      String authMode,
      String workspaceId,
      boolean ingestion,
      boolean indexing,
      boolean answers,
      boolean documentRemoval,
      boolean imageOcr,
      boolean visual,
      boolean audio) {
    this(
        authMode,
        workspaceId,
        ingestion,
        indexing,
        answers,
        documentRemoval,
        imageOcr,
        visual,
        audio,
        false);
  }

  public RuntimeService(
      String authMode,
      String workspaceId,
      boolean ingestion,
      boolean indexing,
      boolean answers,
      boolean documentRemoval,
      boolean imageOcr,
      boolean visual,
      boolean audio,
      boolean video) {
    this(
        authMode,
        workspaceId,
        ingestion,
        indexing,
        answers,
        documentRemoval,
        imageOcr,
        visual,
        audio,
        video,
        false);
  }

  public RuntimeService(
      String authMode,
      String workspaceId,
      boolean ingestion,
      boolean indexing,
      boolean answers,
      boolean documentRemoval,
      boolean imageOcr,
      boolean visual,
      boolean audio,
      boolean video,
      boolean synopsis) {
    this(
        authMode,
        workspaceId,
        ingestion,
        indexing,
        answers,
        documentRemoval,
        imageOcr,
        visual,
        audio,
        video,
        synopsis,
        false);
  }

  public RuntimeService(
      String authMode,
      String workspaceId,
      boolean ingestion,
      boolean indexing,
      boolean answers,
      boolean documentRemoval,
      boolean imageOcr,
      boolean visual,
      boolean audio,
      boolean video,
      boolean synopsis,
      boolean queryAttachments) {
    this(
        authMode,
        workspaceId,
        ingestion,
        indexing,
        answers,
        documentRemoval,
        imageOcr,
        visual,
        audio,
        video,
        synopsis,
        queryAttachments,
        false);
  }

  public RuntimeService(
      String authMode,
      String workspaceId,
      boolean ingestion,
      boolean indexing,
      boolean answers,
      boolean documentRemoval,
      boolean imageOcr,
      boolean visual,
      boolean audio,
      boolean video,
      boolean synopsis,
      boolean queryAttachments,
      boolean pdfOcr) {
    this(
        authMode,
        workspaceId,
        ingestion,
        indexing,
        answers,
        documentRemoval,
        imageOcr,
        visual,
        audio,
        video,
        synopsis,
        queryAttachments,
        pdfOcr,
        false);
  }

  public RuntimeService(
      String authMode,
      String workspaceId,
      boolean ingestion,
      boolean indexing,
      boolean answers,
      boolean documentRemoval,
      boolean imageOcr,
      boolean visual,
      boolean audio,
      boolean video,
      boolean synopsis,
      boolean queryAttachments,
      boolean pdfOcr,
      boolean voiceQuestions) {
    this(
        authMode,
        workspaceId,
        ingestion,
        indexing,
        answers,
        documentRemoval,
        imageOcr,
        visual,
        audio,
        video,
        synopsis,
        queryAttachments,
        pdfOcr,
        voiceQuestions,
        false);
  }

  public RuntimeService(
      String authMode,
      String workspaceId,
      boolean ingestion,
      boolean indexing,
      boolean answers,
      boolean documentRemoval,
      boolean imageOcr,
      boolean visual,
      boolean audio,
      boolean video,
      boolean synopsis,
      boolean queryAttachments,
      boolean pdfOcr,
      boolean voiceQuestions,
      boolean imageVectorRetrieval) {
    this(
        authMode,
        workspaceId,
        ingestion,
        indexing,
        answers,
        documentRemoval,
        imageOcr,
        visual,
        audio,
        video,
        synopsis,
        queryAttachments,
        pdfOcr,
        voiceQuestions,
        imageVectorRetrieval,
        false);
  }

  public RuntimeService(
      String authMode,
      String workspaceId,
      boolean ingestion,
      boolean indexing,
      boolean answers,
      boolean documentRemoval,
      boolean imageOcr,
      boolean visual,
      boolean audio,
      boolean video,
      boolean synopsis,
      boolean queryAttachments,
      boolean pdfOcr,
      boolean voiceQuestions,
      boolean imageVectorRetrieval,
      boolean audioVectorRetrieval) {
    this(
        authMode,
        workspaceId,
        ingestion,
        indexing,
        answers,
        documentRemoval,
        imageOcr,
        visual,
        audio,
        video,
        synopsis,
        queryAttachments,
        pdfOcr,
        voiceQuestions,
        imageVectorRetrieval,
        audioVectorRetrieval,
        false);
  }

  public RuntimeService(
      String authMode,
      String workspaceId,
      boolean ingestion,
      boolean indexing,
      boolean answers,
      boolean documentRemoval,
      boolean imageOcr,
      boolean visual,
      boolean audio,
      boolean video,
      boolean synopsis,
      boolean queryAttachments,
      boolean pdfOcr,
      boolean voiceQuestions,
      boolean imageVectorRetrieval,
      boolean audioVectorRetrieval,
      boolean sound) {
    this(
        authMode,
        workspaceId,
        ingestion,
        indexing,
        answers,
        documentRemoval,
        imageOcr,
        visual,
        audio,
        video,
        synopsis,
        queryAttachments,
        pdfOcr,
        voiceQuestions,
        imageVectorRetrieval,
        audioVectorRetrieval,
        sound,
        false);
  }

  public RuntimeService(
      String authMode,
      String workspaceId,
      boolean ingestion,
      boolean indexing,
      boolean answers,
      boolean documentRemoval,
      boolean imageOcr,
      boolean visual,
      boolean audio,
      boolean video,
      boolean synopsis,
      boolean queryAttachments,
      boolean pdfOcr,
      boolean voiceQuestions,
      boolean imageVectorRetrieval,
      boolean audioVectorRetrieval,
      boolean sound,
      boolean videoAv) {
    this(
        authMode,
        workspaceId,
        ingestion,
        indexing,
        answers,
        documentRemoval,
        imageOcr,
        visual,
        audio,
        video,
        synopsis,
        queryAttachments,
        pdfOcr,
        voiceQuestions,
        imageVectorRetrieval,
        audioVectorRetrieval,
        sound,
        videoAv,
        false);
  }

  public RuntimeService(
      String authMode,
      String workspaceId,
      boolean ingestion,
      boolean indexing,
      boolean answers,
      boolean documentRemoval,
      boolean imageOcr,
      boolean visual,
      boolean audio,
      boolean video,
      boolean synopsis,
      boolean queryAttachments,
      boolean pdfOcr,
      boolean voiceQuestions,
      boolean imageVectorRetrieval,
      boolean audioVectorRetrieval,
      boolean sound,
      boolean videoAv,
      boolean videoAvQueryAttachments) {
    this(
        authMode,
        workspaceId,
        ingestion,
        indexing,
        answers,
        documentRemoval,
        imageOcr,
        visual,
        audio,
        video,
        synopsis,
        queryAttachments,
        pdfOcr,
        voiceQuestions,
        imageVectorRetrieval,
        audioVectorRetrieval,
        sound,
        videoAv,
        videoAvQueryAttachments,
        false);
  }

  public RuntimeService(
      String authMode,
      String workspaceId,
      boolean ingestion,
      boolean indexing,
      boolean answers,
      boolean documentRemoval,
      boolean imageOcr,
      boolean visual,
      boolean audio,
      boolean video,
      boolean synopsis,
      boolean queryAttachments,
      boolean pdfOcr,
      boolean voiceQuestions,
      boolean imageVectorRetrieval,
      boolean audioVectorRetrieval,
      boolean sound,
      boolean videoAv,
      boolean videoAvQueryAttachments,
      boolean documentCleanup) {
    var enabled =
        new ArrayList<>(
            List.of(
                "management",
                "folders",
                "metadata",
                "batch_move",
                "batch_tag",
                "document_originals"));
    var unavailable = new ArrayList<>(List.of("answers", "sources", "reindex", "document_delete"));
    if (ingestion) {
      enabled.addAll(List.of("text_upload", "ingestions"));
    } else {
      unavailable.addAll(List.of("upload", "ingestions"));
    }
    if (indexing) {
      enabled.addAll(List.of("text_index", "indexings"));
    } else {
      unavailable.addAll(List.of("text_index", "indexings"));
    }
    if (answers) {
      enabled.addAll(List.of("answers", "sources"));
      unavailable.removeAll(List.of("answers", "sources"));
    }
    if (documentCleanup) {
      enabled.add("document_cleanup");
    }
    if (documentRemoval) {
      enabled.add("document_removal");
    }
    if (imageOcr && ingestion && !visual) {
      enabled.add("image_text_upload");
    }
    if (imageOcr && answers) {
      enabled.add("source_image_content");
    }
    if (pdfOcr && ingestion) {
      enabled.add("pdf_ocr_upload");
    }
    if (visual && ingestion) {
      enabled.add("visual_image_upload");
    }
    if (visual && answers) {
      enabled.addAll(List.of("visual_answers", "visual_sources"));
    }
    if (audio && ingestion) {
      enabled.add("audio_upload");
    }
    if (audio && indexing) {
      enabled.add("audio_index");
    }
    if (audio && answers) {
      enabled.addAll(List.of("audio_answers", "audio_sources"));
    }
    if (video && ingestion) {
      enabled.add("video_upload");
    }
    if (video && indexing) {
      enabled.add("video_index");
    }
    if (video && answers) {
      enabled.addAll(List.of("video_answers", "video_sources"));
    }
    if (synopsis) {
      enabled.addAll(List.of("file_synopsis", "synopsis_sources", "tag_suggestions"));
    } else {
      unavailable.addAll(List.of("file_synopsis", "synopsis_sources"));
    }
    if (queryAttachments && answers && visual && imageOcr && audio && video) {
      enabled.add("query_attachments");
    }
    if (voiceQuestions && audio && ingestion && answers) {
      enabled.add("voice_questions");
    }
    if (imageVectorRetrieval && indexing && enabled.contains("query_attachments")) {
      enabled.add("image_vector_retrieval");
    }
    if (audioVectorRetrieval && ingestion && indexing && enabled.contains("query_attachments")) {
      enabled.add("audio_vector_retrieval");
    }
    if (sound && ingestion) {
      enabled.addAll(
          List.of(
              "sound_upload",
              "sound_index",
              "sound_answers",
              "sound_sources",
              "sound_query_attachments"));
    }
    if (videoAv && ingestion) {
      enabled.addAll(
          List.of("video_av_upload", "video_av_index", "video_av_answers", "video_av_sources"));
      if (videoAvQueryAttachments) {
        enabled.add("video_av_query_attachments");
      }
    }
    capabilities =
        new RuntimeCapabilities(
            authMode,
            workspaceId,
            visual && answers
                ? "visual_answers"
                : answers
                    ? "text_answers"
                    : indexing
                        ? "text_indexing"
                        : ingestion ? "text_ingestion" : "management_slice",
            enabled,
            unavailable);
  }

  public RuntimeCapabilities capabilities() {
    return dynamic == null ? capabilities : Objects.requireNonNull(dynamic.get());
  }
}
