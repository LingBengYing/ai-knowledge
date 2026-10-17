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

  /** One row per uploaded file with the task states the bulk actions decide on. */
  public List<Map<String, Object>> actionRows(String id) {
    return store.rows(
        "SELECT f.ordinal,f.document_id,f.revision_id,f.ingestion_task_id,g.state AS parse_state,"
            + automatic("ingestion", "g")
            + " AS parse_automatic,a.state AS request_state,a.task_id AS request_task,"
            + "j.id AS index_task,j.state AS index_state,"
            + automatic("indexing", "j")
            + " AS index_automatic "
            + JOIN
            + " WHERE f.batch_id=? AND f.document_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=f.document_id) ORDER BY f.ordinal",
        id);
  }

  /** Files a bulk retry could queue again: failed or cancelled work with attempts left. */
  public long retryable(String id) {
    return store.count(
        "SELECT COUNT(*) "
            + JOIN
            + " WHERE f.batch_id=? AND f.document_id IS NOT NULL"
            + " AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=f.document_id)"
            + " AND ((g.state IN ('failed','cancelled') AND g.attempt<3)"
            + " OR (g.state='parsed' AND a.state='failed' AND a.task_id IS NULL)"
            + " OR (g.state='parsed' AND j.state IN ('failed','cancelled') AND j.attempt<3))",
        id);
  }

  /** Queued work a batch cancel stops: parsing tasks and the batch's own import index tasks. */
  public List<Map<String, Object>> queuedTasks(String id) {
    return store.rows(
        "SELECT CASE WHEN g.state='queued' THEN g.id END AS parse_task,"
            + "CASE WHEN j.state='queued' THEN j.id END AS index_task "
            + JOIN
            + " WHERE f.batch_id=? AND (g.state='queued' OR j.state='queued')",
        id);
  }

  /** A user cancel is recorded as the batch's halt: no uploads and no automatic work start. */
  public void cancel(String id) {
    store.execute(
        "UPDATE document_import_batches SET halted_reason='batch_cancelled' WHERE id=?", id);
  }

  /** Clears the halt; a new limit may raise or lower the budget but never below what is spent. */
  public void resume(String id, Integer requestLimit) {
    if (requestLimit != null)
      store.execute(
          "UPDATE document_import_batches SET request_limit=? WHERE id=?", requestLimit, id);
    store.execute("UPDATE document_import_batches SET halted_reason=NULL WHERE id=?", id);
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

  /**
   * The batch file's own import index task: the job its import request submitted, or, before the
   * request records a task id (pending or dispatching), the non-rebuild job of that revision that
   * dispatch adopts as existing work. Later reindexes and model rebuilds are never batch work.
   */
  private static final String IMPORT_INDEX_TASK =
      """
      FROM document_import_batches b
      JOIN document_import_files f ON f.batch_id=b.id
      JOIN import_index_requests a ON a.revision_id=f.revision_id
      JOIN indexing_jobs j ON j.revision_id=f.revision_id AND j.document_id=f.document_id
      WHERE j.id=? AND j.model_rebuild_id IS NULL
        AND (a.task_id=j.id OR (a.task_id IS NULL AND a.state IN ('pending','dispatching')))
      """;

  /**
   * An attempt is automatic when it is the first one or was requeued by restart recovery or a
   * resume; only an explicit user retry is not. A halt stops automatic work only.
   */
  private static String automatic(String kind, String alias) {
    return "("
        + alias
        + ".attempt=1 OR EXISTS(SELECT 1 FROM batch_task_requeues q WHERE q.task_kind='"
        + kind
        + "' AND q.job_id="
        + alias
        + ".id AND q.attempt="
        + alias
        + ".attempt))";
  }

  /** Queued automatic import parsing of a halted batch is terminated by the claimant. */
  public boolean haltedImportIngestion(String ingestionJobId) {
    return store.count(
            "SELECT COUNT(*) FROM document_import_files f JOIN document_import_batches b ON b.id=f.batch_id JOIN ingestion_jobs g ON g.id=f.ingestion_task_id WHERE f.ingestion_task_id=? AND b.halted_reason IS NOT NULL AND "
                + automatic("ingestion", "g"),
            ingestionJobId)
        != 0;
  }

  /** Queued automatic import indexing of a halted batch is terminated by the claimant. */
  public boolean haltedImportIndexing(String indexingJobId) {
    return store.count(
            "SELECT COUNT(*) "
                + IMPORT_INDEX_TASK
                + " AND b.halted_reason IS NOT NULL AND "
                + automatic("indexing", "j"),
            indexingJobId)
        != 0;
  }

  /** The batch of an import parsing task, with whether that batch is halted. */
  public Optional<Boolean> ingestionBatchHalted(String ingestionJobId) {
    return store
        .rows(
            "SELECT b.halted_reason FROM document_import_files f JOIN document_import_batches b ON b.id=f.batch_id WHERE f.ingestion_task_id=?",
            ingestionJobId)
        .stream()
        .findFirst()
        .map(row -> row.get("halted_reason") != null);
  }

  /** The batch of an import index task, with whether that batch is halted. */
  public Optional<Boolean> indexingBatchHalted(String indexingJobId) {
    return store.rows("SELECT b.halted_reason " + IMPORT_INDEX_TASK, indexingJobId).stream()
        .findFirst()
        .map(row -> row.get("halted_reason") != null);
  }

  public void markAutomatic(String kind, String jobId, int attempt, String reason, String now) {
    store.execute(
        "INSERT OR IGNORE INTO batch_task_requeues(task_kind,job_id,attempt,reason,created_at) VALUES(?,?,?,?,?)",
        kind,
        jobId,
        attempt,
        reason,
        now);
  }

  /**
   * Reserves this attempt's expected requests plus up to {@code retries} more for transient
   * retries, taken only from the budget's spare room. Returns the granted retry allowance, or -1
   * when the attempt may not start. Indexing outside a batch spends no budget.
   *
   * <p>With {@code mayWait}, a shortfall that other running attempts of the batch could still cover
   * by releasing their unused retry allowance returns {@link #HELD} instead of halting: the caller
   * waits for them and asks again. Without it, any shortfall halts the batch.
   */
  public int reserve(IndexClaim claim, int requests, int retries) {
    return reserve(claim, requests, retries, false);
  }

  /** The budget is held by running attempts that may still release part of it. */
  public static final int HELD = -2;

  public int reserve(IndexClaim claim, int requests, int retries, boolean mayWait) {
    var rows = store.rows("SELECT b.* " + IMPORT_INDEX_TASK, claim.jobId());
    if (rows.isEmpty()) return retries;
    var row = rows.getFirst();
    String batch = (String) row.get("id");
    // Automatic attempts stop with the batch; explicit retries still respect the budget.
    if (row.get("halted_reason") != null && haltedImportIndexing(claim.jobId())) return -1;
    if (store.count(
            "SELECT COUNT(*) FROM batch_request_reservations WHERE job_id=? AND attempt=?",
            claim.jobId(),
            claim.attempt())
        != 0) return -1;
    long available =
        ((Number) row.get("request_limit")).longValue()
            - ((Number) row.get("reserved_requests")).longValue();
    if (requests > available) {
      long releasable =
          mayWait
              ? store.count(
                  """
                  SELECT COALESCE(SUM(r.reserved),0) FROM batch_request_reservations r
                  JOIN indexing_jobs j ON j.id=r.job_id AND j.attempt=r.attempt AND j.state='processing'
                  WHERE r.batch_id=? AND r.started IS NULL AND NOT (r.job_id=? AND r.attempt=?)
                  """,
                  batch,
                  claim.jobId(),
                  claim.attempt())
              : 0;
      if (requests <= available + releasable) return HELD;
      store.execute(
          "UPDATE document_import_batches SET halted_reason='embedding_budget_exhausted' WHERE id=?",
          batch);
      return -1;
    }
    int allowance = (int) Math.min(retries, available - requests);
    store.execute(
        "UPDATE document_import_batches SET reserved_requests=reserved_requests+? WHERE id=?",
        requests + allowance,
        batch);
    store.execute(
        "INSERT INTO batch_request_reservations(job_id,attempt,batch_id,reserved) VALUES(?,?,?,?)",
        claim.jobId(),
        claim.attempt(),
        batch,
        requests + allowance);
    return allowance;
  }

  /**
   * Records the audited usage. A complete, closed audit releases the unused retry allowance; the
   * expected requests stay charged as before, so {@code reserved_requests} remains an upper bound.
   */
  public void usage(IndexClaim claim, int started, int finished, boolean complete, int expected) {
    store.execute(
        "UPDATE batch_request_reservations SET started=?,finished=?,audit_complete=? WHERE job_id=? AND attempt=?",
        started,
        finished,
        complete ? 1 : 0,
        claim.jobId(),
        claim.attempt());
    if (!complete || started != finished) {
      store.execute(
          """
        UPDATE document_import_batches SET halted_reason=COALESCE(halted_reason,'model_usage_uncertain')
        WHERE id IN (SELECT batch_id FROM batch_request_reservations WHERE job_id=? AND attempt=?)
        """,
          claim.jobId(),
          claim.attempt());
      return;
    }
    for (var row :
        store.rows(
            "SELECT batch_id,reserved FROM batch_request_reservations WHERE job_id=? AND attempt=?",
            claim.jobId(),
            claim.attempt())) {
      long unused = ((Number) row.get("reserved")).longValue() - Math.max(expected, started);
      if (unused <= 0) continue;
      store.execute(
          "UPDATE batch_request_reservations SET reserved=reserved-? WHERE job_id=? AND attempt=?",
          unused,
          claim.jobId(),
          claim.attempt());
      store.execute(
          "UPDATE document_import_batches SET reserved_requests=reserved_requests-? WHERE id=?",
          unused,
          row.get("batch_id"));
    }
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
