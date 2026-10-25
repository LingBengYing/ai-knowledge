package com.evidence.rag.repository;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.BatchImportFile;
import com.evidence.rag.model.domain.BatchPlacement;
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

  public void savePlacement(String batchId, BatchPlacement placement) {
    if (placement.targetFolderId() == null && !placement.preservePaths()) return;
    store.execute(
        "INSERT INTO document_import_batch_placement(batch_id,target_folder_id,target_folder_name,preserve_paths) VALUES(?,?,?,?)",
        batchId,
        placement.targetFolderId(),
        placement.targetFolderName(),
        placement.preservePaths() ? 1 : 0);
  }

  public BatchPlacement placement(String batchId) {
    return store
        .rows("SELECT * FROM document_import_batch_placement WHERE batch_id=?", batchId)
        .stream()
        .findFirst()
        .map(
            row ->
                new BatchPlacement(
                    (String) row.get("target_folder_id"),
                    (String) row.get("target_folder_name"),
                    ((Number) row.get("preserve_paths")).intValue() == 1))
        .orElse(BatchPlacement.NONE);
  }

  public String relativePath(String batchId, int ordinal) {
    return store
        .rows(
            "SELECT relative_path FROM document_import_file_paths WHERE batch_id=? AND ordinal=?",
            batchId,
            ordinal)
        .stream()
        .findFirst()
        .map(row -> (String) row.get("relative_path"))
        .orElse(null);
  }

  /**
   * Metadata only; the service also resolves these candidates through the authority publication
   * contract.
   */
  public List<Map<String, Object>> sourceCandidates(String batchId) {
    return store.rows(
        """
        SELECT f.ordinal,f.document_id,f.filename,d.display_name,r.relative_path,f.revision_id
        FROM document_import_files f
        JOIN documents d ON d.id=f.document_id
        JOIN active_corpus_publications a ON a.document_id=d.id AND a.revision_id=f.revision_id
        LEFT JOIN document_import_file_paths r ON r.batch_id=f.batch_id AND r.ordinal=f.ordinal
        WHERE f.batch_id=? AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
          AND (EXISTS(SELECT 1 FROM index_publication_entries e WHERE e.publication_id=a.publication_id)
            OR EXISTS(SELECT 1 FROM audio_publication_entries e WHERE e.publication_id=a.publication_id)
            OR EXISTS(SELECT 1 FROM video_transcript_publication_entries e WHERE e.publication_id=a.publication_id)
            OR EXISTS(SELECT 1 FROM video_ocr_publication_entries e WHERE e.publication_id=a.publication_id)
            OR EXISTS(SELECT 1 FROM video_subtitle_publication_entries e WHERE e.publication_id=a.publication_id))
        ORDER BY f.ordinal
        """,
        batchId);
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
    var paths = new java.util.ArrayList<Object[]>();
    for (int i = 0; i < files.size(); i++)
      if (files.get(i).relativePath() != null)
        paths.add(new Object[] {id, i, files.get(i).relativePath()});
    if (!paths.isEmpty())
      store.executeBatch(
          "INSERT INTO document_import_file_paths(batch_id,ordinal,relative_path) VALUES(?,?,?)",
          paths);
  }

  /** Registers an open batch: no files yet, the announced count and the duplicate choice. */
  public void open(String id, int expectedTotal, boolean includeDuplicates) {
    store.execute(
        "INSERT INTO document_import_batch_streams(batch_id,expected_total,include_duplicates) VALUES(?,?,?)",
        id,
        expectedTotal,
        includeDuplicates ? 1 : 0);
  }

  /** The open-batch row of a batch, absent for a one-shot manifest. */
  public Optional<Map<String, Object>> stream(String id) {
    return store.rows("SELECT * FROM document_import_batch_streams WHERE batch_id=?", id).stream()
        .findFirst();
  }

  /** Open-batch rows of several batches at once, keyed by batch id. */
  public Map<String, Map<String, Object>> streams(List<String> ids) {
    var result = new java.util.HashMap<String, Map<String, Object>>();
    if (ids.isEmpty()) return result;
    for (var row :
        store.rows(
            "SELECT * FROM document_import_batch_streams WHERE batch_id IN ("
                + String.join(",", java.util.Collections.nCopies(ids.size(), "?"))
                + ")",
            ids.toArray())) result.put((String) row.get("batch_id"), row);
    return result;
  }

  /** How many manifest entries the batch has: its ordinals are 0 to this minus one. */
  public int registered(String id) {
    return (int) store.count("SELECT COUNT(*) FROM document_import_files WHERE batch_id=?", id);
  }

  /** The total declared bytes of the batch's registered entries. */
  public long registeredBytes(String id) {
    return store.count(
        "SELECT COALESCE(SUM(size_bytes),0) FROM document_import_files WHERE batch_id=?", id);
  }

  /** Registered entries from {@code from} to {@code to} (exclusive), with their folder paths. */
  public List<Map<String, Object>> entries(String id, int from, int to) {
    return store.rows(
        "SELECT f.ordinal,f.filename,f.size_bytes,f.source_sha256,r.relative_path FROM document_import_files f"
            + " LEFT JOIN document_import_file_paths r ON r.batch_id=f.batch_id AND r.ordinal=f.ordinal"
            + " WHERE f.batch_id=? AND f.ordinal>=? AND f.ordinal<? ORDER BY f.ordinal",
        id,
        from,
        to);
  }

  /** Appends entries at ordinals {@code base}, {@code base}+1, ... of an open batch. */
  public void append(String id, int base, List<BatchImportFile.Manifest> files) {
    var rows = new java.util.ArrayList<Object[]>(files.size());
    var paths = new java.util.ArrayList<Object[]>();
    for (int i = 0; i < files.size(); i++) {
      var f = files.get(i);
      rows.add(new Object[] {id, base + i, f.filename(), f.sizeBytes(), f.sourceSha256()});
      if (f.relativePath() != null) paths.add(new Object[] {id, base + i, f.relativePath()});
    }
    store.executeBatch(
        "INSERT INTO document_import_files(batch_id,ordinal,filename,size_bytes,source_sha256) VALUES(?,?,?,?,?)",
        rows);
    if (!paths.isEmpty())
      store.executeBatch(
          "INSERT INTO document_import_file_paths(batch_id,ordinal,relative_path) VALUES(?,?,?)",
          paths);
    touch(id);
  }

  /** Closes an open batch's manifest; returns false when it was already sealed. */
  public boolean seal(String id) {
    if (store.count(
            "SELECT COUNT(*) FROM document_import_batch_streams WHERE batch_id=? AND sealed_at IS NULL",
            id)
        == 0) return false;
    store.execute(
        "UPDATE document_import_batch_streams SET sealed_at="
            + BatchHistorySchema.NOW
            + " WHERE batch_id=? AND sealed_at IS NULL",
        id);
    touch(id);
    return true;
  }

  /** Records a manifest change as the batch's last update. */
  private void touch(String id) {
    store.execute(
        "UPDATE document_import_batch_times SET updated_at="
            + BatchHistorySchema.NOW
            + " WHERE batch_id=?",
        id);
  }

  /**
   * The first registered ordinal of each of these digests that is neither a skipped duplicate nor
   * deleted, among ordinals below {@code before}: an appended entry with the same bytes repeats
   * that file.
   */
  public Map<String, Integer> firstOrdinals(
      String id, int before, java.util.Collection<String> digests) {
    var result = new java.util.HashMap<String, Integer>();
    if (digests.isEmpty()) return result;
    var arguments = new java.util.ArrayList<Object>();
    arguments.add(id);
    arguments.add(before);
    arguments.addAll(digests);
    for (var row :
        store.rows(
            "SELECT f.source_sha256,MIN(f.ordinal) AS first FROM document_import_files f"
                + " JOIN document_import_file_states s ON s.batch_id=f.batch_id AND s.ordinal=f.ordinal"
                + " WHERE f.batch_id=? AND f.ordinal<? AND s.state NOT IN ('duplicate','deleted') AND f.source_sha256 IN ("
                + String.join(",", java.util.Collections.nCopies(digests.size(), "?"))
                + ") GROUP BY f.source_sha256",
            arguments.toArray()))
      result.put((String) row.get("source_sha256"), ((Number) row.get("first")).intValue());
    return result;
  }

  /**
   * The maintained state of entries {@code from} to {@code to} (exclusive), for an append reply.
   */
  public List<Map<String, Object>> entryStates(String id, int from, int to) {
    return store.rows(
        "SELECT s.ordinal,s.state,s.duplicate_ordinal,COALESCE(s.duplicate_document_id,p.document_id) AS duplicate_document"
            + " FROM document_import_file_states s"
            + " LEFT JOIN document_import_files p ON p.batch_id=s.batch_id AND p.ordinal=s.duplicate_ordinal"
            + " WHERE s.batch_id=? AND s.ordinal>=? AND s.ordinal<? ORDER BY s.ordinal",
        id,
        from,
        to);
  }

  /** The batch's last recorded change (a file state, a halt or a new limit). */
  public String updatedAt(String id) {
    return store
        .rows("SELECT updated_at FROM document_import_batch_times WHERE batch_id=?", id)
        .stream()
        .findFirst()
        .map(row -> AuthorityRows.text(row, "updated_at"))
        .orElse(null);
  }

  /** How many batches the workspace has; every member of the workspace sees its batches. */
  public int countForWorkspace(Actor actor) {
    return (int)
        store.count(
            "SELECT COUNT(*) FROM document_import_batches WHERE workspace_id=?",
            actor.workspaceId());
  }

  /** One page of the workspace's batches, newest first, with their last change. */
  public List<Map<String, Object>> page(Actor actor, int offset, int limit) {
    return store.rows(
        "SELECT b.id,b.created_at,b.halted_reason,t.updated_at FROM document_import_batches b"
            + " LEFT JOIN document_import_batch_times t ON t.batch_id=b.id"
            + " WHERE b.workspace_id=? ORDER BY b.created_at DESC,b.id DESC LIMIT ? OFFSET ?",
        actor.workspaceId(),
        limit,
        offset);
  }

  /** Files that finished (indexed, failed or cancelled) at or after {@code since}. */
  public long finishedSince(String id, String since) {
    return store.count(
        "SELECT COUNT(*) FROM document_import_file_times WHERE batch_id=? AND finished_at>=?",
        id,
        since);
  }

  /**
   * Marks manifest files whose bytes already exist as a live (not removed) document of the
   * workspace, or repeat an earlier file of the same manifest, as {@code duplicate}: they are never
   * uploaded and count as settled. Returns how many were marked.
   */
  public int markDuplicates(Actor actor, String id, List<BatchImportFile.Manifest> files) {
    return markDuplicates(actor, id, 0, files, Map.of());
  }

  /**
   * As {@link #markDuplicates(Actor, String, List)} for entries appended at ordinals {@code base}
   * onwards: {@code earlier} maps the digests of files registered before them to the first such
   * file, so an appended entry also repeats an earlier chunk of the same batch.
   */
  public int markDuplicates(
      Actor actor,
      String id,
      int base,
      List<BatchImportFile.Manifest> files,
      Map<String, Integer> earlier) {
    var live = new java.util.HashMap<String, java.util.Optional<String>>();
    var first = new java.util.HashMap<String, Integer>(earlier);
    var rows = new java.util.ArrayList<Object[]>();
    for (int i = base; i < base + files.size(); i++) {
      String sha = files.get(i - base).sourceSha256();
      var existing =
          live.computeIfAbsent(
              sha,
              digest ->
                  store
                      .rows(
                          "SELECT d.id FROM documents d WHERE d.workspace_id=? AND d.source_sha256=? AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id) ORDER BY d.updated_at DESC,d.id LIMIT 1",
                          actor.workspaceId(),
                          digest)
                      .stream()
                      .findFirst()
                      .map(row -> AuthorityRows.text(row, "id")));
      if (existing.isPresent()) {
        rows.add(new Object[] {existing.orElseThrow(), null, id, i});
      } else if (first.containsKey(sha)) {
        rows.add(new Object[] {null, first.get(sha), id, i});
      } else {
        first.put(sha, i);
      }
    }
    store.executeBatch(
        "UPDATE document_import_file_states SET state='duplicate',duplicate_document_id=?,duplicate_ordinal=? WHERE batch_id=? AND ordinal=?",
        rows);
    return rows.size();
  }

  public boolean duplicate(String id, int ordinal) {
    return store.count(
            "SELECT COUNT(*) FROM document_import_file_states WHERE batch_id=? AND ordinal=? AND state='duplicate'",
            id,
            ordinal)
        != 0;
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

  private static final String JOIN = BatchThroughputSchema.INPUTS;

  /** One page; the state is the trigger-maintained row, not recomputed per poll. */
  public List<BatchImportFile> files(String id, int offset, int limit) {
    return files(id, List.of(), offset, limit);
  }

  /**
   * One page of the files whose maintained state is one of {@code states} (all files when empty),
   * in manifest order. The state filter is a range of the (batch_id,state) index.
   */
  public List<BatchImportFile> files(String id, List<String> states, int offset, int limit) {
    var arguments = new java.util.ArrayList<Object>();
    arguments.add(id);
    arguments.addAll(states);
    arguments.add(limit);
    arguments.add(offset);
    String filter =
        states.isEmpty()
            ? ""
            : " AND s.state IN ("
                + String.join(",", java.util.Collections.nCopies(states.size(), "?"))
                + ")";
    return store
        .rows(
            """
            SELECT f.*,s.state AS batch_state,s.duplicate_ordinal,
              COALESCE(s.duplicate_document_id,p.document_id) AS duplicate_document,
              j.id AS index_task,COALESCE(g.error_code,a.error_code,j.error_code) AS failure,
              r.relative_path,g.created_at AS started,t.finished_at
            FROM document_import_files f
            JOIN document_import_file_states s ON s.batch_id=f.batch_id AND s.ordinal=f.ordinal
            LEFT JOIN document_import_files p ON p.batch_id=f.batch_id AND p.ordinal=s.duplicate_ordinal
            LEFT JOIN ingestion_jobs g ON g.id=f.ingestion_task_id
            LEFT JOIN import_index_requests a ON a.revision_id=f.revision_id
            LEFT JOIN indexing_jobs j ON j.id=a.task_id
            LEFT JOIN document_import_file_paths r ON r.batch_id=f.batch_id AND r.ordinal=f.ordinal
            LEFT JOIN document_import_file_times t ON t.batch_id=f.batch_id AND t.ordinal=f.ordinal
            WHERE f.batch_id=?"""
                + filter
                + " ORDER BY f.ordinal LIMIT ? OFFSET ?",
            arguments.toArray())
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
                    (String) r.get("failure"),
                    (String) r.get("duplicate_document"),
                    r.get("duplicate_ordinal") == null
                        ? null
                        : ((Number) r.get("duplicate_ordinal")).intValue(),
                    (String) r.get("relative_path"),
                    (String) r.get("started"),
                    (String) r.get("finished_at")))
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

  /**
   * Files a bulk retry could queue again: failed or cancelled work with attempts left. Only files
   * whose maintained state is failed or cancelled can qualify, so the join covers just those.
   */
  public long retryable(String id) {
    return store.count(
        "SELECT COUNT(*) FROM document_import_file_states s JOIN document_import_files f ON f.batch_id=s.batch_id AND f.ordinal=s.ordinal"
            + " LEFT JOIN ingestion_jobs g ON g.id=f.ingestion_task_id"
            + " LEFT JOIN import_index_requests a ON a.revision_id=f.revision_id"
            + " LEFT JOIN indexing_jobs j ON j.id=a.task_id"
            + " WHERE s.batch_id=? AND s.state IN ('failed','cancelled') AND f.document_id IS NOT NULL"
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

  /** Per-state file counts of several batches at once, keyed by batch id. */
  public Map<String, Map<String, Long>> counts(List<String> ids) {
    var result = new java.util.HashMap<String, Map<String, Long>>();
    if (ids.isEmpty()) return result;
    for (var r :
        store.rows(
            "SELECT batch_id,state,COUNT(*) AS amount FROM document_import_file_states WHERE batch_id IN ("
                + String.join(",", java.util.Collections.nCopies(ids.size(), "?"))
                + ") GROUP BY batch_id,state ORDER BY batch_id,state",
            ids.toArray()))
      result
          .computeIfAbsent((String) r.get("batch_id"), k -> new java.util.LinkedHashMap<>())
          .put((String) r.get("state"), ((Number) r.get("amount")).longValue());
    return result;
  }

  /** Per-state file counts from the maintained state rows (one covering-index range scan). */
  public Map<String, Long> counts(String id) {
    var result = new java.util.LinkedHashMap<String, Long>();
    for (var r :
        store.rows(
            "SELECT state,COUNT(*) AS amount FROM document_import_file_states WHERE batch_id=? GROUP BY state",
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
