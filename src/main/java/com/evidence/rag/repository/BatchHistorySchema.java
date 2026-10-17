package com.evidence.rag.repository;

import com.evidence.rag.model.domain.ModelValues;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * v41: what the import history and the batch page show beyond file states. Existing tables are not
 * rewritten; three new tables, two indexes and four triggers are added.
 *
 * <ul>
 *   <li>{@code document_import_file_paths}: the optional folder-relative path of a manifest file
 *       (only files that came from a folder have a row).
 *   <li>{@code document_import_file_times}: when each file's maintained state last changed, and
 *       when it reached a finished state (indexed, failed or cancelled). The trigger on {@code
 *       document_import_file_states} keeps it in the same transaction, so the batch page can count
 *       recently finished files with one index range instead of joining the task tables.
 *   <li>{@code document_import_batch_times}: the batch's last change (a file state, a halt or a new
 *       limit), for the import history list.
 * </ul>
 */
final class BatchHistorySchema {
  static final int VERSION = 41;

  /** Millisecond UTC timestamp, the same shape the Java side compares against. */
  static final String NOW = "strftime('%Y-%m-%dT%H:%M:%fZ','now')";

  private static final List<String> OBJECTS =
      List.of(
          "document_import_file_paths",
          "document_import_file_times",
          "document_import_batch_times",
          "document_import_file_times_finished",
          "document_import_batches_workspace",
          "batch_times_insert",
          "batch_times_update",
          "batch_file_times_insert",
          "batch_file_times_update");

  private final SqliteAuthorityStore store;

  BatchHistorySchema(SqliteAuthorityStore store) {
    this.store = store;
  }

  void migrate() {
    store.transaction(
        () -> {
          new BatchThroughputSchema(store).verify();
          store.execute(
              """
          CREATE TABLE document_import_file_paths(
            batch_id TEXT NOT NULL,ordinal INTEGER NOT NULL,
            relative_path TEXT NOT NULL CHECK(length(relative_path) BETWEEN 1 AND 1024),
            PRIMARY KEY(batch_id,ordinal),
            FOREIGN KEY(batch_id,ordinal) REFERENCES document_import_files(batch_id,ordinal))
          """);
          store.execute(
              """
          CREATE TABLE document_import_file_times(
            batch_id TEXT NOT NULL,ordinal INTEGER NOT NULL,
            changed_at TEXT NOT NULL,finished_at TEXT,
            PRIMARY KEY(batch_id,ordinal),
            FOREIGN KEY(batch_id,ordinal) REFERENCES document_import_files(batch_id,ordinal))
          """);
          store.execute(
              """
          CREATE TABLE document_import_batch_times(
            batch_id TEXT PRIMARY KEY REFERENCES document_import_batches(id),updated_at TEXT NOT NULL)
          """);
          store.execute(
              "CREATE INDEX document_import_file_times_finished ON document_import_file_times(batch_id,finished_at)");
          store.execute(
              "CREATE INDEX document_import_batches_workspace ON document_import_batches(workspace_id,created_at,id)");
          store.execute(
              """
          CREATE TRIGGER batch_times_insert AFTER INSERT ON document_import_batches
          BEGIN INSERT INTO document_import_batch_times(batch_id,updated_at) VALUES(NEW.id,NEW.created_at); END
          """);
          store.execute(
              "CREATE TRIGGER batch_times_update AFTER UPDATE OF halted_reason,request_limit ON document_import_batches"
                  + " WHEN OLD.halted_reason IS NOT NEW.halted_reason OR OLD.request_limit<>NEW.request_limit"
                  + " BEGIN UPDATE document_import_batch_times SET updated_at="
                  + NOW
                  + " WHERE batch_id=NEW.id; END");
          store.execute(
              "CREATE TRIGGER batch_file_times_insert AFTER INSERT ON document_import_file_states"
                  + " BEGIN INSERT INTO document_import_file_times(batch_id,ordinal,changed_at) VALUES(NEW.batch_id,NEW.ordinal,"
                  + NOW
                  + "); END");
          store.execute(
              "CREATE TRIGGER batch_file_times_update AFTER UPDATE OF state ON document_import_file_states"
                  + " WHEN OLD.state IS NOT NEW.state BEGIN"
                  + " UPDATE document_import_file_times SET changed_at="
                  + NOW
                  + ",finished_at=CASE WHEN NEW.state IN ('indexed','failed','cancelled') THEN "
                  + NOW
                  + " END WHERE batch_id=NEW.batch_id AND ordinal=NEW.ordinal;"
                  + " UPDATE document_import_batch_times SET updated_at="
                  + NOW
                  + " WHERE batch_id=NEW.batch_id; END");
          // Existing files: the latest task change is the best record of when they last moved.
          store.execute(
              """
              INSERT INTO document_import_file_times(batch_id,ordinal,changed_at,finished_at)
              SELECT x.batch_id,x.ordinal,x.changed,CASE WHEN x.state IN ('indexed','failed','cancelled') THEN x.changed END
              FROM (SELECT f.batch_id,f.ordinal,s.state,
                  COALESCE(NULLIF(max(COALESCE(g.updated_at,''),COALESCE(a.updated_at,''),COALESCE(j.updated_at,'')),''),b.created_at) AS changed
                FROM document_import_files f
                JOIN document_import_batches b ON b.id=f.batch_id
                JOIN document_import_file_states s ON s.batch_id=f.batch_id AND s.ordinal=f.ordinal
                LEFT JOIN ingestion_jobs g ON g.id=f.ingestion_task_id
                LEFT JOIN import_index_requests a ON a.revision_id=f.revision_id
                LEFT JOIN indexing_jobs j ON j.id=a.task_id) x
              """);
          store.execute(
              """
              INSERT INTO document_import_batch_times(batch_id,updated_at)
              SELECT b.id,max(b.created_at,COALESCE((SELECT max(t.changed_at) FROM document_import_file_times t WHERE t.batch_id=b.id),''))
              FROM document_import_batches b
              """);
          for (var row :
              store.rows(
                  "SELECT name,type,sql FROM sqlite_master WHERE name IN ("
                      + names()
                      + ") AND sql IS NOT NULL")) {
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
    new BatchThroughputSchema(store).verify(VERSION);
    if (store.count("SELECT COUNT(*) FROM pragma_table_info('document_import_file_paths')") != 3
        || store.count("SELECT COUNT(*) FROM pragma_table_info('document_import_file_times')") != 4
        || store.count("SELECT COUNT(*) FROM pragma_table_info('document_import_batch_times')") != 2
        || store.count("SELECT COUNT(*) FROM sqlite_master WHERE name IN (" + names() + ")")
            != OBJECTS.size()
        || store.count(
                "SELECT COUNT(*) FROM document_import_file_states s WHERE NOT EXISTS(SELECT 1 FROM document_import_file_times t WHERE t.batch_id=s.batch_id AND t.ordinal=s.ordinal)")
            != 0
        || store.count(
                "SELECT COUNT(*) FROM document_import_batches b WHERE NOT EXISTS(SELECT 1 FROM document_import_batch_times t WHERE t.batch_id=b.id)")
            != 0) throw new IllegalStateException("Unsupported batch history schema");
  }

  private static String names() {
    return String.join(",", OBJECTS.stream().map(name -> "'" + name + "'").toList());
  }
}
