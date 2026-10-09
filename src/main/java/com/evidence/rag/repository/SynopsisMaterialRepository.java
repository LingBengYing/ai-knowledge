package com.evidence.rag.repository;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AudioTranscriptSpan;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.EvidenceScope;
import com.evidence.rag.model.domain.FileSynopsis;
import com.evidence.rag.model.domain.ImageDimensions;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.PublishedVideoSubtitleEvidence;
import com.evidence.rag.model.domain.SynopsisEvidence;
import com.evidence.rag.model.domain.SynopsisFileInput;
import com.evidence.rag.model.domain.SynopsisInput;
import com.evidence.rag.model.domain.SynopsisSourceMaterial;
import com.evidence.rag.model.domain.VideoTranscriptEvidence;
import com.evidence.rag.model.domain.VisualImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Complete synopsis material and original sources; the caller owns the authority transaction. */
public final class SynopsisMaterialRepository {
  private final SqliteAuthorityStore store;
  private final EvidenceRepository evidence;

  public SynopsisMaterialRepository(SqliteAuthorityStore store) {
    this.store = java.util.Objects.requireNonNull(store);
    this.evidence = new EvidenceRepository(store);
  }

  public Optional<PublicationVersion> publication(Actor actor, String documentId) {
    if (actor == null) {
      throw ModelValues.invalid();
    }
    ModelValues.identifier(documentId, 100);
    return evidence
        .findActivePublications(actor, DocumentSelection.selected(List.of(documentId)))
        .stream()
        .findFirst();
  }

  public SynopsisInput load(Actor actor, PublicationVersion publication) {
    var scope = current(actor, publication);
    var entries = entries(actor, publication);
    long images =
        entries.stream()
            .filter(
                e ->
                    e.kind() == SynopsisEvidence.Kind.IMAGE
                        || e.kind() == SynopsisEvidence.Kind.VIDEO_FRAME)
            .count();
    if (entries.size() > SynopsisInput.MAX_EVIDENCE || images > SynopsisInput.MAX_IMAGES) {
      throw capacity();
    }
    var original = original(actor, publication);
    var subtitles = subtitles(scope, entries);
    var items = new ArrayList<SynopsisEvidence>();
    long codePoints = 0;
    long imageBytes = 0;
    for (var entry : entries) {
      var item = material(scope, entry, original, subtitles).evidence();
      switch (item.content()) {
        case SynopsisEvidence.Text text ->
            codePoints += text.text().codePointCount(0, text.text().length());
        case SynopsisEvidence.Image image -> imageBytes += image.image().content().length;
      }
      if (codePoints > SynopsisInput.MAX_TEXT_CODE_POINTS
          || imageBytes > SynopsisInput.MAX_IMAGE_BYTES) {
        throw capacity();
      }
      items.add(item);
    }
    return new SynopsisInput(publication, items);
  }

  public SynopsisFileInput document(Actor actor, PublicationVersion publication) {
    var scope = current(actor, publication);
    var entries = entries(actor, publication);
    long images =
        entries.stream()
            .filter(
                e ->
                    e.kind() == SynopsisEvidence.Kind.IMAGE
                        || e.kind() == SynopsisEvidence.Kind.VIDEO_FRAME)
            .count();
    if (entries.size() > SynopsisFileInput.MAX_EVIDENCE || images > SynopsisFileInput.MAX_IMAGES) {
      throw capacity();
    }
    var original = original(actor, publication);
    var subtitles = subtitles(scope, entries);
    var items = new ArrayList<SynopsisEvidence>();
    long codePoints = 0;
    long imageBytes = 0;
    for (var entry : entries) {
      var item = material(scope, entry, original, subtitles).evidence();
      switch (item.content()) {
        case SynopsisEvidence.Text text ->
            codePoints += text.text().codePointCount(0, text.text().length());
        case SynopsisEvidence.Image image -> imageBytes += image.image().content().length;
      }
      if (codePoints > SynopsisFileInput.MAX_TEXT_CODE_POINTS
          || imageBytes > SynopsisFileInput.MAX_IMAGE_BYTES) {
        throw capacity();
      }
      items.add(item);
    }
    return new SynopsisFileInput(publication, items);
  }

  public SynopsisSourceMaterial source(
      Actor actor, PublicationVersion publication, FileSynopsis.Reference reference) {
    if (reference == null) {
      throw ModelValues.invalid();
    }
    var scope = current(actor, publication);
    var entry =
        entries(actor, publication).stream()
            .filter(e -> e.id().equals(reference.id()))
            .findFirst()
            .orElseThrow(ModelValues::notFound);
    var original = original(actor, publication);
    var material = material(scope, entry, original, subtitles(scope, List.of(entry)));
    var item = material.evidence();
    if (!new FileSynopsis.Reference(item.id(), item.sha256(), item.kind(), item.time())
        .equals(reference)) {
      throw ModelValues.notFound();
    }
    return new SynopsisSourceMaterial(
        item,
        original.filename(),
        original.mediaType(),
        material.locator(),
        original.bytes(),
        material.frame());
  }

  private EvidenceScope current(Actor actor, PublicationVersion expected) {
    if (expected == null) {
      throw ModelValues.invalid();
    }
    if (!publication(actor, expected.documentId()).filter(expected::equals).isPresent()) {
      throw ModelValues.notFound();
    }
    return new EvidenceScope(
        actor, DocumentSelection.selected(List.of(expected.documentId())), List.of(expected));
  }

  /** Source order matches the complete indexing snapshot, never physical-id or recall ranking. */
  private List<MaterialEntry> entries(Actor actor, PublicationVersion publication) {
    var rows =
        store.rows(
            """
        SELECT e.*,d.document_type FROM (
          SELECT e.publication_id,e.physical_segment_id AS physical_id,s.id AS source_id,
            0 AS kind_order,'TEXT' AS kind,s.ordinal AS primary_order,0 AS secondary_order
          FROM index_publication_entries e JOIN corpus_segments s ON s.id=e.source_segment_id
          JOIN index_publications p ON p.id=e.publication_id AND p.revision_id=s.revision_id
          UNION ALL
          SELECT e.publication_id,e.physical_segment_id,i.id,1,'IMAGE',0,0
          FROM image_publication_entries e JOIN image_evidence i ON i.id=e.image_evidence_id
          JOIN index_publications p ON p.id=e.publication_id AND p.revision_id=i.revision_id
          UNION ALL
          SELECT e.publication_id,e.physical_segment_id,s.id,2,'AUDIO_TRANSCRIPT',s.index_ordinal,0
          FROM audio_publication_entries e JOIN audio_spans s ON s.id=e.audio_span_id AND s.index_ordinal IS NOT NULL
          JOIN index_publications p ON p.id=e.publication_id AND p.revision_id=s.revision_id
          UNION ALL
          SELECT e.publication_id,e.physical_segment_id,f.id,3,'VIDEO_FRAME',f.ordinal,0
          FROM video_frame_publication_entries e JOIN video_frames f ON f.id=e.video_frame_id
          JOIN index_publications p ON p.id=e.publication_id AND p.revision_id=f.revision_id
          UNION ALL
          SELECT e.publication_id,e.physical_segment_id,s.id,4,'VIDEO_TRANSCRIPT',s.index_ordinal,0
          FROM video_transcript_publication_entries e JOIN video_transcript_spans s ON s.id=e.video_transcript_span_id AND s.index_ordinal IS NOT NULL
          JOIN index_publications p ON p.id=e.publication_id AND p.revision_id=s.revision_id
          UNION ALL
          SELECT e.publication_id,e.physical_segment_id,s.id,5,'VIDEO_OCR',f.ordinal,s.ordinal
          FROM video_ocr_publication_entries e JOIN video_ocr_segments s ON s.id=e.video_ocr_segment_id
          JOIN video_frame_ocr f ON f.frame_id=s.frame_id AND f.revision_id=s.revision_id
          JOIN index_publications p ON p.id=e.publication_id AND p.revision_id=s.revision_id
          UNION ALL
          SELECT e.publication_id,e.physical_segment_id,s.id,6,'VIDEO_SUBTITLE',s.stream_index,s.ordinal
          FROM video_subtitle_publication_entries e JOIN video_subtitle_cues s ON s.id=e.video_subtitle_cue_id AND s.index_ordinal IS NOT NULL
          JOIN index_publications p ON p.id=e.publication_id AND p.revision_id=s.revision_id
        ) e JOIN index_publications p ON p.id=e.publication_id
        JOIN active_corpus_publications a ON a.publication_id=p.id AND a.document_id=p.document_id AND a.revision_id=p.revision_id
        JOIN documents d ON d.id=p.document_id
        WHERE p.id=? AND p.revision_id=? AND d.workspace_id=?
          AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
        ORDER BY e.kind_order,e.primary_order,e.secondary_order,e.source_id
        """,
            publication.publicationId(),
            publication.sourceRevisionId(),
            actor.workspaceId());
    if (rows.size() != publication.segmentCount()) {
      throw ModelValues.notFound();
    }
    var ids = new HashSet<String>();
    var sources = new HashSet<String>();
    var result = new ArrayList<MaterialEntry>();
    for (var row : rows) {
      String id = AuthorityRows.text(row, "physical_id");
      String source = AuthorityRows.text(row, "source_id");
      if (!ids.add(id) || !sources.add(source) || id.equals(source)) {
        throw ModelValues.notFound();
      }
      var kind = SynopsisEvidence.Kind.valueOf(AuthorityRows.text(row, "kind"));
      if (kind == SynopsisEvidence.Kind.TEXT
          && "image".equals(AuthorityRows.text(row, "document_type"))) {
        kind = SynopsisEvidence.Kind.IMAGE_OCR;
      }
      result.add(new MaterialEntry(id, source, kind));
    }
    return List.copyOf(result);
  }

  /** Once per material load: the original hash binds all derived sources to the sealed file. */
  private Original original(Actor actor, PublicationVersion publication) {
    var rows =
        store.rows(
            """
        SELECT d.filename,d.mime_type,c.original_blob FROM index_publications p
        JOIN active_corpus_publications a ON a.publication_id=p.id AND a.document_id=p.document_id AND a.revision_id=p.revision_id
        JOIN documents d ON d.id=p.document_id
        JOIN corpus_documents c ON c.document_id=d.id AND c.initial_revision_id=p.revision_id
        WHERE p.id=? AND p.revision_id=? AND p.source_sha256=? AND d.source_sha256=p.source_sha256
          AND d.workspace_id=?
          AND LENGTH(c.original_blob) BETWEEN 1 AND 20971520
          AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
        """,
            publication.publicationId(),
            publication.sourceRevisionId(),
            publication.sourceSha256(),
            actor.workspaceId());
    if (rows.size() != 1) {
      throw ModelValues.notFound();
    }
    var row = rows.getFirst();
    byte[] bytes = (byte[]) row.get("original_blob");
    if (!ModelValues.sha256(bytes).equals(publication.sourceSha256())) {
      throw ModelValues.notFound();
    }
    return new Original(
        AuthorityRows.text(row, "filename"), AuthorityRows.text(row, "mime_type"), bytes);
  }

  private Map<String, PublishedVideoSubtitleEvidence> subtitles(
      EvidenceScope scope, List<MaterialEntry> entries) {
    var ids =
        entries.stream()
            .filter(e -> e.kind() == SynopsisEvidence.Kind.VIDEO_SUBTITLE)
            .map(MaterialEntry::id)
            .toList();
    if (ids.isEmpty()) {
      return Map.of();
    }
    var result = new HashMap<String, PublishedVideoSubtitleEvidence>();
    for (var source : evidence.findPublishedVideoSubtitleEvidence(scope, ids)) {
      if (result.put(source.physicalSegmentId(), source) != null
          || !ids.contains(source.physicalSegmentId())) {
        throw ModelValues.notFound();
      }
    }
    if (result.size() != ids.size()) {
      throw ModelValues.notFound();
    }
    return Map.copyOf(result);
  }

  private Material material(
      EvidenceScope scope,
      MaterialEntry entry,
      Original original,
      Map<String, PublishedVideoSubtitleEvidence> subtitles) {
    var publication = scope.publications().getFirst();
    var ids = List.of(entry.id());
    return switch (entry.kind()) {
      case TEXT, IMAGE_OCR -> {
        var source = one(evidence.findPublishedEvidence(scope, ids));
        if (!source.segment().segmentId().equals(entry.sourceId())) {
          throw ModelValues.notFound();
        }
        var segment = source.segment();
        var regions =
            entry.kind() == SynopsisEvidence.Kind.IMAGE_OCR
                ? evidence.findImageRegions(publication, segment.start(), segment.end())
                : List.<com.evidence.rag.model.domain.ImageTextRegion>of();
        var item =
            new SynopsisEvidence(
                entry.id(), entry.kind(), new SynopsisEvidence.Text(segment.text()), null);
        yield new Material(
            item,
            new SynopsisSourceMaterial.Page(
                segment.page(), segment.start(), segment.end(), null, regions),
            null);
      }
      case IMAGE -> {
        var source = one(evidence.findPublishedImageEvidence(scope, ids));
        if (!source.image().id().equals(entry.sourceId())) {
          throw ModelValues.notFound();
        }
        var image = new VisualImage(original.mediaType(), original.bytes());
        var item =
            new SynopsisEvidence(entry.id(), entry.kind(), new SynopsisEvidence.Image(image), null);
        yield new Material(
            item,
            new SynopsisSourceMaterial.Image(
                new ImageDimensions(source.image().width(), source.image().height())),
            null);
      }
      case AUDIO_TRANSCRIPT -> {
        var source = one(evidence.findPublishedAudioEvidence(scope, ids));
        if (!source.span().id().equals(entry.sourceId())) {
          throw ModelValues.notFound();
        }
        var span = source.span();
        var time = new SynopsisEvidence.TimeRange(span.startMs() * 1000L, span.endMs() * 1000L);
        var item =
            new SynopsisEvidence(
                entry.id(), entry.kind(), new SynopsisEvidence.Text(span.text()), time);
        yield new Material(item, new SynopsisSourceMaterial.Audio(span.ordinal(), time), null);
      }
      case VIDEO_FRAME -> {
        var frame = evidence.findVideoFrame(scope.actor(), publication, entry.sourceId());
        if (frame == null) {
          throw ModelValues.notFound();
        }
        var time =
            new SynopsisEvidence.TimeRange(
                frame.presentationUs(), frame.presentationUs() + frame.durationUs());
        var item =
            new SynopsisEvidence(
                entry.id(), entry.kind(), new SynopsisEvidence.Image(frame.image()), time);
        yield new Material(
            item,
            new SynopsisSourceMaterial.VideoFrame(
                entry.sourceId(),
                frame.ordinal(),
                time,
                new ImageDimensions(frame.width(), frame.height())),
            frame.image());
      }
      case VIDEO_TRANSCRIPT -> videoTranscript(scope, entry);
      case VIDEO_SUBTITLE -> {
        var source = subtitles.get(entry.id());
        if (source == null || !source.source().id().equals(entry.sourceId())) {
          throw ModelValues.notFound();
        }
        var cue = source.source();
        var time = new SynopsisEvidence.TimeRange(cue.startUs(), cue.endUs());
        var item =
            new SynopsisEvidence(
                entry.id(), entry.kind(), new SynopsisEvidence.Text(cue.cue().text()), time);
        yield new Material(item, new SynopsisSourceMaterial.VideoSubtitle(source), null);
      }
      case VIDEO_OCR -> {
        var source = one(evidence.findPublishedVideoOcrEvidence(scope, ids));
        if (!source.source().id().equals(entry.sourceId())) {
          throw ModelValues.notFound();
        }
        var frame = evidence.findVideoFrame(scope.actor(), publication, source.source().frameId());
        if (frame == null
            || !frame.image().sha256().equals(source.frame().frameSha256())
            || frame.presentationUs() != source.framePresentationUs()
            || frame.durationUs() != source.frameDurationUs()
            || frame.width() != source.frame().dimensions().width()
            || frame.height() != source.frame().dimensions().height()) {
          throw ModelValues.notFound();
        }
        var segment = source.source().segment();
        var time =
            new SynopsisEvidence.TimeRange(
                frame.presentationUs(), frame.presentationUs() + frame.durationUs());
        var item =
            new SynopsisEvidence(
                entry.id(), entry.kind(), new SynopsisEvidence.Text(segment.text()), time);
        var regions =
            source.frame().regions().stream()
                .filter(r -> r.start() < segment.end() && r.end() > segment.start())
                .toList();
        yield new Material(
            item,
            new SynopsisSourceMaterial.VideoOcr(
                source.source().frameId(),
                frame.ordinal(),
                time,
                source.frame().dimensions(),
                segment.start(),
                segment.end(),
                regions),
            frame.image());
      }
    };
  }

  private Material videoTranscript(EvidenceScope scope, MaterialEntry entry) {
    var publication = scope.publications().getFirst();
    var row =
        one(
            store.rows(
                """
        SELECT s.* FROM video_transcript_publication_entries e
        JOIN index_publications p ON p.id=e.publication_id
        JOIN video_transcript_spans s ON s.id=e.video_transcript_span_id AND s.revision_id=p.revision_id
        JOIN active_corpus_publications a ON a.publication_id=p.id AND a.document_id=p.document_id AND a.revision_id=p.revision_id
        JOIN documents d ON d.id=p.document_id
        WHERE p.id=? AND e.physical_segment_id=? AND s.id=? AND s.index_ordinal IS NOT NULL
          AND d.workspace_id=?
          AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
        """,
                publication.publicationId(),
                entry.id(),
                entry.sourceId(),
                scope.actor().workspaceId()));
    var span =
        new AudioTranscriptSpan(
            AuthorityRows.integer(row, "ordinal"),
            AuthorityRows.number(row, "start_ms"),
            AuthorityRows.number(row, "end_ms"),
            AuthorityRows.text(row, "text"));
    if (!span.textSha256().equals(AuthorityRows.text(row, "text_sha256"))) {
      throw ModelValues.notFound();
    }
    var source =
        new VideoTranscriptEvidence(
            entry.sourceId(),
            publication.sourceRevisionId(),
            span,
            AuthorityRows.integer(row, "index_ordinal"));
    var time = new SynopsisEvidence.TimeRange(span.startMs() * 1000L, span.endMs() * 1000L);
    var item =
        new SynopsisEvidence(
            entry.id(), entry.kind(), new SynopsisEvidence.Text(span.text()), time);
    return new Material(
        item, new SynopsisSourceMaterial.VideoTranscript(source.id(), span.ordinal(), time), null);
  }

  private static <T> T one(List<T> values) {
    if (values.size() != 1) {
      throw ModelValues.notFound();
    }
    return values.getFirst();
  }

  private static ApplicationException capacity() {
    return new ApplicationException(
        FailureKind.CAPACITY_EXCEEDED, "input_capacity_exceeded", "完整文件证据超过当前摘要限额，文件索引保持可用。");
  }

  private record MaterialEntry(String id, String sourceId, SynopsisEvidence.Kind kind) {}

  private record Original(String filename, String mediaType, byte[] bytes) {}

  private record Material(
      SynopsisEvidence evidence, SynopsisSourceMaterial.Locator locator, VisualImage frame) {}
}
