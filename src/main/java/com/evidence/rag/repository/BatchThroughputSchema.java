package com.evidence.rag.repository;

import com.evidence.rag.model.domain.ModelValues;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * v40: one maintained state row per batch file, plus the lookup indexes the fair queue, the
 * duplicate check and the batch page need. Existing tables are not rewritten.
 *
 * <p>{@code document_import_file_states.state} is the same derivation the batch page used to
 * compute on every poll from four tables ({@link #STATE}); triggers recompute it for exactly the
 * affected files whenever one of its inputs changes (the file row, its parsing task, its import
 * index request, the request's indexing task, or a tombstone), inside the same transaction. A
 * {@code duplicate} file is skipped at registration and never gets an input, so it stays duplicate.
 */
final class BatchThroughputSchema {
  static final int VERSION = 40;

  /** Batch file state from its inputs; aliases f (file), g (parse), a (request), j (index). */
  static final String STATE =
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

  static final String INPUTS =
      """
      FROM document_import_files f
      LEFT JOIN ingestion_jobs g ON g.id=f.ingestion_task_id
      LEFT JOIN import_index_requests a ON a.revision_id=f.revision_id
      LEFT JOIN indexing_jobs j ON j.id=a.task_id
      """;

  private static final List<String> OBJECTS =
      List.of(
          "document_import_file_states",
          "document_import_file_states_state",
          "document_import_files_ingestion_task",
          "document_import_files_revision",
          "import_index_requests_task",
          "batch_request_reservations_batch",
          "documents_workspace_source",
          "batch_file_state_insert",
          "batch_file_state_received",
          "batch_file_state_parse",
          "batch_file_state_request_insert",
          "batch_file_state_request_update",
          "batch_file_state_index",
          "batch_file_state_tombstone");

  private final SqliteAuthorityStore store;

  BatchThroughputSchema(SqliteAuthorityStore store) {
    this.store = store;
  }

  /** Recomputes the non-duplicate state rows of the files matching {@code filter} (on table f0). */
  private static String recompute(String filter) {
    return "UPDATE document_import_file_states SET state=(SELECT "
        + STATE
        + INPUTS
        + " WHERE f.batch_id=document_import_file_states.batch_id AND f.ordinal=document_import_file_states.ordinal)"
        + " WHERE state<>'duplicate' AND (batch_id,ordinal) IN (SELECT f0.batch_id,f0.ordinal FROM document_import_files f0 WHERE "
        + filter
        + ");";
  }

  void migrate() {
    store.transaction(
        () -> {
          new BatchRequeueSchema(store).verify();
          store.execute(
              """
          CREATE TABLE document_import_file_states(
            batch_id TEXT NOT NULL,ordinal INTEGER NOT NULL,
            state TEXT NOT NULL CHECK(state IN ('awaiting_upload','queued','parsing','parsed','indexing','indexed','failed','cancelled','deleted','duplicate')),
            duplicate_document_id TEXT,duplicate_ordinal INTEGER CHECK(duplicate_ordinal>=0),
            PRIMARY KEY(batch_id,ordinal),
            FOREIGN KEY(batch_id,ordinal) REFERENCES document_import_files(batch_id,ordinal),
            CHECK((state='duplicate')=(duplicate_document_id IS NOT NULL OR duplicate_ordinal IS NOT NULL)))
          """);
          store.execute(
              "CREATE INDEX document_import_file_states_state ON document_import_file_states(batch_id,state)");
          store.execute(
              "CREATE INDEX document_import_files_ingestion_task ON document_import_files(ingestion_task_id)");
          store.execute(
              "CREATE INDEX document_import_files_revision ON document_import_files(revision_id)");
          store.execute(
              "CREATE INDEX import_index_requests_task ON import_index_requests(task_id)");
          store.execute(
              "CREATE INDEX batch_request_reservations_batch ON batch_request_reservations(batch_id)");
          store.execute(
              "CREATE INDEX documents_workspace_source ON documents(workspace_id,source_sha256)");
          store.execute(
              """
          CREATE TRIGGER batch_file_state_insert AFTER INSERT ON document_import_files
          BEGIN INSERT INTO document_import_file_states(batch_id,ordinal,state) VALUES(NEW.batch_id,NEW.ordinal,'awaiting_upload'); END
          """);
          store.execute(
              "CREATE TRIGGER batch_file_state_received AFTER UPDATE OF document_id,revision_id,ingestion_task_id ON document_import_files BEGIN "
                  + recompute("f0.batch_id=NEW.batch_id AND f0.ordinal=NEW.ordinal")
                  + " END");
          store.execute(
              "CREATE TRIGGER batch_file_state_parse AFTER UPDATE OF state ON ingestion_jobs"
                  + " WHEN EXISTS(SELECT 1 FROM document_import_files x WHERE x.ingestion_task_id=NEW.id) BEGIN "
                  + recompute("f0.ingestion_task_id=NEW.id")
                  + " END");
          store.execute(
              "CREATE TRIGGER batch_file_state_request_insert AFTER INSERT ON import_index_requests"
                  + " WHEN EXISTS(SELECT 1 FROM document_import_files x WHERE x.revision_id=NEW.revision_id) BEGIN "
                  + recompute("f0.revision_id=NEW.revision_id")
                  + " END");
          store.execute(
              "CREATE TRIGGER batch_file_state_request_update AFTER UPDATE OF state,task_id ON import_index_requests"
                  + " WHEN EXISTS(SELECT 1 FROM document_import_files x WHERE x.revision_id=NEW.revision_id) BEGIN "
                  + recompute("f0.revision_id=NEW.revision_id")
                  + " END");
          store.execute(
              "CREATE TRIGGER batch_file_state_index AFTER UPDATE OF state ON indexing_jobs"
                  + " WHEN EXISTS(SELECT 1 FROM import_index_requests x JOIN document_import_files y ON y.revision_id=x.revision_id WHERE x.task_id=NEW.id) BEGIN "
                  + recompute(
                      "f0.revision_id IN (SELECT x.revision_id FROM import_index_requests x WHERE x.task_id=NEW.id)")
                  + " END");
          store.execute(
              "CREATE TRIGGER batch_file_state_tombstone AFTER INSERT ON document_tombstones"
                  + " WHEN EXISTS(SELECT 1 FROM document_import_files x WHERE x.document_id=NEW.document_id) BEGIN "
                  + recompute("f0.document_id=NEW.document_id")
                  + " END");
          store.execute(
              "INSERT INTO document_import_file_states(batch_id,ordinal,state) SELECT f.batch_id,f.ordinal,"
                  + STATE
                  + INPUTS);
          for (var row :
              store.rows(
                  "SELECT name,type,sql FROM sqlite_master WHERE name IN ("
                      + String.join(",", OBJECTS.stream().map(name -> "'" + name + "'").toList())
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
    verify(VERSION);
  }

  void verify(int version) {
    new BatchRequeueSchema(store).verify(version);
    if (store.count("SELECT COUNT(*) FROM pragma_table_info('document_import_file_states')") != 5
        || store.count(
                "SELECT COUNT(*) FROM sqlite_master WHERE name IN ("
                    + String.join(",", OBJECTS.stream().map(name -> "'" + name + "'").toList())
                    + ")")
            != OBJECTS.size()
        || store.count(
                "SELECT COUNT(*) FROM document_import_files f WHERE NOT EXISTS(SELECT 1 FROM document_import_file_states s WHERE s.batch_id=f.batch_id AND s.ordinal=f.ordinal)")
            != 0) throw new IllegalStateException("Unsupported batch throughput schema");
  }
}
