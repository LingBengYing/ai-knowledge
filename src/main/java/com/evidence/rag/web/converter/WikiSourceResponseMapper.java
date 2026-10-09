package com.evidence.rag.web.converter;

import com.evidence.rag.model.domain.SynopsisEvidence;
import com.evidence.rag.model.domain.SynopsisSourceMaterial;
import com.evidence.rag.model.vo.WikiSourceResponse;
import java.util.Locale;

/**
 * Wiki navigation uses its own persisted binding, never a fabricated answer/synopsis identifier.
 */
public final class WikiSourceResponseMapper {
  private WikiSourceResponseMapper() {}

  public static WikiSourceResponse source(
      String sourceId, String base, SynopsisSourceMaterial material) {
    var evidence = material.evidence();
    String text =
        evidence.content() instanceof SynopsisEvidence.Text original ? original.text() : null;
    String proof =
        switch (evidence.kind()) {
          case TEXT -> "original_text";
          case IMAGE_OCR, VIDEO_OCR -> "machine_ocr";
          case IMAGE, VIDEO_FRAME -> "original_image";
          case AUDIO_TRANSCRIPT, VIDEO_TRANSCRIPT -> "machine_asr";
          case VIDEO_SUBTITLE -> "embedded_subtitle";
        };
    String precision =
        switch (evidence.kind()) {
          case AUDIO_TRANSCRIPT, VIDEO_TRANSCRIPT -> "server_chunk";
          case VIDEO_FRAME, VIDEO_OCR -> "frame_interval";
          case VIDEO_SUBTITLE -> "subtitle_cue";
          default -> null;
        };
    return new WikiSourceResponse(
        sourceId,
        evidence.id(),
        evidence.kind().name().toLowerCase(Locale.ROOT),
        evidence.sha256(),
        material.filename(),
        material.mediaType(),
        text,
        proof,
        precision,
        SynopsisResponseMapper.locator(material.locator()),
        base + "/content",
        material.frame() == null ? null : base + "/frame");
  }
}
