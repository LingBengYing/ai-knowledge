package com.evidence.rag.repository;

import static com.evidence.rag.repository.AuthorityRows.integer;
import static com.evidence.rag.repository.AuthorityRows.text;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AudioCompilation;
import com.evidence.rag.model.domain.AudioEvidence;
import com.evidence.rag.model.domain.AudioTraceEvidence;
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
import com.evidence.rag.model.domain.PublishedVideoEvidence;
import com.evidence.rag.model.domain.PublishedVideoGroup;
import com.evidence.rag.model.domain.PublishedVideoOcrEvidence;
import com.evidence.rag.model.domain.PublishedVideoSubtitleEvidence;
import com.evidence.rag.model.domain.QueryTrace;
import com.evidence.rag.model.domain.TextPage;
import com.evidence.rag.model.domain.TraceDraft;
import com.evidence.rag.model.domain.TraceEvidence;
import com.evidence.rag.model.domain.VideoAssessment;
import com.evidence.rag.model.domain.VideoCompilation;
import com.evidence.rag.model.domain.VideoEvidence;
import com.evidence.rag.model.domain.VideoEvidenceGroup;
import com.evidence.rag.model.domain.VideoFrame;
import com.evidence.rag.model.domain.VideoOcrSegment;
import com.evidence.rag.model.domain.VideoOcrSegmentEvidence;
import com.evidence.rag.model.domain.VideoProofInput;
import com.evidence.rag.model.domain.VideoSubtitleEvidence;
import com.evidence.rag.model.domain.VideoTraceEvidence;
import com.evidence.rag.model.domain.VideoTraceFact;
import com.evidence.rag.model.domain.VideoTraceProof;
import com.evidence.rag.model.domain.VideoTranscriptEvidence;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.model.domain.VisualTraceEvidence;
import com.evidence.rag.model.entity.TraceAudioCitationEntity;
import com.evidence.rag.model.entity.TraceCitationEntity;
import com.evidence.rag.model.entity.TraceImageCitationEntity;
import com.evidence.rag.model.entity.TraceVideoCitationEntity;
import com.evidence.rag.model.entity.TraceVideoOcrCitationEntity;
import com.evidence.rag.model.entity.TraceVideoSubtitleCitationEntity;
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
  public void insertTrace(
      String traceId,
      EvidenceScope scope,
      TraceDraft draft,
      List<TraceCitationEntity> citations,
      List<TraceImageCitationEntity> imageCitations,
      List<TraceAudioCitationEntity> audioCitations,
      List<TraceVideoCitationEntity> videoCitations,
      List<TraceVideoOcrCitationEntity> videoOcrCitations,
      List<TraceVideoSubtitleCitationEntity> videoSubtitleCitations,
      String createdAt) {
    insertTraceAll(
        traceId,
        scope,
        draft,
        citations,
        imageCitations,
        audioCitations,
        videoCitations,
        videoOcrCitations,
        videoSubtitleCitations,
        createdAt);
  }

  public void insertTrace(
      String traceId,
      EvidenceScope scope,
      TraceDraft draft,
      List<TraceCitationEntity> citations,
      List<TraceImageCitationEntity> imageCitations,
      List<TraceAudioCitationEntity> audioCitations,
      List<TraceVideoCitationEntity> videoCitations,
      List<TraceVideoOcrCitationEntity> videoOcrCitations,
      String createdAt) {
    insertTraceAll(
        traceId,
        scope,
        draft,
        citations,
        imageCitations,
        audioCitations,
        videoCitations,
        videoOcrCitations,
        List.of(),
        createdAt);
  }

  public List<PublicationVersion> findVideoSubtitlePublications(EvidenceScope scope) {
    if (scope.publications().isEmpty()) {
      return List.of();
    }
    return store
        .rows(
            "SELECT p.* "
                + CURRENT_PUBLICATIONS
                + " AND p.id IN ("
                + placeholders(scope.publications().size())
                + ") AND EXISTS(SELECT 1 FROM video_subtitle_publication_entries e WHERE e.publication_id=p.id) ORDER BY d.id",
            sourceArguments(scope, List.of()))
        .stream()
        .map(EvidenceRepository::publication)
        .toList();
  }

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

  public TraceVideoSubtitleCitationEntity findTraceVideoSubtitleCitation(
      Actor actor, String traceId, int ordinal) {
    var rows =
        store.rows(
            "SELECT e.* FROM video_subtitle_trace_evidence e JOIN query_traces t ON t.id=e.trace_id WHERE t.id=? AND t.workspace_id=? AND t.actor_id=? AND e.citation_ordinal=? AND t.outcome='answered'",
            traceId,
            actor.workspaceId(),
            actor.principalId(),
            ordinal);
    if (rows.isEmpty()) {
      return null;
    }
    var row = rows.getFirst();
    return new TraceVideoSubtitleCitationEntity(
        new TraceEvidence(
            integer(row, "citation_ordinal"),
            text(row, "physical_segment_id"),
            integer(row, "start_code_point"),
            integer(row, "end_code_point"),
            ((Number) row.get("retrieval_score")).doubleValue(),
            ((Number) row.get("rerank_score")).doubleValue(),
            List.of(text(row, "fact_sha256").split(","))),
        text(row, "publication_id"),
        text(row, "cue_id"),
        text(row, "track_id"),
        text(row, "source_sha256"),
        text(row, "subtitle_manifest_sha256"),
        text(row, "native_manifest_sha256"),
        text(row, "track_text_sha256"),
        text(row, "payload_sha256"),
        text(row, "quote_sha256"));
  }

  public List<PublicationVersion> findVideoOcrPublications(EvidenceScope scope) {
    if (scope.publications().isEmpty()) {
      return List.of();
    }
    return store
        .rows(
            "SELECT p.* "
                + CURRENT_PUBLICATIONS
                + " AND p.id IN ("
                + placeholders(scope.publications().size())
                + ") AND EXISTS(SELECT 1 FROM video_ocr_publication_entries e WHERE e.publication_id=p.id) ORDER BY d.id",
            sourceArguments(scope, List.of()))
        .stream()
        .map(EvidenceRepository::publication)
        .toList();
  }

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

  public TraceVideoOcrCitationEntity findTraceVideoOcrCitation(
      Actor actor, String traceId, int ordinal) {
    var rows =
        store.rows(
            "SELECT e.* FROM video_ocr_trace_evidence e JOIN query_traces t ON t.id=e.trace_id WHERE t.id=? AND t.workspace_id=? AND t.actor_id=? AND e.citation_ordinal=? AND t.outcome='answered'",
            traceId,
            actor.workspaceId(),
            actor.principalId(),
            ordinal);
    if (rows.isEmpty()) {
      return null;
    }
    var row = rows.getFirst();
    return new TraceVideoOcrCitationEntity(
        new TraceEvidence(
            integer(row, "citation_ordinal"),
            text(row, "physical_segment_id"),
            integer(row, "start_code_point"),
            integer(row, "end_code_point"),
            ((Number) row.get("retrieval_score")).doubleValue(),
            ((Number) row.get("rerank_score")).doubleValue(),
            List.of(text(row, "fact_sha256").split(","))),
        text(row, "publication_id"),
        text(row, "segment_id"),
        text(row, "frame_id"),
        text(row, "source_sha256"),
        text(row, "frame_sha256"),
        text(row, "ocr_manifest_sha256"),
        text(row, "frame_text_sha256"),
        text(row, "quote_sha256"));
  }

  public VideoFrame findVideoFrame(Actor actor, PublicationVersion publication, String frameId) {
    var rows =
        store.rows(
            "SELECT f.* FROM video_frames f JOIN index_publications p ON p.revision_id=f.revision_id "
                + VIDEO_AUTHORIZATION
                + " AND p.id=? AND p.document_id=? AND p.revision_id=? AND p.source_sha256=? AND f.id=?",
            actor.workspaceId(),
            actor.principalId(),
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

  private static final String CURRENT_PUBLICATIONS =
      """
      FROM documents d
      JOIN document_acl acl ON acl.document_id=d.id
      JOIN corpus_documents c ON c.document_id=d.id
      JOIN active_corpus_publications a ON a.document_id=d.id
      JOIN index_publications p ON p.id=a.publication_id AND p.document_id=d.id
        AND p.revision_id=a.revision_id
      JOIN corpus_revisions r ON r.id=p.revision_id AND r.document_id=d.id
      JOIN indexing_jobs j ON j.id=p.job_id AND j.state='indexed'
        AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id
      WHERE d.workspace_id=? AND acl.principal_id=? AND acl.role IN ('owner','editor','reader')
        AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
        AND c.parsed_revision_id=r.id AND r.parsed_at IS NOT NULL
        AND d.source_sha256=p.source_sha256 AND r.source_sha256=p.source_sha256
        AND r.parser_revision=p.parser_revision
        AND r.segment_count+(SELECT COUNT(*) FROM image_evidence i WHERE i.revision_id=r.id)
          +(SELECT COUNT(*) FROM audio_spans s WHERE s.revision_id=r.id AND s.index_ordinal IS NOT NULL)
          +(SELECT COUNT(*) FROM video_frames f WHERE f.revision_id=r.id)
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
      JOIN document_acl acl ON acl.document_id=d.id
      JOIN corpus_segments s ON s.id=e.source_segment_id AND s.revision_id=p.revision_id
      JOIN corpus_pages pg ON pg.revision_id=s.revision_id AND pg.page_number=s.page_number
      WHERE d.workspace_id=? AND acl.principal_id=? AND acl.role IN ('owner','editor','reader')
        AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
      """;
  private static final String PUBLISHED_IMAGES =
      """
      FROM image_publication_entries e
      JOIN index_publications p ON p.id=e.publication_id
      JOIN active_corpus_publications a ON a.publication_id=p.id AND a.document_id=p.document_id AND a.revision_id=p.revision_id
      JOIN documents d ON d.id=p.document_id
      JOIN document_acl acl ON acl.document_id=d.id
      JOIN image_evidence i ON i.id=e.image_evidence_id AND i.revision_id=p.revision_id
      WHERE d.workspace_id=? AND acl.principal_id=? AND acl.role IN ('owner','editor','reader')
        AND d.document_type='image' AND d.mime_type IN ('image/png','image/jpeg')
        AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
      """;
  private static final String PUBLISHED_AUDIO =
      """
      FROM audio_publication_entries e
      JOIN index_publications p ON p.id=e.publication_id
      JOIN active_corpus_publications a ON a.publication_id=p.id AND a.document_id=p.document_id AND a.revision_id=p.revision_id
      JOIN documents d ON d.id=p.document_id
      JOIN document_acl acl ON acl.document_id=d.id
      JOIN audio_spans s ON s.id=e.audio_span_id AND s.revision_id=p.revision_id AND s.index_ordinal IS NOT NULL
      JOIN audio_compilations h ON h.revision_id=s.revision_id AND h.source_sha256=p.source_sha256 AND h.compiler_revision=p.parser_revision
      WHERE d.workspace_id=? AND acl.principal_id=? AND acl.role IN ('owner','editor','reader')
        AND d.document_type='audio'
        AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
      """;
  private final SqliteAuthorityStore store;

  private static final String VIDEO_AUTHORIZATION =
      """
      JOIN active_corpus_publications a ON a.publication_id=p.id AND a.document_id=p.document_id AND a.revision_id=p.revision_id
      JOIN documents d ON d.id=p.document_id
      JOIN document_acl acl ON acl.document_id=d.id
      JOIN video_compilations h ON h.revision_id=p.revision_id AND h.source_sha256=p.source_sha256 AND h.compiler_revision=p.parser_revision
      JOIN corpus_documents c ON c.document_id=d.id AND c.parsed_revision_id=p.revision_id
      JOIN corpus_revisions r ON r.id=p.revision_id AND r.document_id=d.id AND r.parsed_at IS NOT NULL
      JOIN indexing_jobs j ON j.id=p.job_id AND j.state='indexed' AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id
      WHERE d.workspace_id=? AND acl.principal_id=? AND acl.role IN ('owner','editor','reader')
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

  public List<PublicationVersion> findVideoPublications(EvidenceScope scope) {
    if (scope.publications().isEmpty()) {
      return List.of();
    }
    return store
        .rows(
            "SELECT p.* "
                + CURRENT_PUBLICATIONS
                + " AND p.id IN ("
                + placeholders(scope.publications().size())
                + ") AND EXISTS(SELECT 1 FROM video_frame_publication_entries e WHERE e.publication_id=p.id) ORDER BY d.id",
            sourceArguments(scope, List.of()))
        .stream()
        .map(EvidenceRepository::publication)
        .toList();
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

  public List<PublishedVideoGroup> findPublishedVideoGroups(
      EvidenceScope scope, List<String> physicalIds) {
    if (scope.publications().isEmpty() || physicalIds.isEmpty()) {
      return List.of();
    }
    var args = new ArrayList<Object>(List.of(sourceArguments(scope, physicalIds)));
    args.addAll(physicalIds);
    return store
        .rows(
            VIDEO_GROUPS
                + " AND p.id IN ("
                + placeholders(scope.publications().size())
                + ") AND (fe.physical_segment_id IN ("
                + placeholders(physicalIds.size())
                + ") OR te.physical_segment_id IN ("
                + placeholders(physicalIds.size())
                + ")) ORDER BY p.id,g.ordinal",
            args.toArray())
        .stream()
        .map(EvidenceRepository::videoGroup)
        .toList();
  }

  public PublishedVideoEvidence findPublishedVideoEvidence(
      EvidenceScope scope, String publicationId, String groupId) {
    if (scope.publications().isEmpty()) {
      return null;
    }
    var args = new ArrayList<Object>(List.of(sourceArguments(scope, List.of())));
    args.add(publicationId);
    args.add(groupId);
    var rows =
        store.rows(
            VIDEO_GROUPS
                + " AND p.id IN ("
                + placeholders(scope.publications().size())
                + ") AND p.id=? AND g.id=?",
            args.toArray());
    if (rows.isEmpty()) {
      return null;
    }
    var source = videoGroup(rows.getFirst());
    var group = source.group();
    VideoFrame frame = null;
    if (group.frameId() != null) {
      var frameRows =
          store.rows(
              "SELECT * FROM video_frames WHERE revision_id=? AND id=?",
              group.revisionId(),
              group.frameId());
      if (frameRows.size() != 1) {
        throw ModelValues.invalid();
      }
      var row = frameRows.getFirst();
      var image = new VisualImage(text(row, "media_type"), (byte[]) row.get("frame_blob"));
      if (!image.sha256().equals(text(row, "frame_sha256"))) {
        throw ModelValues.invalid();
      }
      frame =
          new VideoFrame(
              integer(row, "ordinal"),
              AuthorityRows.number(row, "presentation_us"),
              AuthorityRows.number(row, "duration_us"),
              image,
              integer(row, "width"),
              integer(row, "height"));
    }
    GroundingText transcript = null;
    VideoTranscriptEvidence selected = null;
    if (group.transcriptSpanId() != null) {
      var body = new StringBuilder();
      int codePoints = 0;
      int start = 0;
      int ordinal = 0;
      for (var row :
          store.rows(
              "SELECT * FROM video_transcript_spans WHERE revision_id=? ORDER BY ordinal",
              group.revisionId())) {
        if (integer(row, "ordinal") != ordinal++) {
          throw ModelValues.invalid();
        }
        var span =
            new AudioTranscriptSpan(
                integer(row, "ordinal"),
                AuthorityRows.number(row, "start_ms"),
                AuthorityRows.number(row, "end_ms"),
                text(row, "text"));
        if (!span.textSha256().equals(text(row, "text_sha256"))) {
          throw ModelValues.invalid();
        }
        var evidence =
            new VideoTranscriptEvidence(
                text(row, "id"), group.revisionId(), span, nullableInteger(row, "index_ordinal"));
        if (!body.isEmpty()) {
          body.append('\n');
          codePoints++;
        }
        if (evidence.id().equals(group.transcriptSpanId())) {
          selected = evidence;
          start = codePoints;
        }
        body.append(span.text());
        codePoints += span.text().codePointCount(0, span.text().length());
      }
      if (selected == null
          || ordinal
              != store.count(
                  "SELECT span_count FROM video_compilations WHERE revision_id=?",
                  group.revisionId())) {
        throw ModelValues.invalid();
      }
      if (!selected.span().text().isBlank()) {
        String context = body.toString();
        transcript =
            new GroundingText(
                selected.id(),
                "video-transcript:" + group.revisionId(),
                context,
                ModelValues.sha256(context.getBytes(StandardCharsets.UTF_8)),
                start,
                start + selected.span().text().codePointCount(0, selected.span().text().length()));
      }
    }
    return new PublishedVideoEvidence(
        source,
        new VideoProofInput(
            source.publication().sourceSha256(), source.manifestSha256(), group, frame, transcript),
        selected);
  }

  public byte[] findVideoOriginal(Actor actor, PublicationVersion publication, int maximumBytes) {
    var rows =
        store.rows(
            """
        SELECT c.original_blob FROM corpus_documents c JOIN documents d ON d.id=c.document_id
        JOIN document_acl acl ON acl.document_id=d.id
        JOIN active_corpus_publications a ON a.document_id=d.id AND a.publication_id=? AND a.revision_id=?
        WHERE d.id=? AND d.workspace_id=? AND acl.principal_id=? AND acl.role IN ('owner','editor','reader')
          AND d.document_type='video' AND c.initial_revision_id=? AND d.source_sha256=?
          AND LENGTH(c.original_blob) BETWEEN 1 AND ?
          AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
        """,
            publication.publicationId(),
            publication.sourceRevisionId(),
            publication.documentId(),
            actor.workspaceId(),
            actor.principalId(),
            publication.sourceRevisionId(),
            publication.sourceSha256(),
            maximumBytes);
    return rows.isEmpty() ? null : ((byte[]) rows.getFirst().get("original_blob")).clone();
  }

  public TraceVideoCitationEntity findTraceVideoCitation(
      Actor actor, String traceId, int citationOrdinal) {
    var rows =
        store.rows(
            """
        SELECT e.* FROM video_trace_evidence e JOIN query_traces t ON t.id=e.trace_id
        WHERE t.id=? AND t.workspace_id=? AND t.actor_id=? AND t.outcome='answered' AND e.citation_ordinal=?
        """,
            traceId,
            actor.workspaceId(),
            actor.principalId(),
            citationOrdinal);
    if (rows.isEmpty()) {
      return null;
    }
    var row = rows.getFirst();
    return new TraceVideoCitationEntity(
        new VideoTraceEvidence(
            integer(row, "citation_ordinal"),
            VideoTraceEvidence.Kind.valueOf(text(row, "kind")),
            text(row, "physical_segment_id"),
            nullableInteger(row, "start_code_point"),
            nullableInteger(row, "end_code_point"),
            ((Number) row.get("retrieval_score")).doubleValue(),
            ((Number) row.get("rerank_score")).doubleValue(),
            text(row, "fact_sha256")),
        text(row, "publication_id"),
        text(row, "group_id"),
        text(row, "frame_id"),
        text(row, "transcript_span_id"),
        text(row, "source_sha256"),
        text(row, "manifest_sha256"),
        text(row, "frame_sha256"),
        text(row, "span_text_sha256"),
        text(row, "quote_sha256"));
  }

  public VideoTraceProof findTraceVideoProof(Actor actor, String traceId) {
    var rows =
        store.rows(
            """
        SELECT p.* FROM video_trace_proofs p JOIN query_traces t ON t.id=p.trace_id
        WHERE t.id=? AND t.workspace_id=? AND t.actor_id=? AND t.outcome='answered'
        """,
            traceId,
            actor.workspaceId(),
            actor.principalId());
    if (rows.isEmpty()) {
      return null;
    }
    var row = rows.getFirst();
    var facts =
        store
            .rows("SELECT * FROM video_trace_facts WHERE trace_id=? ORDER BY ordinal", traceId)
            .stream()
            .map(
                f ->
                    new VideoTraceFact(
                        integer(f, "ordinal"),
                        text(f, "fact_sha256"),
                        integer(f, "visual_support"),
                        integer(f, "transcript_support")))
            .toList();
    return new VideoTraceProof(
        text(row, "publication_id"),
        text(row, "group_id"),
        VideoAssessment.Mode.valueOf(text(row, "mode")),
        facts,
        text(row, "text_model_revision"),
        text(row, "vision_model_revision"),
        text(row, "policy_revision"));
  }

  public void insertTrace(
      String traceId,
      EvidenceScope scope,
      TraceDraft draft,
      List<TraceCitationEntity> citations,
      List<TraceImageCitationEntity> imageCitations,
      List<TraceAudioCitationEntity> audioCitations,
      List<TraceVideoCitationEntity> videoCitations,
      String createdAt) {
    insertTraceAll(
        traceId,
        scope,
        draft,
        citations,
        imageCitations,
        audioCitations,
        videoCitations,
        List.of(),
        List.of(),
        createdAt);
  }

  private static Integer nullableInteger(Map<String, Object> row, String column) {
    return row.get(column) == null ? null : integer(row, column);
  }

  private static PublishedVideoGroup videoGroup(Map<String, Object> row) {
    var publication = publication(row);
    return new PublishedVideoGroup(
        publication,
        new VideoEvidenceGroup(
            text(row, "group_id"),
            publication.sourceRevisionId(),
            integer(row, "group_ordinal"),
            AuthorityRows.number(row, "start_us"),
            AuthorityRows.number(row, "end_us"),
            text(row, "frame_id"),
            text(row, "transcript_span_id")),
        text(row, "frame_physical_id"),
        text(row, "transcript_physical_id"),
        text(row, "video_manifest_sha256"),
        text(row, "filename"),
        text(row, "mime_type"));
  }

  public List<PublicationVersion> findAudioPublications(EvidenceScope scope) {
    if (scope.publications().isEmpty()) {
      return List.of();
    }
    var args =
        new ArrayList<Object>(List.of(scope.actor().workspaceId(), scope.actor().principalId()));
    args.addAll(scope.publications().stream().map(PublicationVersion::publicationId).toList());
    return store
        .rows(
            "SELECT p.* "
                + CURRENT_PUBLICATIONS
                + " AND p.id IN ("
                + placeholders(scope.publications().size())
                + ") AND EXISTS(SELECT 1 FROM audio_publication_entries e WHERE e.publication_id=p.id) ORDER BY d.id",
            args.toArray())
        .stream()
        .map(EvidenceRepository::publication)
        .toList();
  }

  public long publishedAudioTranscriptBytes(EvidenceScope scope, List<String> physicalIds) {
    if (physicalIds.isEmpty() || scope.publications().isEmpty()) {
      return 0;
    }
    return store.count(
        "SELECT COALESCE(SUM(transcript_bytes),0) FROM (SELECT DISTINCT p.revision_id,"
            + " (SELECT SUM(LENGTH(CAST(all_spans.text AS BLOB)))+COUNT(*)-1 FROM audio_spans all_spans WHERE all_spans.revision_id=p.revision_id) AS transcript_bytes "
            + audioFilter(scope, physicalIds)
            + ")",
        sourceArguments(scope, physicalIds));
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
        JOIN document_acl acl ON acl.document_id=d.id
        WHERE d.id=? AND d.workspace_id=? AND acl.principal_id=? AND acl.role IN ('owner','editor','reader')
          AND d.document_type='audio' AND c.initial_revision_id=? AND d.source_sha256=?
          AND LENGTH(c.original_blob) BETWEEN 1 AND ?
          AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
        """,
            publication.documentId(),
            actor.workspaceId(),
            actor.principalId(),
            publication.sourceRevisionId(),
            publication.sourceSha256(),
            maximumBytes);
    return rows.isEmpty() ? null : ((byte[]) rows.getFirst().get("original_blob")).clone();
  }

  public TraceAudioCitationEntity findTraceAudioCitation(
      Actor actor, String traceId, int citationOrdinal) {
    var rows =
        store.rows(
            """
        SELECT e.* FROM audio_trace_evidence e JOIN query_traces t ON t.id=e.trace_id
        WHERE t.id=? AND t.workspace_id=? AND t.actor_id=? AND t.outcome='answered' AND e.citation_ordinal=?
        """,
            traceId,
            actor.workspaceId(),
            actor.principalId(),
            citationOrdinal);
    if (rows.isEmpty()) {
      return null;
    }
    var row = rows.getFirst();
    return new TraceAudioCitationEntity(
        new AudioTraceEvidence(
            integer(row, "citation_ordinal"),
            text(row, "physical_segment_id"),
            integer(row, "start_code_point"),
            integer(row, "end_code_point"),
            ((Number) row.get("retrieval_score")).doubleValue(),
            ((Number) row.get("rerank_score")).doubleValue(),
            List.of(text(row, "fact_sha256").split(","))),
        text(row, "publication_id"),
        text(row, "audio_span_id"),
        AuthorityRows.number(row, "start_ms"),
        AuthorityRows.number(row, "end_ms"),
        text(row, "source_sha256"),
        text(row, "text_sha256"),
        text(row, "transcript_sha256"),
        text(row, "quote_sha256"));
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

  /** ACL filtering precedes the 129th-row capacity sentinel; no silent full-library truncation. */
  public List<PublicationVersion> findActivePublications(Actor actor, DocumentSelection selection) {
    if (!selection.all() && selection.documentIds().isEmpty()) {
      return List.of();
    }
    var args = new ArrayList<Object>(List.of(actor.workspaceId(), actor.principalId()));
    String selected = "";
    if (!selection.all()) {
      selected = " AND d.id IN (" + placeholders(selection.documentIds().size()) + ")";
      args.addAll(selection.documentIds());
    }
    return store
        .rows(
            "SELECT p.* " + CURRENT_PUBLICATIONS + selected + " ORDER BY d.id LIMIT 129",
            args.toArray())
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
    var args =
        new ArrayList<Object>(List.of(scope.actor().workspaceId(), scope.actor().principalId()));
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

  /** Only narrows retrieval candidates; the caller retains the complete authority snapshot. */
  public List<PublicationVersion> findImagePublications(EvidenceScope scope) {
    if (scope.publications().isEmpty()) {
      return List.of();
    }
    var args =
        new ArrayList<Object>(List.of(scope.actor().workspaceId(), scope.actor().principalId()));
    args.addAll(scope.publications().stream().map(PublicationVersion::publicationId).toList());
    return store
        .rows(
            "SELECT p.* "
                + CURRENT_PUBLICATIONS
                + " AND p.id IN ("
                + placeholders(scope.publications().size())
                + ")"
                + " AND EXISTS(SELECT 1 FROM image_publication_entries e WHERE e.publication_id=p.id) ORDER BY d.id",
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
            JOIN document_acl acl ON acl.document_id=d.id
            WHERE d.id=? AND d.workspace_id=? AND acl.principal_id=?
              AND acl.role IN ('owner','editor','reader')
              AND d.document_type='image' AND d.mime_type IN ('image/png','image/jpeg')
              AND c.initial_revision_id=? AND d.source_sha256=?
              AND LENGTH(c.original_blob) BETWEEN 1 AND ?
              AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
            """,
            publication.documentId(),
            actor.workspaceId(),
            actor.principalId(),
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
    var args =
        new ArrayList<Object>(List.of(scope.actor().workspaceId(), scope.actor().principalId()));
    args.addAll(scope.publications().stream().map(PublicationVersion::publicationId).toList());
    args.addAll(physicalIds);
    return args.toArray();
  }

  private record PageKey(String revisionId, int pageNumber) {}

  public void insertTrace(
      String traceId,
      EvidenceScope scope,
      TraceDraft draft,
      List<TraceCitationEntity> citations,
      List<TraceImageCitationEntity> imageCitations,
      List<TraceAudioCitationEntity> audioCitations,
      String createdAt) {
    insertTraceAll(
        traceId,
        scope,
        draft,
        citations,
        imageCitations,
        audioCitations,
        List.of(),
        List.of(),
        List.of(),
        createdAt);
  }

  private void insertTraceAll(
      String traceId,
      EvidenceScope scope,
      TraceDraft draft,
      List<TraceCitationEntity> citations,
      List<TraceImageCitationEntity> imageCitations,
      List<TraceAudioCitationEntity> audioCitations,
      List<TraceVideoCitationEntity> videoCitations,
      List<TraceVideoOcrCitationEntity> videoOcrCitations,
      List<TraceVideoSubtitleCitationEntity> videoSubtitleCitations,
      String createdAt) {
    if (!videoOcrCitations.stream()
        .map(TraceVideoOcrCitationEntity::evidence)
        .toList()
        .equals(draft.videoOcrEvidence())) {
      throw ModelValues.invalid();
    }
    if (!videoSubtitleCitations.stream()
        .map(TraceVideoSubtitleCitationEntity::evidence)
        .toList()
        .equals(draft.videoSubtitleEvidence())) {
      throw ModelValues.invalid();
    }
    for (int index = 0; index < scope.publications().size(); index++) {
      store.execute(
          "INSERT INTO query_trace_documents(trace_id,ordinal,publication_id) VALUES(?,?,?)",
          traceId,
          index,
          scope.publications().get(index).publicationId());
    }
    for (var citation : citations) {
      var locator = citation.evidence();
      store.execute(
          """
          INSERT INTO query_trace_evidence(trace_id,citation_ordinal,publication_id,source_segment_id,
            physical_segment_id,page_number,start_offset,end_offset,text_sha256,page_sha256,quote_sha256,
            retrieval_score,rerank_score,fact_sha256) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
          """,
          traceId,
          locator.citationOrdinal(),
          citation.publicationId(),
          citation.sourceSegmentId(),
          locator.physicalSegmentId(),
          citation.page(),
          locator.start(),
          locator.end(),
          citation.textSha256(),
          citation.pageSha256(),
          citation.quoteSha256(),
          locator.retrievalScore(),
          locator.rerankScore(),
          String.join(",", locator.factSha256()));
    }
    for (var citation : imageCitations) {
      var locator = citation.evidence();
      store.execute(
          """
          INSERT INTO image_trace_evidence(trace_id,citation_ordinal,publication_id,image_evidence_id,
            physical_segment_id,source_sha256,retrieval_score,rerank_score,fact_sha256,
            visual_model_revision,visual_policy_revision) VALUES(?,?,?,?,?,?,?,?,?,?,?)
          """,
          traceId,
          locator.citationOrdinal(),
          citation.publicationId(),
          citation.imageEvidenceId(),
          locator.physicalSegmentId(),
          citation.sourceSha256(),
          locator.retrievalScore(),
          locator.rerankScore(),
          String.join(",", locator.factSha256()),
          locator.visualModelRevision(),
          locator.visualPolicyRevision());
    }
    for (var citation : audioCitations) {
      var locator = citation.evidence();
      store.execute(
          """
          INSERT INTO audio_trace_evidence(trace_id,citation_ordinal,publication_id,audio_span_id,
            physical_segment_id,start_code_point,end_code_point,start_ms,end_ms,source_sha256,
            text_sha256,transcript_sha256,quote_sha256,retrieval_score,rerank_score,fact_sha256)
          VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
          """,
          traceId,
          locator.citationOrdinal(),
          citation.publicationId(),
          citation.audioSpanId(),
          locator.physicalSegmentId(),
          locator.startCodePoint(),
          locator.endCodePoint(),
          citation.startMs(),
          citation.endMs(),
          citation.sourceSha256(),
          citation.textSha256(),
          citation.transcriptSha256(),
          citation.quoteSha256(),
          locator.retrievalScore(),
          locator.rerankScore(),
          String.join(",", locator.factSha256()));
    }
    insertVideoTrace(traceId, draft, videoCitations);
    for (var citation : videoOcrCitations) {
      var locator = citation.evidence();
      store.execute(
          "INSERT INTO video_ocr_trace_evidence(trace_id,citation_ordinal,publication_id,segment_id,frame_id,physical_segment_id,start_code_point,end_code_point,source_sha256,frame_sha256,ocr_manifest_sha256,frame_text_sha256,quote_sha256,retrieval_score,rerank_score,fact_sha256) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          traceId,
          locator.citationOrdinal(),
          citation.publicationId(),
          citation.segmentId(),
          citation.frameId(),
          locator.physicalSegmentId(),
          locator.start(),
          locator.end(),
          citation.sourceSha256(),
          citation.frameSha256(),
          citation.ocrManifestSha256(),
          citation.frameTextSha256(),
          citation.quoteSha256(),
          locator.retrievalScore(),
          locator.rerankScore(),
          String.join(",", locator.factSha256()));
    }
    for (var citation : videoSubtitleCitations) {
      var locator = citation.evidence();
      store.execute(
          "INSERT INTO video_subtitle_trace_evidence(trace_id,citation_ordinal,publication_id,cue_id,track_id,physical_segment_id,start_code_point,end_code_point,source_sha256,subtitle_manifest_sha256,native_manifest_sha256,track_text_sha256,payload_sha256,quote_sha256,retrieval_score,rerank_score,fact_sha256) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          traceId,
          locator.citationOrdinal(),
          citation.publicationId(),
          citation.cueId(),
          citation.trackId(),
          locator.physicalSegmentId(),
          locator.start(),
          locator.end(),
          citation.sourceSha256(),
          citation.subtitleManifestSha256(),
          citation.nativeManifestSha256(),
          citation.trackTextSha256(),
          citation.payloadSha256(),
          citation.quoteSha256(),
          locator.retrievalScore(),
          locator.rerankScore(),
          String.join(",", locator.factSha256()));
    }
    insertQueryTrace(traceId, draft.queryTrace());
    // Header seals all children. Deferred foreign keys make partial traces impossible to commit.
    store.execute(
        """
        INSERT INTO query_traces(id,workspace_id,actor_id,selection_all,scope_count,citation_count,
          question_sha256,answer_sha256,outcome,reason_code,model_revision,prompt_revision,policy_revision,created_at)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        traceId,
        scope.actor().workspaceId(),
        scope.actor().principalId(),
        scope.selection().all() ? 1 : 0,
        scope.publications().size(),
        citations.size()
            + imageCitations.size()
            + audioCitations.size()
            + videoCitations.size()
            + videoOcrCitations.size()
            + videoSubtitleCitations.size(),
        draft.questionSha256(),
        draft.answerSha256(),
        draft.outcome(),
        draft.reasonCode(),
        draft.modelRevision(),
        draft.promptRevision(),
        draft.policyRevision(),
        createdAt);
  }

  private void insertQueryTrace(String traceId, QueryTrace trace) {
    if (trace == null) {
      return;
    }
    store.execute(
        "INSERT INTO query_trace_preparations(trace_id,question_sha256,preparation_revision,ranking_revision,status,reason_code,manifest_sha256,attachment_count) VALUES(?,?,?,?,?,?,?,?)",
        traceId,
        trace.questionSha256(),
        trace.preparationRevision(),
        trace.rankingRevision(),
        trace.status(),
        trace.reasonCode(),
        trace.manifestSha256(),
        trace.attachments().size());
    for (var attachment : trace.attachments()) {
      var manifest = attachment.manifest();
      store.execute(
          "INSERT INTO query_trace_attachments(trace_id,ordinal,source_sha256,media_kind,compiler_revision,content_sha256,text_code_points,visual_count,selected_image_count,visual_sampled) VALUES(?,?,?,?,?,?,?,?,?,?)",
          traceId,
          attachment.ordinal(),
          attachment.sourceSha256(),
          attachment.mediaKind().name(),
          manifest == null ? null : manifest.compilerRevision(),
          manifest == null ? null : manifest.contentSha256(),
          manifest == null ? null : manifest.textCodePoints(),
          manifest == null ? null : manifest.visualCount(),
          manifest == null ? 0 : manifest.selectedImageSha256().size(),
          manifest == null ? null : manifest.visualSampled() ? 1 : 0);
      if (manifest != null) {
        for (int ordinal = 0; ordinal < manifest.selectedImageSha256().size(); ordinal++) {
          store.execute(
              "INSERT INTO query_trace_attachment_images(trace_id,attachment_ordinal,ordinal,image_sha256) VALUES(?,?,?,?)",
              traceId,
              attachment.ordinal(),
              ordinal,
              manifest.selectedImageSha256().get(ordinal));
        }
      }
    }
  }

  private void insertVideoTrace(
      String traceId, TraceDraft draft, List<TraceVideoCitationEntity> citations) {
    var proof = draft.videoProof();
    if (!citations.stream()
        .map(TraceVideoCitationEntity::evidence)
        .toList()
        .equals(draft.videoEvidence())) {
      throw ModelValues.invalid();
    }
    if (proof == null) {
      return;
    }
    store.execute(
        "INSERT INTO video_trace_proofs(trace_id,publication_id,group_id,mode,fact_count,text_model_revision,vision_model_revision,policy_revision) VALUES(?,?,?,?,?,?,?,?)",
        traceId,
        proof.publicationId(),
        proof.groupId(),
        proof.mode().name(),
        proof.facts().size(),
        proof.textModelRevision(),
        proof.visionModelRevision(),
        proof.policyRevision());
    for (var fact : proof.facts()) {
      store.execute(
          "INSERT INTO video_trace_facts(trace_id,ordinal,fact_sha256,visual_support,transcript_support) VALUES(?,?,?,?,?)",
          traceId,
          fact.ordinal(),
          fact.factSha256(),
          fact.visualSupport(),
          fact.transcriptSupport());
    }
    for (var citation : citations) {
      var locator = citation.evidence();
      store.execute(
          """
          INSERT INTO video_trace_evidence(trace_id,citation_ordinal,publication_id,group_id,kind,frame_id,transcript_span_id,physical_segment_id,start_code_point,end_code_point,source_sha256,manifest_sha256,frame_sha256,span_text_sha256,quote_sha256,retrieval_score,rerank_score,fact_sha256)
          VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
          """,
          traceId,
          locator.citationOrdinal(),
          citation.publicationId(),
          citation.groupId(),
          locator.kind().name(),
          citation.frameId(),
          citation.transcriptSpanId(),
          locator.physicalSegmentId(),
          locator.startCodePoint(),
          locator.endCodePoint(),
          citation.sourceSha256(),
          citation.manifestSha256(),
          citation.frameSha256(),
          citation.spanTextSha256(),
          citation.quoteSha256(),
          locator.retrievalScore(),
          locator.rerankScore(),
          locator.factSha256());
    }
  }

  /**
   * Only the trace's original actor may recover its frozen metadata. Current ACL is checked by
   * Service.
   */
  public EvidenceScope findTraceScope(Actor actor, String traceId) {
    var headers =
        store.rows(
            "SELECT selection_all FROM query_traces WHERE id=? AND workspace_id=? AND actor_id=? AND outcome='answered'",
            traceId,
            actor.workspaceId(),
            actor.principalId());
    if (headers.isEmpty()) {
      return null;
    }
    var publications =
        store
            .rows(
                "SELECT p.* FROM query_trace_documents q JOIN index_publications p ON p.id=q.publication_id WHERE q.trace_id=? ORDER BY q.ordinal",
                traceId)
            .stream()
            .map(EvidenceRepository::publication)
            .toList();
    var selection =
        integer(headers.getFirst(), "selection_all") == 1
            ? DocumentSelection.allDocuments()
            : DocumentSelection.selected(
                publications.stream().map(PublicationVersion::documentId).toList());
    return new EvidenceScope(actor, selection, publications);
  }

  public TraceCitationEntity findTraceCitation(Actor actor, String traceId, int citationOrdinal) {
    var rows =
        store.rows(
            """
        SELECT e.* FROM query_trace_evidence e JOIN query_traces t ON t.id=e.trace_id
        WHERE t.id=? AND t.workspace_id=? AND t.actor_id=? AND t.outcome='answered' AND e.citation_ordinal=?
        """,
            traceId,
            actor.workspaceId(),
            actor.principalId(),
            citationOrdinal);
    if (rows.isEmpty()) {
      return null;
    }
    var row = rows.getFirst();
    return new TraceCitationEntity(
        new TraceEvidence(
            integer(row, "citation_ordinal"),
            text(row, "physical_segment_id"),
            integer(row, "start_offset"),
            integer(row, "end_offset"),
            ((Number) row.get("retrieval_score")).doubleValue(),
            ((Number) row.get("rerank_score")).doubleValue(),
            List.of(text(row, "fact_sha256").split(","))),
        text(row, "publication_id"),
        text(row, "source_segment_id"),
        integer(row, "page_number"),
        text(row, "text_sha256"),
        text(row, "page_sha256"),
        text(row, "quote_sha256"));
  }

  public TraceImageCitationEntity findTraceImageCitation(
      Actor actor, String traceId, int citationOrdinal) {
    var rows =
        store.rows(
            """
        SELECT e.* FROM image_trace_evidence e JOIN query_traces t ON t.id=e.trace_id
        WHERE t.id=? AND t.workspace_id=? AND t.actor_id=? AND t.outcome='answered' AND e.citation_ordinal=?
        """,
            traceId,
            actor.workspaceId(),
            actor.principalId(),
            citationOrdinal);
    if (rows.isEmpty()) {
      return null;
    }
    var row = rows.getFirst();
    return new TraceImageCitationEntity(
        new VisualTraceEvidence(
            integer(row, "citation_ordinal"),
            text(row, "physical_segment_id"),
            ((Number) row.get("retrieval_score")).doubleValue(),
            ((Number) row.get("rerank_score")).doubleValue(),
            List.of(text(row, "fact_sha256").split(",")),
            text(row, "visual_model_revision"),
            text(row, "visual_policy_revision")),
        text(row, "publication_id"),
        text(row, "image_evidence_id"),
        text(row, "source_sha256"));
  }

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
