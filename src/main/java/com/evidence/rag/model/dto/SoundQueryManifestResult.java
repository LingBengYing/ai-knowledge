package com.evidence.rag.model.dto;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.QueryAttachment;
import com.evidence.rag.model.domain.QueryAttachmentManifest;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Explicit sound API whitelist; model judgments are not speech transcripts. */
public record SoundQueryManifestResult(
    int ordinal,
    @JsonProperty("source_sha256") String sourceSha256,
    @JsonProperty("media_kind") String mediaKind,
    @JsonProperty("compiler_revision") String compilerRevision,
    @JsonProperty("content_sha256") String contentSha256,
    @JsonProperty("text_code_points") int textCodePoints,
    @JsonProperty("visual_count") int visualCount,
    @JsonProperty("selected_image_sha256") List<String> selectedImageSha256,
    @JsonProperty("visual_sampled") boolean visualSampled) {
  public SoundQueryManifestResult {
    selectedImageSha256 = List.copyOf(selectedImageSha256);
  }

  public static SoundQueryManifestResult from(QueryAttachmentManifest manifest) {
    if (manifest == null
        || manifest.mediaKind() != QueryAttachment.Kind.AUDIO
        || manifest.textCodePoints() != 0
        || manifest.visualCount() != 0
        || !manifest.selectedImageSha256().isEmpty()
        || manifest.visualSampled()) {
      throw ModelValues.invalid();
    }
    return new SoundQueryManifestResult(
        manifest.ordinal(),
        manifest.sourceSha256(),
        "audio",
        manifest.compilerRevision(),
        manifest.contentSha256(),
        0,
        0,
        List.of(),
        false);
  }

  @Override
  public String toString() {
    return "SoundQueryManifestResult[redacted]";
  }
}
