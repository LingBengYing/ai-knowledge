package com.evidence.rag.repository;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AudioEvidence;
import com.evidence.rag.model.domain.AudioTranscriptSpan;
import com.evidence.rag.model.domain.ImageEvidence;
import com.evidence.rag.model.domain.ImageRecall;
import com.evidence.rag.model.domain.IndexSegment;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.ProjectionItem;
import com.evidence.rag.model.domain.VideoEvidence;
import com.evidence.rag.model.domain.VideoOcrSegment;
import com.evidence.rag.model.domain.VideoOcrSegmentEvidence;
import com.evidence.rag.model.domain.VideoTranscriptEvidence;
import com.evidence.rag.model.entity.IndexPublicationEntity;
import com.evidence.rag.model.entity.RevisionEntity;
import com.evidence.rag.model.entity.TaskEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** SQL for index attempts, immutable publication entries and active pointers. */
public final class IndexingRepository {
  private static final String PROJECTION_COUNT =
      "(r.segment_count+(SELECT COUNT(*) FROM image_evidence i WHERE i.revision_id=r.id)"
          + "+(SELECT COUNT(*) FROM audio_spans s WHERE s.revision_id=r.id AND s.index_ordinal IS NOT NULL)"
          + "+(SELECT COUNT(*) FROM video_frames f WHERE f.revision_id=r.id)"
          + "+(SELECT COUNT(*) FROM video_transcript_spans s WHERE s.revision_id=r.id AND s.index_ordinal IS NOT NULL)"
          + "+(SELECT COUNT(*) FROM video_ocr_segments s WHERE s.revision_id=r.id)"
          + "+(SELECT COUNT(*) FROM video_subtitle_cues s WHERE s.revision_id=r.id AND s.index_ordinal IS NOT NULL))";
  private final SqliteAuthorityStore store;

  public IndexingRepository(SqliteAuthorityStore store) {
    this.store = Objects.requireNonNull(store);
  }

  public Optional<RevisionEntity> parsedRevision(String documentId) {
    return store
        .rows(
            "SELECT r.* FROM corpus_documents c JOIN corpus_revisions r ON r.id=c.parsed_revision_id AND r.document_id=c.document_id JOIN ingestion_jobs p ON p.document_id=c.document_id AND p.revision_id=r.id WHERE c.document_id=? AND r.parsed_at IS NOT NULL AND "
                + PROJECTION_COUNT
                + " BETWEEN 1 AND 4096 AND p.state='parsed' AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=c.document_id)",
            documentId)
        .stream()
        .findFirst()
        .map(
            row ->
                new RevisionEntity(
                    AuthorityRows.text(row, "id"),
                    AuthorityRows.text(row, "source_sha256"),
                    AuthorityRows.text(row, "parser_revision"),
                    AuthorityRows.integer(row, "segment_count")));
  }

  public boolean jobExists(String documentId) {
    return store.count("SELECT COUNT(*) FROM indexing_jobs WHERE document_id=?", documentId) != 0;
  }

  public boolean activePublicationExists(String documentId) {
    return store.count(
            "SELECT COUNT(*) FROM active_corpus_publications WHERE document_id=?", documentId)
        != 0;
  }

  public void insertJob(
      String jobId,
      String documentId,
      RevisionEntity revision,
      IndexTarget target,
      String creator,
      String now) {
    store.execute(
        "INSERT INTO indexing_jobs(id,document_id,revision_id,source_sha256,parser_revision,embedding_identity,projection_identity,model_revision,dimensions,state,attempt,created_by,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,'queued',1,?,?,?)",
        jobId,
        documentId,
        revision.id(),
        revision.sourceSha256(),
        revision.parserRevision(),
        target.embeddingIdentity(),
        target.projectionIdentity(),
        target.modelRevision(),
        target.dimensions(),
        creator,
        now,
        now);
  }

  public boolean hasProcessing() {
    return store.count(
            "SELECT COUNT(*) FROM indexing_jobs j WHERE state='processing' AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=j.document_id)")
        != 0;
  }

  public List<String> queuedIds(String workspaceId) {
    return store
        .rows(
            "SELECT j.id FROM indexing_jobs j JOIN documents d ON d.id=j.document_id WHERE d.workspace_id=? AND j.state='queued' AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id) ORDER BY j.created_at,j.id",
            workspaceId)
        .stream()
        .map(row -> AuthorityRows.text(row, "id"))
        .toList();
  }

  public Optional<TaskEntity> findInternalTask(String jobId) {
    return store
        .rows(
            "SELECT j.*,d.workspace_id,d.filename FROM indexing_jobs j JOIN documents d ON d.id=j.document_id WHERE j.id=?",
            jobId)
        .stream()
        .findFirst()
        .map(row -> AuthorityRows.task(row, true));
  }

  public Optional<TaskEntity> findAuthorizedTask(Actor actor, String jobId, boolean edit) {
    return store
        .rows(
            "SELECT j.*,d.filename,acl.role AS current_role FROM indexing_jobs j JOIN documents d ON d.id=j.document_id JOIN document_acl acl ON acl.document_id=d.id WHERE j.id=? AND d.workspace_id=? AND acl.principal_id=? AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)"
                + (edit ? " AND acl.role IN ('owner','editor')" : ""),
            jobId,
            actor.workspaceId(),
            actor.principalId())
        .stream()
        .findFirst()
        .map(row -> AuthorityRows.task(row, true));
  }

  public boolean sourceCurrent(TaskEntity job) {
    return store.count(
            "SELECT COUNT(*) FROM corpus_documents c JOIN corpus_revisions r ON r.document_id=c.document_id AND r.id=c.parsed_revision_id JOIN documents d ON d.id=c.document_id JOIN ingestion_jobs p ON p.document_id=d.id AND p.revision_id=r.id WHERE c.document_id=? AND r.id=? AND r.source_sha256=? AND d.source_sha256=r.source_sha256 AND r.parser_revision=? AND r.parsed_at IS NOT NULL AND p.state='parsed' AND "
                + PROJECTION_COUNT
                + " BETWEEN 1 AND 4096 AND r.segment_count=(SELECT COUNT(*) FROM corpus_segments s WHERE s.revision_id=r.id) AND NOT EXISTS(SELECT 1 FROM active_corpus_publications a WHERE a.document_id=d.id) AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)",
            job.documentId(),
            job.revisionId(),
            job.sourceSha256(),
            job.parserRevision())
        == 1;
  }

  /** Complete recall snapshot; text locations are validated before leaving their source type. */
  public List<ProjectionItem> projectionItems(String revisionId) {
    var items = new ArrayList<ProjectionItem>();
    for (var segment : segments(revisionId)) {
      items.add(ProjectionItem.fromText(segment));
    }
    for (var row :
        store.rows("SELECT * FROM image_evidence WHERE revision_id=? ORDER BY id", revisionId)) {
      var image =
          new ImageEvidence(
              AuthorityRows.text(row, "id"),
              AuthorityRows.text(row, "revision_id"),
              AuthorityRows.integer(row, "width"),
              AuthorityRows.integer(row, "height"),
              AuthorityRows.text(row, "recall_text"),
              AuthorityRows.text(row, "recall_sha256"),
              AuthorityRows.text(row, "description_revision"));
      items.add(
          new ProjectionItem(image.id(), items.size(), image.recallText(), image.recallSha256()));
    }
    for (var row :
        store.rows(
            "SELECT * FROM audio_spans WHERE revision_id=? AND index_ordinal IS NOT NULL ORDER BY index_ordinal",
            revisionId)) {
      var audio =
          new AudioEvidence(
              AuthorityRows.text(row, "id"),
              AuthorityRows.text(row, "revision_id"),
              AuthorityRows.integer(row, "ordinal"),
              AuthorityRows.integer(row, "start_ms"),
              AuthorityRows.integer(row, "end_ms"),
              AuthorityRows.text(row, "text"),
              AuthorityRows.text(row, "text_sha256"),
              AuthorityRows.integer(row, "index_ordinal"));
      items.add(new ProjectionItem(audio.id(), items.size(), audio.text(), audio.textSha256()));
    }
    // Recall does not need the sealed original frame bytes; those remain in video authority.
    for (var row :
        store.rows(
            "SELECT id,revision_id,ordinal,recall_text,recall_sha256,description_revision FROM video_frames WHERE revision_id=? ORDER BY ordinal",
            revisionId)) {
      String id = AuthorityRows.text(row, "id");
      if (!VideoEvidence.frameIdentity(
              AuthorityRows.text(row, "revision_id"), AuthorityRows.integer(row, "ordinal"))
          .equals(id)) {
        throw ModelValues.invalid();
      }
      var recall =
          new ImageRecall(
              AuthorityRows.text(row, "recall_text"),
              AuthorityRows.text(row, "description_revision"));
      items.add(
          new ProjectionItem(
              id, items.size(), recall.recallText(), AuthorityRows.text(row, "recall_sha256")));
    }
    for (var row :
        store.rows(
            "SELECT * FROM video_transcript_spans WHERE revision_id=? AND index_ordinal IS NOT NULL ORDER BY index_ordinal",
            revisionId)) {
      var transcript =
          new VideoTranscriptEvidence(
              AuthorityRows.text(row, "id"),
              AuthorityRows.text(row, "revision_id"),
              new AudioTranscriptSpan(
                  AuthorityRows.integer(row, "ordinal"),
                  AuthorityRows.integer(row, "start_ms"),
                  AuthorityRows.integer(row, "end_ms"),
                  AuthorityRows.text(row, "text")),
              AuthorityRows.integer(row, "index_ordinal"));
      items.add(
          new ProjectionItem(
              transcript.id(),
              items.size(),
              transcript.span().text(),
              AuthorityRows.text(row, "text_sha256")));
    }
    for (var row :
        store.rows(
            "SELECT s.* FROM video_ocr_segments s JOIN video_frame_ocr f ON f.frame_id=s.frame_id WHERE s.revision_id=? ORDER BY f.ordinal,s.ordinal",
            revisionId)) {
      var segment =
          new VideoOcrSegment(
              AuthorityRows.integer(row, "ordinal"),
              AuthorityRows.integer(row, "start_offset"),
              AuthorityRows.integer(row, "end_offset"),
              AuthorityRows.text(row, "text"));
      var evidence =
          new VideoOcrSegmentEvidence(
              AuthorityRows.text(row, "id"),
              revisionId,
              AuthorityRows.text(row, "frame_id"),
              segment);
      items.add(
          new ProjectionItem(
              evidence.id(), items.size(), segment.text(), AuthorityRows.text(row, "text_sha256")));
    }
    if (store.count(
            "SELECT COUNT(*) FROM video_subtitle_compilations WHERE revision_id=?", revisionId)
        != 0) {
      var subtitles =
          IngestionRepository.readVideoSubtitleEvidence(store, revisionId)
              .orElseThrow(ModelValues::invalid);
      for (var track : subtitles.tracks()) {
        for (var cue : track.cues()) {
          if (cue.indexOrdinal() != null) {
            items.add(
                new ProjectionItem(cue.id(), items.size(), cue.cue().text(), cue.textSha256()));
          }
        }
      }
    }
    return List.copyOf(items);
  }

  private List<IndexSegment> segments(String revisionId) {
    return store
        .rows("SELECT * FROM corpus_segments WHERE revision_id=? ORDER BY ordinal", revisionId)
        .stream()
        .map(
            row ->
                new IndexSegment(
                    AuthorityRows.text(row, "id"),
                    AuthorityRows.integer(row, "ordinal"),
                    AuthorityRows.integer(row, "page_number"),
                    AuthorityRows.integer(row, "start_offset"),
                    AuthorityRows.integer(row, "end_offset"),
                    AuthorityRows.text(row, "text"),
                    AuthorityRows.text(row, "text_sha256")))
        .toList();
  }

  public void insertAttempt(String jobId, int attempt, String generation, String now) {
    store.execute(
        "INSERT INTO indexing_attempts(job_id,attempt,projection_generation_id,started_at) VALUES(?,?,?,?)",
        jobId,
        attempt,
        generation,
        now);
  }

  public void markProcessing(String jobId, String tokenHash, String generation, String now) {
    store.execute(
        "UPDATE indexing_jobs SET state='processing',claim_token_sha256=?,projection_generation_id=?,updated_at=? WHERE id=? AND state='queued'",
        tokenHash,
        generation,
        now,
        jobId);
  }

  public boolean attemptExists(String jobId, int attempt, String generation) {
    return store.count(
            "SELECT COUNT(*) FROM indexing_attempts WHERE job_id=? AND attempt=? AND projection_generation_id=?",
            jobId,
            attempt,
            generation)
        == 1;
  }

  public void insertPublication(IndexPublicationEntity publication) {
    var target = publication.target();
    store.execute(
        "INSERT INTO index_publications(id,job_id,document_id,revision_id,attempt,projection_generation_id,source_sha256,parser_revision,embedding_identity,projection_identity,model_revision,dimensions,manifest_sha256,segment_count,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        publication.id(),
        publication.jobId(),
        publication.documentId(),
        publication.revisionId(),
        publication.attempt(),
        publication.projectionGenerationId(),
        publication.sourceSha256(),
        publication.parserRevision(),
        target.embeddingIdentity(),
        target.projectionIdentity(),
        target.modelRevision(),
        target.dimensions(),
        publication.manifestSha256(),
        publication.segmentCount(),
        publication.createdAt());
  }

  public void insertPublicationEntry(
      String publicationId, String evidenceId, String physicalSegmentId, String digest) {
    if (store.count(
            "SELECT COUNT(*) FROM image_evidence i JOIN index_publications p ON p.revision_id=i.revision_id WHERE p.id=? AND i.id=?",
            publicationId,
            evidenceId)
        == 1) {
      store.execute(
          "INSERT INTO image_publication_entries(publication_id,image_evidence_id,physical_segment_id,entry_sha256) VALUES(?,?,?,?)",
          publicationId,
          evidenceId,
          physicalSegmentId,
          digest);
    } else if (store.count(
            "SELECT COUNT(*) FROM audio_spans s JOIN index_publications p ON p.revision_id=s.revision_id WHERE p.id=? AND s.id=? AND s.index_ordinal IS NOT NULL",
            publicationId,
            evidenceId)
        == 1) {
      store.execute(
          "INSERT INTO audio_publication_entries(publication_id,audio_span_id,physical_segment_id,entry_sha256) VALUES(?,?,?,?)",
          publicationId,
          evidenceId,
          physicalSegmentId,
          digest);
    } else if (store.count(
            "SELECT COUNT(*) FROM video_frames f JOIN index_publications p ON p.revision_id=f.revision_id WHERE p.id=? AND f.id=?",
            publicationId,
            evidenceId)
        == 1) {
      store.execute(
          "INSERT INTO video_frame_publication_entries(publication_id,video_frame_id,physical_segment_id,entry_sha256) VALUES(?,?,?,?)",
          publicationId,
          evidenceId,
          physicalSegmentId,
          digest);
    } else if (store.count(
            "SELECT COUNT(*) FROM video_transcript_spans s JOIN index_publications p ON p.revision_id=s.revision_id WHERE p.id=? AND s.id=? AND s.index_ordinal IS NOT NULL",
            publicationId,
            evidenceId)
        == 1) {
      store.execute(
          "INSERT INTO video_transcript_publication_entries(publication_id,video_transcript_span_id,physical_segment_id,entry_sha256) VALUES(?,?,?,?)",
          publicationId,
          evidenceId,
          physicalSegmentId,
          digest);
    } else if (store.count(
            "SELECT COUNT(*) FROM video_ocr_segments s JOIN index_publications p ON p.revision_id=s.revision_id WHERE p.id=? AND s.id=?",
            publicationId,
            evidenceId)
        == 1) {
      store.execute(
          "INSERT INTO video_ocr_publication_entries(publication_id,video_ocr_segment_id,physical_segment_id,entry_sha256) VALUES(?,?,?,?)",
          publicationId,
          evidenceId,
          physicalSegmentId,
          digest);
    } else if (store.count(
            "SELECT COUNT(*) FROM video_subtitle_cues s JOIN index_publications p ON p.revision_id=s.revision_id WHERE p.id=? AND s.id=? AND s.index_ordinal IS NOT NULL",
            publicationId,
            evidenceId)
        == 1) {
      store.execute(
          "INSERT INTO video_subtitle_publication_entries(publication_id,video_subtitle_cue_id,physical_segment_id,entry_sha256) VALUES(?,?,?,?)",
          publicationId,
          evidenceId,
          physicalSegmentId,
          digest);
    } else {
      store.execute(
          "INSERT INTO index_publication_entries(publication_id,source_segment_id,physical_segment_id,entry_sha256) VALUES(?,?,?,?)",
          publicationId,
          evidenceId,
          physicalSegmentId,
          digest);
    }
  }

  public void activatePublication(String documentId, String publicationId, String revisionId) {
    store.execute(
        "INSERT INTO active_corpus_publications(document_id,publication_id,revision_id) VALUES(?,?,?)",
        documentId,
        publicationId,
        revisionId);
  }

  public void markIndexed(String jobId, String now) {
    store.execute(
        "UPDATE indexing_jobs SET state='indexed',claim_token_sha256=NULL,error_code=NULL,updated_at=? WHERE id=?",
        now,
        jobId);
  }

  public void markFailed(String jobId, String code, String now) {
    store.execute(
        "UPDATE indexing_jobs SET state='failed',claim_token_sha256=NULL,error_code=?,updated_at=? WHERE id=?",
        code,
        now,
        jobId);
  }

  public void markCancelled(String jobId, String now) {
    store.execute(
        "UPDATE indexing_jobs SET state='cancelled',claim_token_sha256=NULL,error_code=NULL,updated_at=? WHERE id=?",
        now,
        jobId);
  }

  public void markQueued(String jobId, int attempt, String now) {
    store.execute(
        "UPDATE indexing_jobs SET state='queued',attempt=?,claim_token_sha256=NULL,projection_generation_id=NULL,error_code=NULL,updated_at=? WHERE id=?",
        attempt,
        now,
        jobId);
  }

  public String publicationId(String jobId) {
    var rows = store.rows("SELECT id FROM index_publications WHERE job_id=?", jobId);
    return rows.isEmpty() ? null : AuthorityRows.text(rows.getFirst(), "id");
  }

  public Optional<String> documentJobId(String documentId) {
    return store.rows("SELECT id FROM indexing_jobs WHERE document_id=?", documentId).stream()
        .findFirst()
        .map(row -> AuthorityRows.text(row, "id"));
  }

  public List<String> processingIds() {
    return store.rows("SELECT id FROM indexing_jobs WHERE state='processing'").stream()
        .map(row -> AuthorityRows.text(row, "id"))
        .toList();
  }
}
