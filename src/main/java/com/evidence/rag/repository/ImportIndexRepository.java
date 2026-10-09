package com.evidence.rag.repository;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.entity.ImportIndexProgressEntity;
import com.evidence.rag.model.entity.ImportIndexRequestEntity;
import java.time.Instant;
import java.util.Optional;

/** Admission and progress share the original's authority transaction. */
public final class ImportIndexRepository {
  private final SqliteAuthorityStore store;

  public ImportIndexRepository(SqliteAuthorityStore store) {
    this.store = store;
  }

  public void insert(
      Actor actor,
      String documentId,
      String revisionId,
      String pipeline,
      String replacementId,
      String baseRevisionId) {
    String now = Instant.now().toString();
    store.execute(
        """
        INSERT INTO import_index_requests(revision_id,document_id,workspace_id,actor_id,pipeline,
          replacement_id,base_revision_id,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,'pending',?,?)
        """,
        revisionId,
        documentId,
        actor.workspaceId(),
        actor.principalId(),
        pipeline,
        replacementId,
        baseRevisionId,
        now,
        now);
  }

  public ImportIndexProgressEntity latest(String documentId) {
    var rows =
        store.rows(
            "SELECT state,error_code,task_id FROM import_index_requests WHERE document_id=? ORDER BY created_at DESC,revision_id DESC LIMIT 1",
            documentId);
    if (rows.isEmpty()) {
      return null;
    }
    var row = rows.getFirst();
    return new ImportIndexProgressEntity(
        AuthorityRows.text(row, "state"),
        AuthorityRows.text(row, "error_code"),
        AuthorityRows.text(row, "task_id"));
  }

  public Optional<ImportIndexRequestEntity> claim() {
    var rows =
        store.rows(
            """
        SELECT r.*,j.state parse_state FROM import_index_requests r
        LEFT JOIN ingestion_jobs j ON j.revision_id=r.revision_id
        WHERE r.state='pending'
          AND (r.pipeline!='corpus' OR j.state NOT IN ('queued','processing'))
          AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=r.document_id)
        ORDER BY r.created_at,r.revision_id LIMIT 1
        """);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    var r = rows.getFirst();
    String revision = AuthorityRows.text(r, "revision_id");
    store.execute(
        "UPDATE import_index_requests SET state='dispatching',updated_at=? WHERE revision_id=? AND state='pending'",
        Instant.now().toString(),
        revision);
    return Optional.of(
        new ImportIndexRequestEntity(
            revision,
            AuthorityRows.text(r, "document_id"),
            new Actor(AuthorityRows.text(r, "workspace_id"), AuthorityRows.text(r, "actor_id")),
            AuthorityRows.text(r, "pipeline"),
            AuthorityRows.text(r, "replacement_id"),
            AuthorityRows.text(r, "base_revision_id"),
            AuthorityRows.text(r, "parse_state")));
  }

  public String existingTask(Actor actor, String documentId, String revisionId) {
    var rows =
        store.rows(
            "SELECT j.id FROM indexing_jobs j JOIN documents d ON d.id=j.document_id WHERE j.revision_id=? AND j.document_id=? AND d.workspace_id=? ORDER BY j.created_at,j.id LIMIT 1",
            revisionId,
            documentId,
            actor.workspaceId());
    return rows.isEmpty() ? null : AuthorityRows.text(rows.getFirst(), "id");
  }

  public void finish(String revisionId, String taskId, String errorCode) {
    store.execute(
        "UPDATE import_index_requests SET state=?,task_id=?,error_code=?,updated_at=? WHERE revision_id=? AND state='dispatching'",
        errorCode == null ? "submitted" : "failed",
        taskId,
        errorCode,
        Instant.now().toString(),
        revisionId);
  }

  /** Corpus admission has no remote side effects; an existing job closes the receipt gap. */
  public void recover() {
    for (var row :
        store.rows(
            "SELECT revision_id,pipeline,document_id,workspace_id,actor_id FROM import_index_requests WHERE state='dispatching'")) {
      String revision = AuthorityRows.text(row, "revision_id");
      String pipeline = AuthorityRows.text(row, "pipeline");
      String document = AuthorityRows.text(row, "document_id");
      String workspace = AuthorityRows.text(row, "workspace_id");
      String task =
          existingTask(
              new Actor(workspace, AuthorityRows.text(row, "actor_id")), document, revision);
      if (task != null) {
        finish(revision, task, null);
      } else if ("corpus".equals(pipeline)) {
        store.execute(
            "UPDATE import_index_requests SET state='pending',updated_at=? WHERE revision_id=?",
            Instant.now().toString(),
            revision);
      } else {
        String table = "sound".equals(pipeline) ? "sound_publications" : "video_av_publications";
        boolean published =
            store.count(
                    "SELECT COUNT(*) FROM "
                        + table
                        + " WHERE source_revision_id=? AND document_id=? AND workspace_id=?",
                    revision,
                    document,
                    workspace)
                > 0;
        finish(revision, null, published ? null : "indexing_interrupted");
      }
    }
  }
}
