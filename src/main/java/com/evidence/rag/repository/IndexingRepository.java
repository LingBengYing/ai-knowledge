package com.evidence.rag.repository;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.IndexSegment;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.entity.IndexPublicationEntity;
import com.evidence.rag.model.entity.RevisionEntity;
import com.evidence.rag.model.entity.TaskEntity;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** SQL for index attempts, immutable publication entries and active pointers. */
public final class IndexingRepository {
  private final SqliteAuthorityStore store;

  public IndexingRepository(SqliteAuthorityStore store) {
    this.store = Objects.requireNonNull(store);
  }

  public Optional<RevisionEntity> parsedRevision(String documentId) {
    return store
        .rows(
            "SELECT r.* FROM corpus_documents c JOIN corpus_revisions r ON r.id=c.parsed_revision_id AND r.document_id=c.document_id JOIN ingestion_jobs p ON p.document_id=c.document_id AND p.revision_id=r.id WHERE c.document_id=? AND r.parsed_at IS NOT NULL AND r.segment_count BETWEEN 1 AND 4096 AND p.state='parsed'",
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
    return store.count("SELECT COUNT(*) FROM indexing_jobs WHERE state='processing'") != 0;
  }

  public List<String> queuedIds(String workspaceId) {
    return store
        .rows(
            "SELECT j.id FROM indexing_jobs j JOIN documents d ON d.id=j.document_id WHERE d.workspace_id=? AND j.state='queued' ORDER BY j.created_at,j.id",
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
            "SELECT j.*,d.filename,acl.role AS current_role FROM indexing_jobs j JOIN documents d ON d.id=j.document_id JOIN document_acl acl ON acl.document_id=d.id WHERE j.id=? AND d.workspace_id=? AND acl.principal_id=?"
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
            "SELECT COUNT(*) FROM corpus_documents c JOIN corpus_revisions r ON r.document_id=c.document_id AND r.id=c.parsed_revision_id JOIN documents d ON d.id=c.document_id JOIN ingestion_jobs p ON p.document_id=d.id AND p.revision_id=r.id WHERE c.document_id=? AND r.id=? AND r.source_sha256=? AND d.source_sha256=r.source_sha256 AND r.parser_revision=? AND r.parsed_at IS NOT NULL AND p.state='parsed' AND r.segment_count BETWEEN 1 AND 4096 AND r.segment_count=(SELECT COUNT(*) FROM corpus_segments s WHERE s.revision_id=r.id) AND NOT EXISTS(SELECT 1 FROM active_corpus_publications a WHERE a.document_id=d.id)",
            job.documentId(),
            job.revisionId(),
            job.sourceSha256(),
            job.parserRevision())
        == 1;
  }

  public List<IndexSegment> segments(String revisionId) {
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
      String publicationId, String sourceSegmentId, String physicalSegmentId, String digest) {
    store.execute(
        "INSERT INTO index_publication_entries(publication_id,source_segment_id,physical_segment_id,entry_sha256) VALUES(?,?,?,?)",
        publicationId,
        sourceSegmentId,
        physicalSegmentId,
        digest);
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
