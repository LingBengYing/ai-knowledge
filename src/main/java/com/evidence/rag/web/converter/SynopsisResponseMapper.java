package com.evidence.rag.web.converter;

import com.evidence.rag.model.domain.ImageTextRegion;
import com.evidence.rag.model.domain.SynopsisEvidence;
import com.evidence.rag.model.domain.SynopsisSourceMaterial;
import com.evidence.rag.model.dto.SynopsisDocumentResult;
import com.evidence.rag.model.dto.SynopsisTaskResult;
import com.evidence.rag.model.vo.SynopsisDocumentResponse;
import com.evidence.rag.model.vo.SynopsisSourceResponse;
import com.evidence.rag.model.vo.SynopsisTaskResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Public synopsis whitelist; original file bytes stay exclusively in content responses. */
public final class SynopsisResponseMapper {
  private SynopsisResponseMapper() {}

  public static SynopsisTaskResponse task(SynopsisTaskResult result) {
    return new SynopsisTaskResponse(
        result.taskId(),
        result.documentId(),
        result.publicationId(),
        result.state(),
        result.errorCode(),
        result.createdAt(),
        result.updatedAt());
  }

  public static SynopsisDocumentResponse document(SynopsisDocumentResult result) {
    var synopsis = result.synopsis();
    var publication = synopsis.publication();
    var entries = new ArrayList<SynopsisDocumentResponse.Entry>();
    for (var entry : synopsis.entries()) {
      int entryOrdinal = entries.size() + 1;
      var references = new ArrayList<SynopsisDocumentResponse.Reference>();
      for (var reference : entry.evidence()) {
        int sourceOrdinal = references.size() + 1;
        references.add(
            new SynopsisDocumentResponse.Reference(
                sourceOrdinal,
                reference.id(),
                name(reference.kind()),
                reference.sha256(),
                time(reference.time()),
                sourceUrl(result.synopsisId(), entryOrdinal, sourceOrdinal)));
      }
      entries.add(
          new SynopsisDocumentResponse.Entry(
              entryOrdinal,
              entry.item().section().name().toLowerCase(Locale.ROOT),
              entry.item().text(),
              time(entry.interval()),
              List.copyOf(references)));
    }
    return new SynopsisDocumentResponse(
        result.synopsisId(),
        publication.documentId(),
        publication.publicationId(),
        publication.sourceRevisionId(),
        publication.sourceSha256(),
        synopsis.inputFingerprint(),
        synopsis.modelRevision(),
        synopsis.policyRevision(),
        "available",
        List.copyOf(entries));
  }

  public static SynopsisSourceResponse source(
      String synopsisId, int entry, int source, SynopsisSourceMaterial material) {
    String base = sourceUrl(synopsisId, entry, source);
    var evidence = material.evidence();
    String text = evidence.content() instanceof SynopsisEvidence.Text value ? value.text() : null;
    String proof =
        switch (evidence.kind()) {
          case TEXT -> "original_text";
          case IMAGE_OCR, VIDEO_OCR -> "machine_ocr";
          case IMAGE, VIDEO_FRAME -> "machine_vlm";
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
    return new SynopsisSourceResponse(
        synopsisId,
        entry,
        source,
        evidence.id(),
        name(evidence.kind()),
        evidence.sha256(),
        material.filename(),
        material.mediaType(),
        text,
        proof,
        precision,
        locator(material.locator()),
        base + "/content",
        material.frame() == null ? null : base + "/frame");
  }

  private static SynopsisSourceResponse.Locator locator(SynopsisSourceMaterial.Locator locator) {
    return switch (locator) {
      case SynopsisSourceMaterial.Page page ->
          new SynopsisSourceResponse.Page(
              "page",
              page.page(),
              page.start(),
              page.end(),
              page.dimensions() == null ? null : page.dimensions().width(),
              page.dimensions() == null ? null : page.dimensions().height(),
              regions(page.regions()));
      case SynopsisSourceMaterial.Image image ->
          new SynopsisSourceResponse.Image(
              "image", image.dimensions().width(), image.dimensions().height());
      case SynopsisSourceMaterial.Audio audio ->
          new SynopsisSourceResponse.Audio(
              "audio_span", audio.spanOrdinal(), audio.time().startUs(), audio.time().endUs());
      case SynopsisSourceMaterial.VideoFrame frame ->
          new SynopsisSourceResponse.VideoFrame(
              "video_frame",
              frame.frameId(),
              frame.frameOrdinal(),
              frame.time().startUs(),
              frame.time().endUs(),
              frame.dimensions().width(),
              frame.dimensions().height());
      case SynopsisSourceMaterial.VideoTranscript transcript ->
          new SynopsisSourceResponse.VideoTranscript(
              "video_transcript",
              transcript.spanId(),
              transcript.spanOrdinal(),
              transcript.time().startUs(),
              transcript.time().endUs());
      case SynopsisSourceMaterial.VideoOcr ocr ->
          new SynopsisSourceResponse.VideoOcr(
              "video_ocr",
              ocr.frameId(),
              ocr.frameOrdinal(),
              ocr.time().startUs(),
              ocr.time().endUs(),
              ocr.dimensions().width(),
              ocr.dimensions().height(),
              ocr.start(),
              ocr.end(),
              regions(ocr.regions()));
      case SynopsisSourceMaterial.VideoSubtitle subtitle -> {
        var published = subtitle.subtitle();
        var cue = published.source();
        var track = published.track().track();
        yield new SynopsisSourceResponse.VideoSubtitle(
            "video_subtitle",
            cue.id(),
            cue.trackId(),
            cue.streamIndex(),
            track.codec(),
            track.language(),
            cue.cue().ordinal(),
            cue.cue().pts(),
            cue.cue().duration(),
            track.timeBaseNumerator(),
            track.timeBaseDenominator(),
            cue.startUs(),
            cue.endUs(),
            cue.startOffset(),
            cue.endOffset(),
            cue.cue().payloadSha256(),
            published.subtitleManifestSha256(),
            published.nativeManifestSha256(),
            published.trackTextSha256(),
            published.decoderRevision(),
            com.evidence.rag.model.domain.VideoSubtitleCompilation.TEXT_FORMAT);
      }
    };
  }

  private static List<SynopsisSourceResponse.Region> regions(List<ImageTextRegion> regions) {
    return regions.stream()
        .map(
            region ->
                new SynopsisSourceResponse.Region(
                    region.start(),
                    region.end(),
                    region.left(),
                    region.top(),
                    region.right(),
                    region.bottom()))
        .toList();
  }

  private static SynopsisDocumentResponse.TimeRange time(SynopsisEvidence.TimeRange time) {
    return time == null
        ? null
        : new SynopsisDocumentResponse.TimeRange(time.startUs(), time.endUs());
  }

  private static String name(SynopsisEvidence.Kind kind) {
    return kind.name().toLowerCase(Locale.ROOT);
  }

  private static String sourceUrl(String synopsisId, int entry, int source) {
    return "/v1/synopsis-sources/" + synopsisId + "/" + entry + "/" + source;
  }
}
