package com.evidence.rag.repository;

import com.evidence.rag.model.domain.ModelValues;
import java.nio.charset.StandardCharsets;

/** v38: durable upload manifest and atomic per-attempt embedding reservations. */
final class DocumentBatchSchema {
  private final SqliteAuthorityStore store;

  DocumentBatchSchema(SqliteAuthorityStore store) {
    this.store = store;
  }

  void migrate() {
    store.transaction(
        () -> {
          new IndexRestartSchema(store).verify();
          store.execute(
              """
          CREATE TABLE document_import_batches(
            id TEXT PRIMARY KEY,workspace_id TEXT NOT NULL,actor_id TEXT NOT NULL,
            request_id TEXT NOT NULL,manifest_sha256 TEXT NOT NULL,created_at TEXT NOT NULL,
            request_limit INTEGER NOT NULL CHECK(request_limit>0),
            reserved_requests INTEGER NOT NULL DEFAULT 0 CHECK(reserved_requests>=0 AND reserved_requests<=request_limit),
            halted_reason TEXT,UNIQUE(workspace_id,actor_id,request_id))
          """);
          store.execute(
              """
          CREATE TABLE document_import_files(
            batch_id TEXT NOT NULL REFERENCES document_import_batches(id),ordinal INTEGER NOT NULL CHECK(ordinal>=0),
            filename TEXT NOT NULL,size_bytes INTEGER NOT NULL CHECK(size_bytes>0),source_sha256 TEXT NOT NULL,
            document_id TEXT UNIQUE REFERENCES documents(id),revision_id TEXT,
            ingestion_task_id TEXT REFERENCES ingestion_jobs(id),PRIMARY KEY(batch_id,ordinal))
          """);
          store.execute(
              """
          CREATE TABLE batch_request_reservations(
            job_id TEXT NOT NULL REFERENCES indexing_jobs(id),attempt INTEGER NOT NULL,
            batch_id TEXT NOT NULL REFERENCES document_import_batches(id),reserved INTEGER NOT NULL CHECK(reserved>0),
            started INTEGER,finished INTEGER,audit_complete INTEGER,
            PRIMARY KEY(job_id,attempt))
          """);
          for (var row :
              store.rows(
                  "SELECT name,type,sql FROM sqlite_master WHERE name IN ('document_import_batches','document_import_files','batch_request_reservations') AND sql IS NOT NULL")) {
            store.execute(
                "INSERT INTO cleanup_schema_objects(name,object_type,sql_sha256) VALUES(?,?,?)",
                row.get("name"),
                row.get("type"),
                ModelValues.sha256(
                    AuthorityRows.text(row, "sql").getBytes(StandardCharsets.UTF_8)));
          }
          store.execute("UPDATE format_info SET version=38");
          store.execute("PRAGMA user_version=38");
          verify();
          return null;
        });
  }

  void verify() {
    verify(38);
  }

  void verify(int version) {
    new IndexRestartSchema(store).verify(version);
    if (store.count(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('document_import_batches','document_import_files','batch_request_reservations')")
            != 3
        || store.count("SELECT COUNT(*) FROM pragma_table_info('document_import_files')") != 8
        || store.count("SELECT COUNT(*) FROM pragma_table_info('batch_request_reservations')") != 7)
      throw new IllegalStateException("Unsupported document batch schema");
  }
}
