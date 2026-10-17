package com.evidence.rag.repository;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.BatchImportFile;
import com.evidence.rag.model.domain.IndexClaim;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Batch metadata only; originals and task lifecycles remain in their existing authority tables. */
public final class DocumentBatchRepository {
  private final SqliteAuthorityStore store;

  public DocumentBatchRepository(SqliteAuthorityStore store) {
    this.store = store;
  }

  public Optional<Map<String, Object>> byRequest(Actor actor, String requestId) {
    return store
        .rows(
            "SELECT * FROM document_import_batches WHERE workspace_id=? AND actor_id=? AND request_id=?",
            actor.workspaceId(),
            actor.principalId(),
            requestId)
        .stream()
        .findFirst();
  }

  public Optional<Map<String, Object>> find(Actor actor, String id) {
    return store
        .rows(
            "SELECT * FROM document_import_batches WHERE workspace_id=? AND id=?",
            actor.workspaceId(),
            id)
        .stream()
        .findFirst();
  }

  public void insert(
      Actor actor,
      String id,
      String request,
      String digest,
      long budget,
      String now,
      List<BatchImportFile.Manifest> files) {
    store.execute(
        "INSERT INTO document_import_batches(id,workspace_id,actor_id,request_id,manifest_sha256,request_limit,created_at) VALUES(?,?,?,?,?,?,?)",
        id,
        actor.workspaceId(),
        actor.principalId(),
        request,
        digest,
        budget,
        now);
    var rows = new java.util.ArrayList<Object[]>(files.size());
    for (int i = 0; i < files.size(); i++) {
      var f = files.get(i);
      rows.add(new Object[] {id, i, f.filename(), f.sizeBytes(), f.sourceSha256()});
    }
    store.executeBatch(
        "INSERT INTO document_import_files(batch_id,ordinal,filename,size_bytes,source_sha256) VALUES(?,?,?,?,?)",
        rows);
  }

  public Map<String, Object> file(String id, int ordinal) {
    return store
        .rows("SELECT * FROM document_import_files WHERE batch_id=? AND ordinal=?", id, ordinal)
        .stream()
        .findFirst()
        .orElseThrow(com.evidence.rag.model.domain.ModelValues::notFound);
  }

  public void received(String id, int ordinal, String document, String revision, String task) {
    store.execute(
        "UPDATE document_import_files SET document_id=?,revision_id=?,ingestion_task_id=? WHERE batch_id=? AND ordinal=? AND document_id IS NULL",
        document,
        revision,
        task,
        id,
        ordinal);
  }

  private static final String JOIN =
      """
      FROM document_import_files f
      LEFT JOIN ingestion_jobs g ON g.id=f.ingestion_task_id
      LEFT JOIN import_index_requests a ON a.revision_id=f.revision_id
      LEFT JOIN indexing_jobs j ON j.id=a.task_id
      """;
  private static final String STATE =
      """
      CASE WHEN EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=f.document_id) THEN 'deleted'
        WHEN f.document_id IS NULL THEN 'awaiting_upload'
        WHEN g.state IN ('failed','cancelled') THEN g.state
        WHEN a.state='failed' THEN 'failed'
        WHEN j.state IN ('failed','cancelled') THEN j.state
        WHEN j.state='indexed' THEN 'indexed'
        WHEN j.state IN ('queued','processing','prepared') THEN 'indexing'
        WHEN g.state='parsed' THEN 'parsed'
        WHEN g.state='processing' THEN 'parsing'
        ELSE 'queued' END
      """;

  public List<BatchImportFile> files(String id, int offset, int limit) {
    return store
        .rows(
            "SELECT f.*, "
                + STATE
                + " AS batch_state, j.id AS index_task, COALESCE(g.error_code,a.error_code,j.error_code) AS failure "
                + JOIN
                + " WHERE f.batch_id=? ORDER BY f.ordinal LIMIT ? OFFSET ?",
            id,
            limit,
            offset)
        .stream()
        .map(
            r ->
                new BatchImportFile(
                    ((Number) r.get("ordinal")).intValue(),
                    (String) r.get("filename"),
                    ((Number) r.get("size_bytes")).longValue(),
                    (String) r.get("source_sha256"),
                    (String) r.get("batch_state"),
                    (String) r.get("document_id"),
                    (String) r.get("revision_id"),
                    (String) r.get("ingestion_task_id"),
                    (String) r.get("index_task"),
                    (String) r.get("failure")))
        .toList();
  }

  public Map<String, Long> counts(String id) {
    var result = new java.util.LinkedHashMap<String, Long>();
    for (var r :
        store.rows(
            "SELECT "
                + STATE
                + " AS state,COUNT(*) AS amount "
                + JOIN
                + " WHERE f.batch_id=? GROUP BY "
                + STATE,
            id)) result.put((String) r.get("state"), ((Number) r.get("amount")).longValue());
    return result;
  }

  public boolean canRun(String document) {
    var rows =
        store.rows(
            "SELECT b.id,b.halted_reason "
                + JOIN
                + " JOIN document_import_batches b ON b.id=f.batch_id WHERE f.document_id=?",
            document);
    if (rows.isEmpty()) return true;
    var r = rows.getFirst();
    return r.get("halted_reason") == null;
  }

  public boolean reserve(IndexClaim claim, int requests) {
    var rows =
        store.rows(
            "SELECT b.* FROM document_import_batches b JOIN document_import_files f ON f.batch_id=b.id WHERE f.document_id=?",
            claim.documentId());
    if (rows.isEmpty()) return true;
    var row = rows.getFirst();
    String batch = (String) row.get("id");
    if (!canRun(claim.documentId())) return false;
    if (store.count(
            "SELECT COUNT(*) FROM batch_request_reservations WHERE job_id=? AND attempt=?",
            claim.jobId(),
            claim.attempt())
        != 0) return false;
    if (((Number) row.get("reserved_requests")).longValue() + requests
        > ((Number) row.get("request_limit")).longValue()) {
      store.execute(
          "UPDATE document_import_batches SET halted_reason='embedding_budget_exhausted' WHERE id=?",
          batch);
      return false;
    }
    store.execute(
        "UPDATE document_import_batches SET reserved_requests=reserved_requests+? WHERE id=?",
        requests,
        batch);
    store.execute(
        "INSERT INTO batch_request_reservations(job_id,attempt,batch_id,reserved) VALUES(?,?,?,?)",
        claim.jobId(),
        claim.attempt(),
        batch,
        requests);
    return true;
  }

  public void haltDocument(String document, String reason) {
    store.execute(
        "UPDATE document_import_batches SET halted_reason=COALESCE(halted_reason,?) WHERE id IN (SELECT batch_id FROM document_import_files WHERE document_id=?)",
        reason,
        document);
  }

  public void usage(IndexClaim claim, int started, int finished, boolean complete) {
    store.execute(
        "UPDATE batch_request_reservations SET started=?,finished=?,audit_complete=? WHERE job_id=? AND attempt=?",
        started,
        finished,
        complete ? 1 : 0,
        claim.jobId(),
        claim.attempt());
    if (!complete || started != finished)
      store.execute(
          """
        UPDATE document_import_batches SET halted_reason=COALESCE(halted_reason,'model_usage_uncertain')
        WHERE id IN (SELECT batch_id FROM batch_request_reservations WHERE job_id=? AND attempt=?)
        """,
          claim.jobId(),
          claim.attempt());
  }

  public long observed(String id) {
    return store.count(
        "SELECT COALESCE(SUM(started),0) FROM batch_request_reservations WHERE batch_id=?", id);
  }

  public boolean uncertain(String id) {
    return store.count(
            "SELECT COUNT(*) FROM batch_request_reservations WHERE batch_id=? AND (audit_complete IS NULL OR audit_complete<>1)",
            id)
        > 0;
  }
}
