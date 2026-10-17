package com.evidence.rag.repository;

import com.evidence.rag.model.domain.ModelValues;
import java.nio.charset.StandardCharsets;

/**
 * v39: records which attempt of a batch import task was created automatically (restart recovery or
 * resuming a halted batch), so a halt still stops automatic work while an explicit user retry runs.
 * The existing three-attempt column bounds how often a task can be requeued.
 */
final class BatchRequeueSchema {
  static final int VERSION = 39;
  private final SqliteAuthorityStore store;

  BatchRequeueSchema(SqliteAuthorityStore store) {
    this.store = store;
  }

  void migrate() {
    store.transaction(
        () -> {
          new DocumentBatchSchema(store).verify();
          store.execute(
              """
          CREATE TABLE batch_task_requeues(
            task_kind TEXT NOT NULL CHECK(task_kind IN ('ingestion','indexing')),
            job_id TEXT NOT NULL,attempt INTEGER NOT NULL CHECK(attempt BETWEEN 1 AND 3),
            reason TEXT NOT NULL CHECK(reason IN ('restart','resume')),created_at TEXT NOT NULL,
            PRIMARY KEY(task_kind,job_id,attempt))
          """);
          for (var row :
              store.rows(
                  "SELECT name,type,sql FROM sqlite_master WHERE name='batch_task_requeues' AND sql IS NOT NULL")) {
            store.execute(
                "INSERT INTO cleanup_schema_objects(name,object_type,sql_sha256) VALUES(?,?,?)",
                row.get("name"),
                row.get("type"),
                ModelValues.sha256(
                    AuthorityRows.text(row, "sql").getBytes(StandardCharsets.UTF_8)));
          }
          store.execute("UPDATE format_info SET version=" + VERSION);
          store.execute("PRAGMA user_version=" + VERSION);
          verify();
          return null;
        });
  }

  void verify() {
    verify(VERSION);
  }

  void verify(int version) {
    new DocumentBatchSchema(store).verify(version);
    if (store.count("SELECT COUNT(*) FROM pragma_table_info('batch_task_requeues')") != 5)
      throw new IllegalStateException("Unsupported batch requeue schema");
  }
}
