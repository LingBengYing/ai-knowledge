package com.evidence.rag.repository;

import static com.evidence.rag.repository.AuthorityRows.integer;
import static com.evidence.rag.repository.AuthorityRows.text;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AudioCompilation;
import com.evidence.rag.model.domain.AudioEvidence;
import com.evidence.rag.model.domain.AudioTranscriptSpan;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.EvidenceScope;
import com.evidence.rag.model.domain.GroundingText;
import com.evidence.rag.model.domain.ImageEvidence;
import com.evidence.rag.model.domain.ImageTextRegion;
import com.evidence.rag.model.domain.IndexSegment;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.PublishedAudioEvidence;
import com.evidence.rag.model.domain.PublishedEvidence;
import com.evidence.rag.model.domain.PublishedImageEvidence;
import com.evidence.rag.model.domain.PublishedVideoCandidate;
import com.evidence.rag.model.domain.PublishedVideoOcrEvidence;
import com.evidence.rag.model.domain.PublishedVideoSubtitleEvidence;
import com.evidence.rag.model.domain.TextPage;
import com.evidence.rag.model.domain.VideoCompilation;
import com.evidence.rag.model.domain.VideoEvidence;
import com.evidence.rag.model.domain.VideoFrame;
import com.evidence.rag.model.domain.VideoOcrSegment;
import com.evidence.rag.model.domain.VideoOcrSegmentEvidence;
import com.evidence.rag.model.domain.VideoSubtitleEvidence;
import com.evidence.rag.model.domain.VideoTraceEvidence;
import com.evidence.rag.model.domain.VideoTranscriptEvidence;
import com.evidence.rag.model.domain.VisualImage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/** SQL boundary for active publication snapshots, source hydration and append-only query traces. */
@Repository
public final class EvidenceRepository {

  public List<PublishedVideoSubtitleEvidence> findPublishedVideoSubtitleEvidence(
      EvidenceScope scope, List<String> physicalIds) {
    if (scope.publications().isEmpty() || physicalIds.isEmpty()) {
      return List.of();
    }
    var rows =
        store.rows(
            """
        SELECT p.*,d.filename,d.mime_type,e.physical_segment_id,e.entry_sha256,e.video_subtitle_cue_id
        FROM video_subtitle_publication_entries e JOIN index_publications p ON p.id=e.publication_id
        """
                + VIDEO_AUTHORIZATION
                + " AND p.id IN ("
                + placeholders(scope.publications().size())
                + ") AND e.physical_segment_id IN ("
                + placeholders(physicalIds.size())
                + ") ORDER BY e.physical_segment_id",
            sourceArguments(scope, physicalIds));
    var contexts = new HashMap<String, SubtitleContext>();
    var result = new ArrayList<PublishedVideoSubtitleEvidence>();
    for (var row : rows) {
      var publication = publication(row);
      var context =
          contexts.computeIfAbsent(
              publication.sourceRevisionId(),
              revision -> {
                var compilation =
                    IngestionRepository.readVideoCompilation(store, revision)
                        .orElseThrow(ModelValues::invalid);
                return new SubtitleContext(
                    compilation, VideoSubtitleEvidence.fromCompilation(revision, compilation));
              });
      var compilation = context.compilation();
      if (compilation.subtitles() == null
          || !compilation.sourceSha256().equals(publication.sourceSha256())
          || !compilation.compilerRevision().equals(publication.parserRevision())) {
        throw ModelValues.invalid();
      }
      String cueId = text(row, "video_subtitle_cue_id");
      var track =
          context.authority().tracks().stream()
              .filter(value -> value.cues().stream().anyMatch(cue -> cue.id().equals(cueId)))
              .findFirst()
              .orElseThrow(ModelValues::invalid);
      var cue =
          track.cues().stream()
              .filter(value -> value.id().equals(cueId))
              .findFirst()
              .orElseThrow(ModelValues::invalid);
      result.add(
          new PublishedVideoSubtitleEvidence(
              publication,
              text(row, "physical_segment_id"),
              text(row, "entry_sha256"),
              cue,
              track,
              context.authority().manifestSha256(),
              compilation.subtitles().manifestSha256(),
              compilation.decoderRevision(),
              text(row, "filename"),
              text(row, "mime_type")));
    }
    return List.copyOf(result);
  }

  private record SubtitleContext(VideoCompilation compilation, VideoSubtitleEvidence authority) {}

  public List<PublishedVideoOcrEvidence> findPublishedVideoOcrEvidence(
      EvidenceScope scope, List<String> physicalIds) {
    if (scope.publications().isEmpty() || physicalIds.isEmpty()) {
      return List.of();
    }
    var rows =
        store.rows(
            """
        SELECT p.*,d.filename,d.mime_type,e.physical_segment_id,e.entry_sha256,
          s.id AS ocr_segment_id,s.frame_id,s.ordinal AS ocr_segment_ordinal,s.start_offset,s.end_offset,s.text AS ocr_segment_text,
          f.presentation_us,f.duration_us,o.ocr_revision,o.manifest_sha256 AS ocr_manifest_sha256
        FROM video_ocr_publication_entries e JOIN index_publications p ON p.id=e.publication_id
        JOIN video_ocr_segments s ON s.id=e.video_ocr_segment_id AND s.revision_id=p.revision_id
        JOIN video_frames f ON f.id=s.frame_id AND f.revision_id=p.revision_id
        JOIN video_ocr_compilations o ON o.revision_id=p.revision_id
        """
                + VIDEO_AUTHORIZATION
                + " AND p.id IN ("
                + placeholders(scope.publications().size())
                + ") AND e.physical_segment_id IN ("
                + placeholders(physicalIds.size())
                + ") ORDER BY e.physical_segment_id",
            sourceArguments(scope, physicalIds));
    var frames = new HashMap<String, com.evidence.rag.model.domain.VideoFrameOcr>();
    return rows.stream()
        .map(
            row -> {
              var publication = publication(row);
              String frameId = text(row, "frame_id");
              var frame =
                  frames.computeIfAbsent(
                      frameId,
                      ignored ->
                          IngestionRepository.readVideoFrameOcr(
                              store, publication.sourceRevisionId(), frameId));
              var segment =
                  new VideoOcrSegment(
                      integer(row, "ocr_segment_ordinal"),
                      integer(row, "start_offset"),
                      integer(row, "end_offset"),
                      text(row, "ocr_segment_text"));
              return new PublishedVideoOcrEvidence(
                  publication,
                  text(row, "physical_segment_id"),
                  text(row, "entry_sha256"),
                  new VideoOcrSegmentEvidence(
                      text(row, "ocr_segment_id"),
                      publication.sourceRevisionId(),
                      frameId,
                      segment),
                  frame,
                  AuthorityRows.number(row, "presentation_us"),
                  AuthorityRows.number(row, "duration_us"),
                  text(row, "ocr_revision"),
                  text(row, "ocr_manifest_sha256"),
                  text(row, "filename"),
                  text(row, "mime_type"));
            })
        .toList();
  }

  public VideoFrame findVideoFrame(Actor actor, PublicationVersion publication, String frameId) {
    var rows =
        store.rows(
            "SELECT f.* FROM video_frames f JOIN index_publications p ON p.revision_id=f.revision_id "
                + VIDEO_AUTHORIZATION
                + " AND p.id=? AND p.document_id=? AND p.revision_id=? AND p.source_sha256=? AND f.id=?",
            actor.workspaceId(),
            publication.publicationId(),
            publication.documentId(),
            publication.sourceRevisionId(),
            publication.sourceSha256(),
            frameId);
    if (rows.isEmpty()) {
      return null;
    }
    var row = rows.getFirst();
    var image = new VisualImage(text(row, "media_type"), (byte[]) row.get("frame_blob"));
    if (!image.sha256().equals(text(row, "frame_sha256"))
        || !VideoEvidence.frameIdentity(publication.sourceRevisionId(), integer(row, "ordinal"))
            .equals(frameId)) {
      throw ModelValues.invalid();
    }
    return new VideoFrame(
        integer(row, "ordinal"),
        AuthorityRows.number(row, "presentation_us"),
        AuthorityRows.number(row, "duration_us"),
        image,
        integer(row, "width"),
        integer(row, "height"));
  }

  /** The authority rule for a document's current publication, shared with Wiki maintenance. */
  static final String CURRENT_PUBLICATIONS =
      """
      FROM documents d
      JOIN corpus_documents c ON c.document_id=d.id
      JOIN active_corpus_publications a ON a.document_id=d.id
      JOIN index_publications p ON p.id=a.publication_id AND p.document_id=d.id
        AND p.revision_id=a.revision_id
      JOIN corpus_revisions r ON r.id=p.revision_id AND r.document_id=d.id
      JOIN indexing_jobs j ON j.id=p.job_id AND j.state='indexed'
        AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id
      WHERE d.workspace_id=?
        AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
        AND c.parsed_revision_id=r.id AND r.parsed_at IS NOT NULL
        AND d.source_sha256=p.source_sha256 AND r.source_sha256=p.source_sha256
        AND r.parser_revision=p.parser_revision
        AND r.segment_count+(SELECT COUNT(*) FROM image_evidence i WHERE i.revision_id=r.id)
          +(SELECT COUNT(*) FROM audio_spans s WHERE s.revision_id=r.id AND s.index_ordinal IS NOT NULL)
          +(SELECT COUNT(*) FROM video_frames f WHERE f.revision_id=r.id AND f.recall_text IS NOT NULL)
          +(SELECT COUNT(*) FROM video_transcript_spans s WHERE s.revision_id=r.id AND s.index_ordinal IS NOT NULL)
          +(SELECT COUNT(*) FROM video_ocr_segments s WHERE s.revision_id=r.id)
          +(SELECT COUNT(*) FROM video_subtitle_cues s WHERE s.revision_id=r.id AND s.index_ordinal IS NOT NULL)=p.segment_count
        AND p.segment_count=(SELECT COUNT(*) FROM index_publication_entries e WHERE e.publication_id=p.id)
          +(SELECT COUNT(*) FROM image_publication_entries e WHERE e.publication_id=p.id)
          +(SELECT COUNT(*) FROM audio_publication_entries e WHERE e.publication_id=p.id)
          +(SELECT COUNT(*) FROM video_frame_publication_entries e WHERE e.publication_id=p.id)
          +(SELECT COUNT(*) FROM video_transcript_publication_entries e WHERE e.publication_id=p.id)
          +(SELECT COUNT(*) FROM video_ocr_publication_entries e WHERE e.publication_id=p.id)
          +(SELECT COUNT(*) FROM video_subtitle_publication_entries e WHERE e.publication_id=p.id)
      """;

  private static final String PUBLISHED_SOURCES =
      """
      FROM index_publication_entries e
      JOIN index_publications p ON p.id=e.publication_id
      JOIN active_corpus_publications a ON a.publication_id=p.id AND a.document_id=p.document_id AND a.revision_id=p.revision_id
      JOIN documents d ON d.id=p.document_id
      JOIN corpus_segments s ON s.id=e.source_segment_id AND s.revision_id=p.revision_id
      JOIN corpus_pages pg ON pg.revision_id=s.revision_id AND pg.page_number=s.page_number
      WHERE d.workspace_id=?
        AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
      """;
  private static final String PUBLISHED_IMAGES =
      """
      FROM image_publication_entries e
      JOIN index_publications p ON p.id=e.publication_id
      JOIN active_corpus_publications a ON a.publication_id=p.id AND a.document_id=p.document_id AND a.revision_id=p.revision_id
      JOIN documents d ON d.id=p.document_id
      JOIN image_evidence i ON i.id=e.image_evidence_id AND i.revision_id=p.revision_id
      WHERE d.workspace_id=?
        AND d.document_type='image' AND d.mime_type IN ('image/png','image/jpeg')
        AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
      """;
  private static final String PUBLISHED_AUDIO =
      """
      FROM audio_publication_entries e
      JOIN index_publications p ON p.id=e.publication_id
      JOIN active_corpus_publications a ON a.publication_id=p.id AND a.document_id=p.document_id AND a.revision_id=p.revision_id
      JOIN documents d ON d.id=p.document_id
      JOIN audio_spans s ON s.id=e.audio_span_id AND s.revision_id=p.revision_id AND s.index_ordinal IS NOT NULL
      JOIN audio_compilations h ON h.revision_id=s.revision_id AND h.source_sha256=p.source_sha256 AND h.compiler_revision=p.parser_revision
      WHERE d.workspace_id=?
        AND d.document_type='audio'
        AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
      """;
  private final SqliteAuthorityStore store;

  private static final String VIDEO_AUTHORIZATION =
      """
      JOIN active_corpus_publications a ON a.publication_id=p.id AND a.document_id=p.document_id AND a.revision_id=p.revision_id
      JOIN documents d ON d.id=p.document_id
      JOIN video_compilations h ON h.revision_id=p.revision_id AND h.source_sha256=p.source_sha256 AND h.compiler_revision=p.parser_revision
      JOIN corpus_documents c ON c.document_id=d.id AND c.parsed_revision_id=p.revision_id
      JOIN corpus_revisions r ON r.id=p.revision_id AND r.document_id=d.id AND r.parsed_at IS NOT NULL
      JOIN indexing_jobs j ON j.id=p.job_id AND j.state='indexed' AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id
      WHERE d.workspace_id=?
        AND d.document_type='video' AND d.source_sha256=p.source_sha256
        AND r.source_sha256=p.source_sha256 AND r.parser_revision=p.parser_revision
        AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
      """;
  private static final String VIDEO_GROUPS =
      """
      SELECT p.*,d.filename,d.mime_type,h.manifest_sha256 AS video_manifest_sha256,
        g.id AS group_id,g.ordinal AS group_ordinal,g.start_us,g.end_us,g.frame_id,g.transcript_span_id,
        fe.physical_segment_id AS frame_physical_id,te.physical_segment_id AS transcript_physical_id
      FROM video_evidence_groups g JOIN index_publications p ON p.revision_id=g.revision_id
      LEFT JOIN video_frame_publication_entries fe ON fe.publication_id=p.id AND fe.video_frame_id=g.frame_id
      LEFT JOIN video_transcript_publication_entries te ON te.publication_id=p.id AND te.video_transcript_span_id=g.transcript_span_id
      """
          + VIDEO_AUTHORIZATION;

  public EvidenceRepository(SqliteAuthorityStore store) {
    this.store = store;
  }

  /** Published videos with actual textual material; a caption alone is not eligible. */
  public List<PublicationVersion> findVideoTextPublications(EvidenceScope scope) {
    if (scope.publications().isEmpty()) {
      return List.of();
    }
    return store
        .rows(
            "SELECT p.* "
                + CURRENT_PUBLICATIONS
                + " AND p.id IN ("
                + placeholders(scope.publications().size())
                + ")"
                + " AND (EXISTS(SELECT 1 FROM video_transcript_publication_entries e WHERE e.publication_id=p.id)"
                + " OR EXISTS(SELECT 1 FROM video_ocr_publication_entries e WHERE e.publication_id=p.id)"
                + " OR EXISTS(SELECT 1 FROM video_subtitle_publication_entries e WHERE e.publication_id=p.id)) ORDER BY d.id",
            sourceArguments(scope, List.of()))
        .stream()
        .map(EvidenceRepository::publication)
        .toList();
  }

  /** Called only after the same-transaction publication candidate has been authorized. */
  public VideoTranscriptEvidence findVideoTranscriptSpan(
      PublicationVersion publication, String sourceId) {
    var rows =
        store.rows(
            "SELECT * FROM video_transcript_spans WHERE revision_id=? AND id=?",
            publication.sourceRevisionId(),
            sourceId);
    if (rows.size() != 1) {
      throw ModelValues.invalid();
    }
    var row = rows.getFirst();
    var span =
        new AudioTranscriptSpan(
            integer(row, "ordinal"), AuthorityRows.number(row, "start_ms"),
            AuthorityRows.number(row, "end_ms"), text(row, "text"));
    if (!span.textSha256().equals(text(row, "text_sha256"))) {
      throw ModelValues.invalid();
    }
    return new VideoTranscriptEvidence(
        text(row, "id"),
        publication.sourceRevisionId(),
        span,
        nullableInteger(row, "index_ordinal"));
  }

  public List<PublishedVideoCandidate> findPublishedVideoCandidates(
      EvidenceScope scope, List<String> physicalIds) {
    if (scope.publications().isEmpty() || physicalIds.isEmpty()) {
      return List.of();
    }
    return store
        .rows(
            """
        SELECT p.*,d.filename,d.mime_type,e.physical_segment_id,e.entry_sha256,e.source_id,e.kind,e.recall_text
        FROM (
          SELECT e.publication_id,e.physical_segment_id,e.entry_sha256,f.id AS source_id,'VISUAL' AS kind,f.recall_text
          FROM video_frame_publication_entries e JOIN video_frames f ON f.id=e.video_frame_id
          UNION ALL
          SELECT e.publication_id,e.physical_segment_id,e.entry_sha256,s.id AS source_id,'TRANSCRIPT' AS kind,s.text AS recall_text
          FROM video_transcript_publication_entries e JOIN video_transcript_spans s ON s.id=e.video_transcript_span_id
        ) e JOIN index_publications p ON p.id=e.publication_id
        """
                + VIDEO_AUTHORIZATION
                + " AND p.id IN ("
                + placeholders(scope.publications().size())
                + ") AND e.physical_segment_id IN ("
                + placeholders(physicalIds.size())
                + ") ORDER BY p.id,e.source_id",
            sourceArguments(scope, physicalIds))
        .stream()
        .map(
            row ->
                new PublishedVideoCandidate(
                    publication(row),
                    VideoTraceEvidence.Kind.valueOf(text(row, "kind")),
                    text(row, "physical_segment_id"),
                    text(row, "source_id"),
                    text(row, "entry_sha256"),
                    text(row, "recall_text"),
                    text(row, "filename"),
                    text(row, "mime_type")))
        .toList();
  }

  private static Integer nullableInteger(Map<String, Object> row, String column) {
    return row.get(column) == null ? null : integer(row, column);
  }

  public List<PublishedAudioEvidence> findPublishedAudioEvidence(
      EvidenceScope scope, List<String> physicalIds) {
    if (physicalIds.isEmpty() || scope.publications().isEmpty()) {
      return List.of();
    }
    var contexts = new HashMap<String, AudioContext>();
    var result = new ArrayList<PublishedAudioEvidence>();
    for (var row :
        store.rows(
            "SELECT p.*,d.filename,d.mime_type,e.physical_segment_id,e.entry_sha256,s.id AS audio_span_id,s.ordinal "
                + audioFilter(scope, physicalIds),
            sourceArguments(scope, physicalIds))) {
      var published = publication(row);
      var context = contexts.computeIfAbsent(published.sourceRevisionId(), this::audioContext);
      if (!published.sourceSha256().equals(context.compilation().sourceSha256())
          || !published.parserRevision().equals(context.compilation().compilerRevision())) {
        throw ModelValues.invalid();
      }
      int ordinal = integer(row, "ordinal");
      var span = context.spans().get(ordinal);
      if (!span.id().equals(text(row, "audio_span_id"))) {
        throw ModelValues.invalid();
      }
      int start = context.starts().get(ordinal);
      result.add(
          new PublishedAudioEvidence(
              published,
              text(row, "physical_segment_id"),
              text(row, "entry_sha256"),
              span,
              new GroundingText(
                  text(row, "physical_segment_id"),
                  published.publicationId() + "/audio",
                  context.transcript(),
                  context.sha256(),
                  start,
                  start + span.text().codePointCount(0, span.text().length())),
              text(row, "filename"),
              text(row, "mime_type")));
    }
    return List.copyOf(result);
  }

  public byte[] findAudioOriginal(Actor actor, PublicationVersion publication, int maximumBytes) {
    var rows =
        store.rows(
            """
        SELECT c.original_blob FROM corpus_documents c
        JOIN documents d ON d.id=c.document_id
        WHERE d.id=? AND d.workspace_id=?
          AND d.document_type='audio' AND c.initial_revision_id=? AND d.source_sha256=?
          AND LENGTH(c.original_blob) BETWEEN 1 AND ?
          AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
        """,
            publication.documentId(),
            actor.workspaceId(),
            publication.sourceRevisionId(),
            publication.sourceSha256(),
            maximumBytes);
    return rows.isEmpty() ? null : ((byte[]) rows.getFirst().get("original_blob")).clone();
  }

  private AudioContext audioContext(String revisionId) {
    var compilation =
        new IngestionRepository(store)
            .findAudioCompilation(revisionId)
            .orElseThrow(ModelValues::invalid);
    var spans = AudioEvidence.fromCompilation(revisionId, compilation);
    var starts = new ArrayList<Integer>();
    var transcript = new StringBuilder();
    int codePoints = 0;
    for (var span : spans) {
      if (span.ordinal() > 0) {
        transcript.append('\n');
        codePoints++;
      }
      starts.add(codePoints);
      transcript.append(span.text());
      codePoints += span.text().codePointCount(0, span.text().length());
    }
    String body = transcript.toString();
    return new AudioContext(
        compilation,
        spans,
        body,
        ModelValues.sha256(body.getBytes(StandardCharsets.UTF_8)),
        List.copyOf(starts));
  }

  private static String audioFilter(EvidenceScope scope, List<String> physicalIds) {
    return PUBLISHED_AUDIO
        + " AND p.id IN ("
        + placeholders(scope.publications().size())
        + ") AND e.physical_segment_id IN ("
        + placeholders(physicalIds.size())
        + ")";
  }

  private record AudioContext(
      AudioCompilation compilation,
      List<AudioEvidence> spans,
      String transcript,
      String sha256,
      List<Integer> starts) {
    @Override
    public String toString() {
      return "AudioContext[redacted]";
    }
  }

  /** Complete current organization scope; retrieval batches do not truncate the library. */
  public List<PublicationVersion> findActivePublications(Actor actor, DocumentSelection selection) {
    if (!selection.all() && selection.documentIds().isEmpty()) {
      return List.of();
    }
    var args = new ArrayList<Object>(List.of(actor.workspaceId()));
    String selected = "";
    if (!selection.all()) {
      selected = " AND d.id IN (" + placeholders(selection.documentIds().size()) + ")";
      args.addAll(selection.documentIds());
    }
    return store
        .rows("SELECT p.* " + CURRENT_PUBLICATIONS + selected + " ORDER BY d.id", args.toArray())
        .stream()
        .map(EvidenceRepository::publication)
        .toList();
  }

  /** Historical metadata is used to authenticate a revoked snapshot, never to hydrate its body. */
  public List<PublicationVersion> findHistoricalPublications(
      String workspaceId, List<String> publicationIds) {
    if (publicationIds.isEmpty()) {
      return List.of();
    }
    var args = new ArrayList<Object>();
    args.add(workspaceId);
    args.addAll(publicationIds);
    return store
        .rows(
            "SELECT p.* FROM index_publications p JOIN documents d ON d.id=p.document_id"
                + " WHERE d.workspace_id=? AND p.id IN ("
                + placeholders(publicationIds.size())
                + ")",
            args.toArray())
        .stream()
        .map(EvidenceRepository::publication)
        .toList();
  }

  /** Only narrows retrieval candidates; the caller retains the complete authority snapshot. */
  public List<PublicationVersion> findTextPublications(EvidenceScope scope) {
    if (scope.publications().isEmpty()) {
      return List.of();
    }
    var args = new ArrayList<Object>(List.of(scope.actor().workspaceId()));
    args.addAll(scope.publications().stream().map(PublicationVersion::publicationId).toList());
    return store
        .rows(
            "SELECT p.* "
                + CURRENT_PUBLICATIONS
                + " AND p.id IN ("
                + placeholders(scope.publications().size())
                + ")"
                + " AND EXISTS(SELECT 1 FROM index_publication_entries e WHERE e.publication_id=p.id) ORDER BY d.id",
            args.toArray())
        .stream()
        .map(EvidenceRepository::publication)
        .toList();
  }

  public List<PublishedImageEvidence> findPublishedImageEvidence(
      EvidenceScope scope, List<String> physicalIds) {
    if (physicalIds.isEmpty() || scope.publications().isEmpty()) {
      return List.of();
    }
    return store
        .rows(
            """
            SELECT p.*, d.filename, d.mime_type, e.physical_segment_id, e.entry_sha256,
              i.id AS image_evidence_id, i.width, i.height, i.recall_text, i.recall_sha256,
              i.description_revision
            """
                + PUBLISHED_IMAGES
                + " AND p.id IN ("
                + placeholders(scope.publications().size())
                + ")"
                + " AND e.physical_segment_id IN ("
                + placeholders(physicalIds.size())
                + ")",
            sourceArguments(scope, physicalIds))
        .stream()
        .map(
            row ->
                new PublishedImageEvidence(
                    publication(row),
                    text(row, "physical_segment_id"),
                    text(row, "entry_sha256"),
                    new ImageEvidence(
                        text(row, "image_evidence_id"),
                        text(row, "revision_id"),
                        integer(row, "width"),
                        integer(row, "height"),
                        text(row, "recall_text"),
                        text(row, "recall_sha256"),
                        text(row, "description_revision")),
                    text(row, "filename"),
                    text(row, "mime_type")))
        .toList();
  }

  /** Counts unique authority page bytes without returning page bodies to the JVM. */
  public long publishedPageBytes(EvidenceScope scope, List<String> physicalIds) {
    if (physicalIds.isEmpty() || scope.publications().isEmpty()) {
      return 0;
    }
    return store.count(
        "SELECT COALESCE(SUM(page_bytes),0) FROM ("
            + "SELECT DISTINCT p.id,pg.page_number,LENGTH(CAST(pg.text AS BLOB)) AS page_bytes "
            + sourceFilter(scope, physicalIds)
            + ")",
        sourceArguments(scope, physicalIds));
  }

  public List<PublishedEvidence> findPublishedEvidence(
      EvidenceScope scope, List<String> physicalIds) {
    if (physicalIds.isEmpty() || scope.publications().isEmpty()) {
      return List.of();
    }
    String filter = sourceFilter(scope, physicalIds);
    Object[] args = sourceArguments(scope, physicalIds);
    var segments =
        store.rows(
            """
        SELECT p.*, d.filename, e.physical_segment_id, e.entry_sha256,
          s.id AS source_segment_id,s.ordinal,s.page_number,s.start_offset,s.end_offset,
          s.text AS segment_text,s.text_sha256 AS segment_sha256,
          pg.text_sha256 AS page_sha256
        """
                + filter,
            args);
    var pages = new HashMap<PageKey, TextPage>();
    // One page body per immutable source page, even when many candidates share that page.
    for (var row :
        store.rows(
            "SELECT DISTINCT pg.revision_id,pg.page_number,pg.text AS page_text " + filter, args)) {
      pages.put(
          new PageKey(text(row, "revision_id"), integer(row, "page_number")),
          new TextPage(integer(row, "page_number"), text(row, "page_text")));
    }
    return segments.stream()
        .map(
            row ->
                new PublishedEvidence(
                    publication(row),
                    text(row, "physical_segment_id"),
                    text(row, "entry_sha256"),
                    new IndexSegment(
                        text(row, "source_segment_id"),
                        integer(row, "ordinal"),
                        integer(row, "page_number"),
                        integer(row, "start_offset"),
                        integer(row, "end_offset"),
                        text(row, "segment_text"),
                        text(row, "segment_sha256")),
                    pages.get(new PageKey(text(row, "revision_id"), integer(row, "page_number"))),
                    text(row, "page_sha256"),
                    text(row, "filename")))
        .toList();
  }

  /** Current-authorized original pinned to the citation's immutable source revision. */
  public byte[] findImageOriginal(Actor actor, PublicationVersion publication, int maximumBytes) {
    var rows =
        store.rows(
            """
            SELECT c.original_blob FROM corpus_documents c
            JOIN documents d ON d.id=c.document_id
            WHERE d.id=? AND d.workspace_id=?
              AND d.document_type='image' AND d.mime_type IN ('image/png','image/jpeg')
              AND c.initial_revision_id=? AND d.source_sha256=?
              AND LENGTH(c.original_blob) BETWEEN 1 AND ?
              AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
            """,
            publication.documentId(),
            actor.workspaceId(),
            publication.sourceRevisionId(),
            publication.sourceSha256(),
            maximumBytes);
    return rows.isEmpty() ? null : ((byte[]) rows.getFirst().get("original_blob")).clone();
  }

  public List<ImageTextRegion> findImageRegions(
      PublicationVersion publication, int start, int end) {
    return store
        .rows(
            "SELECT start_offset,end_offset,left_pixel,top_pixel,right_pixel,bottom_pixel FROM image_text_regions WHERE revision_id=? AND start_offset<? AND end_offset>? ORDER BY ordinal",
            publication.sourceRevisionId(),
            end,
            start)
        .stream()
        .map(
            row ->
                new ImageTextRegion(
                    integer(row, "start_offset"),
                    integer(row, "end_offset"),
                    integer(row, "left_pixel"),
                    integer(row, "top_pixel"),
                    integer(row, "right_pixel"),
                    integer(row, "bottom_pixel")))
        .toList();
  }

  private static String sourceFilter(EvidenceScope scope, List<String> physicalIds) {
    return PUBLISHED_SOURCES
        + " AND p.id IN ("
        + placeholders(scope.publications().size())
        + ")"
        + " AND e.physical_segment_id IN ("
        + placeholders(physicalIds.size())
        + ")";
  }

  private static Object[] sourceArguments(EvidenceScope scope, List<String> physicalIds) {
    var args = new ArrayList<Object>(List.of(scope.actor().workspaceId()));
    args.addAll(scope.publications().stream().map(PublicationVersion::publicationId).toList());
    args.addAll(physicalIds);
    return args.toArray();
  }

  private record PageKey(String revisionId, int pageNumber) {}

  private static PublicationVersion publication(Map<String, Object> row) {
    return new PublicationVersion(
        text(row, "document_id"),
        text(row, "id"),
        text(row, "revision_id"),
        text(row, "projection_generation_id"),
        text(row, "source_sha256"),
        text(row, "parser_revision"),
        new IndexTarget(
            text(row, "embedding_identity"),
            text(row, "projection_identity"),
            text(row, "model_revision"),
            integer(row, "dimensions")),
        text(row, "manifest_sha256"),
        integer(row, "segment_count"));
  }

  private static String placeholders(int count) {
    return String.join(",", Collections.nCopies(count, "?"));
  }
}
