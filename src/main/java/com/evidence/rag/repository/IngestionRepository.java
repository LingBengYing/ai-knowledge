package com.evidence.rag.repository;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.ParsedText;
import com.evidence.rag.model.domain.TextPage;
import com.evidence.rag.model.domain.TextSegment;
import com.evidence.rag.model.entity.TaskEntity;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Persistence for immutable originals, evidence and ingestion attempts. No parsing or policy. */
public final class IngestionRepository {
  private final SqliteAuthorityStore store;

  public IngestionRepository(SqliteAuthorityStore store) {
    this.store = Objects.requireNonNull(store);
  }

  public long pendingCount(String workspaceId) {
    return store.count(
        "SELECT COUNT(*) FROM ingestion_jobs j JOIN documents d ON d.id=j.document_id WHERE d.workspace_id=? AND j.state IN ('queued','processing') AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)",
        workspaceId);
  }

  public long storedBytes(String workspaceId) {
    return store.count(
        "SELECT COALESCE(SUM(d.size_bytes),0) FROM documents d JOIN corpus_documents c ON c.document_id=d.id WHERE d.workspace_id=?",
        workspaceId);
  }

  public void insertOriginal(
      String documentId,
      String revisionId,
      String parserRevision,
      String sourceHash,
      byte[] original,
      String now) {
    store.execute(
        "INSERT INTO corpus_revisions(id,document_id,parser_revision,source_sha256,created_at) VALUES(?,?,?,?,?)",
        revisionId,
        documentId,
        parserRevision,
        sourceHash,
        now);
    store.execute(
        "INSERT INTO corpus_documents(document_id,original_blob,initial_revision_id) VALUES(?,?,?)",
        documentId,
        original,
        revisionId);
  }

  public void insertJob(
      String jobId, String documentId, String revisionId, String creator, String now) {
    store.execute(
        "INSERT INTO ingestion_jobs(id,document_id,revision_id,state,attempt,created_by,created_at,updated_at) VALUES(?,?,?,'queued',1,?,?,?)",
        jobId,
        documentId,
        revisionId,
        creator,
        now,
        now);
  }

  public Optional<String> nextQueuedId(String workspaceId) {
    return store
        .rows(
            "SELECT j.id FROM ingestion_jobs j JOIN documents d ON d.id=j.document_id WHERE d.workspace_id=? AND j.state='queued' AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id) ORDER BY j.created_at,j.id LIMIT 1",
            workspaceId)
        .stream()
        .findFirst()
        .map(row -> AuthorityRows.text(row, "id"));
  }

  public void markProcessing(String jobId, String tokenHash, String now) {
    store.execute(
        "UPDATE ingestion_jobs SET state='processing',claim_token_sha256=?,updated_at=? WHERE id=? AND state='queued'",
        tokenHash,
        now,
        jobId);
  }

  public Optional<TaskEntity> findInternalTask(String jobId) {
    return store
        .rows(
            "SELECT j.*,d.workspace_id,d.filename,d.mime_type,d.source_sha256,r.parser_revision FROM ingestion_jobs j JOIN documents d ON d.id=j.document_id JOIN corpus_documents c ON c.document_id=d.id AND c.initial_revision_id=j.revision_id JOIN corpus_revisions r ON r.id=j.revision_id AND r.document_id=d.id WHERE j.id=?",
            jobId)
        .stream()
        .findFirst()
        .map(row -> AuthorityRows.task(row, false));
  }

  public Optional<TaskEntity> findAuthorizedTask(Actor actor, String jobId, boolean edit) {
    return store
        .rows(
            "SELECT j.*,d.filename,acl.role AS current_role FROM ingestion_jobs j JOIN documents d ON d.id=j.document_id JOIN document_acl acl ON acl.document_id=d.id WHERE j.id=? AND d.workspace_id=? AND acl.principal_id=? AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)"
                + (edit ? " AND acl.role IN ('owner','editor')" : ""),
            jobId,
            actor.workspaceId(),
            actor.principalId())
        .stream()
        .findFirst()
        .map(row -> AuthorityRows.task(row, false));
  }

  public byte[] original(String documentId) {
    return ((byte[])
            store
                .rows("SELECT original_blob FROM corpus_documents WHERE document_id=?", documentId)
                .getFirst()
                .get("original_blob"))
        .clone();
  }

  public void insertPage(String revisionId, TextPage page, String textHash) {
    store.execute(
        "INSERT INTO corpus_pages VALUES(?,?,?,?)",
        revisionId,
        page.number(),
        page.text(),
        textHash);
  }

  public void insertSegment(
      String segmentId, String revisionId, TextSegment segment, String textHash) {
    store.execute(
        "INSERT INTO corpus_segments VALUES(?,?,?,?,?,?,?,?)",
        segmentId,
        revisionId,
        segment.ordinal(),
        segment.page(),
        segment.start(),
        segment.end(),
        segment.text(),
        textHash);
  }

  public void markParsed(
      String jobId, String documentId, String revisionId, int pages, int segments, String now) {
    store.execute(
        "UPDATE corpus_revisions SET parsed_at=?,page_count=?,segment_count=? WHERE id=?",
        now,
        pages,
        segments,
        revisionId);
    store.execute(
        "UPDATE corpus_documents SET parsed_revision_id=? WHERE document_id=?",
        revisionId,
        documentId);
    store.execute(
        "UPDATE ingestion_jobs SET state='parsed',claim_token_sha256=NULL,error_code=NULL,updated_at=? WHERE id=?",
        now,
        jobId);
    store.execute("UPDATE documents SET updated_at=? WHERE id=?", now, documentId);
  }

  public void markFailed(String jobId, String safeCode, String now) {
    store.execute(
        "UPDATE ingestion_jobs SET state='failed',claim_token_sha256=NULL,error_code=?,updated_at=? WHERE id=?",
        safeCode,
        now,
        jobId);
  }

  public void markCancelled(String jobId, String now) {
    store.execute(
        "UPDATE ingestion_jobs SET state='cancelled',claim_token_sha256=NULL,error_code=NULL,updated_at=? WHERE id=?",
        now,
        jobId);
  }

  public void markQueued(String jobId, int attempt, String now) {
    store.execute(
        "UPDATE ingestion_jobs SET state='queued',attempt=?,claim_token_sha256=NULL,error_code=NULL,updated_at=? WHERE id=?",
        attempt,
        now,
        jobId);
  }

  public List<String> processingIds() {
    return store.rows("SELECT id FROM ingestion_jobs WHERE state='processing'").stream()
        .map(row -> AuthorityRows.text(row, "id"))
        .toList();
  }

  public Optional<String> authorizedParsedRevision(Actor actor, String documentId) {
    return store
        .rows(
            "SELECT c.parsed_revision_id FROM corpus_documents c JOIN documents d ON d.id=c.document_id JOIN document_acl acl ON acl.document_id=d.id WHERE d.id=? AND d.workspace_id=? AND acl.principal_id=? AND c.parsed_revision_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)",
            documentId,
            actor.workspaceId(),
            actor.principalId())
        .stream()
        .findFirst()
        .map(row -> AuthorityRows.text(row, "parsed_revision_id"));
  }

  public ParsedText parsedEvidence(String revisionId) {
    var pages =
        store
            .rows(
                "SELECT page_number,text FROM corpus_pages WHERE revision_id=? ORDER BY page_number",
                revisionId)
            .stream()
            .map(
                row ->
                    new TextPage(
                        AuthorityRows.integer(row, "page_number"), AuthorityRows.text(row, "text")))
            .toList();
    var segments =
        store
            .rows(
                "SELECT ordinal,page_number,start_offset,end_offset,text FROM corpus_segments WHERE revision_id=? ORDER BY ordinal",
                revisionId)
            .stream()
            .map(
                row ->
                    new TextSegment(
                        AuthorityRows.integer(row, "ordinal"),
                        AuthorityRows.integer(row, "page_number"),
                        AuthorityRows.integer(row, "start_offset"),
                        AuthorityRows.integer(row, "end_offset"),
                        AuthorityRows.text(row, "text")))
            .toList();
    return new ParsedText(pages, segments);
  }
}
