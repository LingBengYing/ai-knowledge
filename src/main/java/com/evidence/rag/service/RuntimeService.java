package com.evidence.rag.service;

import com.evidence.rag.model.dto.RuntimeCapabilitiesResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Publishes explicit local migration capabilities without exposing provider credentials. */
public final class RuntimeService {
  private final RuntimeCapabilitiesResult capabilities;
  private Supplier<RuntimeCapabilitiesResult> dynamic;

  public RuntimeService(Supplier<RuntimeCapabilitiesResult> dynamic) {
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
    this(authMode, workspaceId, ingestion, indexing, answers, documentRemoval, false, false);
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
        false,
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
        imageVectorRetrieval,
        audioVectorRetrieval,
        false);
  }

  /**
   * Full form. {@code queryAttachments} is ignored since attachment answers were removed; it stays
   * positional so the boolean argument order of existing callers keeps its meaning.
   */
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
      boolean imageVectorRetrieval,
      boolean audioVectorRetrieval,
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
    var unavailable = new ArrayList<>(List.of("reindex", "document_delete"));
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
    if (documentCleanup) {
      enabled.add("document_cleanup");
    }
    if (documentRemoval) {
      enabled.add("document_removal");
    }
    if (imageOcr && ingestion && !visual) {
      enabled.add("image_text_upload");
    }
    if (pdfOcr && ingestion) {
      enabled.add("pdf_ocr_upload");
    }
    if (visual && ingestion) {
      enabled.add("visual_image_upload");
    }
    // Web-compatibility names only: the web gates image/audio vector builds on them.
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
    if (synopsis) {
      enabled.addAll(List.of("file_synopsis", "synopsis_sources", "tag_suggestions"));
    } else {
      unavailable.addAll(List.of("file_synopsis", "synopsis_sources"));
    }
    // Same prerequisites the embedding configurations require before assembling the service.
    if (imageVectorRetrieval && indexing && visual && answers) {
      enabled.add("image_vector_retrieval");
    }
    if (audioVectorRetrieval && audio && ingestion && indexing && answers) {
      enabled.add("audio_vector_retrieval");
    }
    capabilities =
        new RuntimeCapabilitiesResult(
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

  public RuntimeCapabilitiesResult capabilities() {
    return dynamic == null ? capabilities : Objects.requireNonNull(dynamic.get());
  }
}
