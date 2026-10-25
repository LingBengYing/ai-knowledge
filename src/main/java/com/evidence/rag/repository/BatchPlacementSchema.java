package com.evidence.rag.repository;

import com.evidence.rag.model.domain.ModelValues;
import java.nio.charset.StandardCharsets;

/** v43 adds optional import placement without rewriting a source, batch, or folder. */
final class BatchPlacementSchema {
  static final int VERSION = 43;
  private final SqliteAuthorityStore store;

  BatchPlacementSchema(SqliteAuthorityStore store) {
    this.store = store;
  }

  void migrate() {
    store.transaction(
        () -> {
          new BatchStreamSchema(store).verify();
          store.execute(
              """
          CREATE TABLE document_import_batch_placement(
            batch_id TEXT PRIMARY KEY REFERENCES document_import_batches(id),
            target_folder_id TEXT,
            target_folder_name TEXT,
            preserve_paths INTEGER NOT NULL CHECK(preserve_paths IN (0,1)),
            CHECK((target_folder_id IS NULL AND target_folder_name IS NULL)
              OR (target_folder_id IS NOT NULL AND length(target_folder_name) BETWEEN 1 AND 80)))
          """);
          var row =
              store
                  .rows(
                      "SELECT type,sql FROM sqlite_master WHERE name='document_import_batch_placement'")
                  .getFirst();
          store.execute(
              "INSERT INTO cleanup_schema_objects(name,object_type,sql_sha256) VALUES(?,?,?)",
              "document_import_batch_placement",
              row.get("type"),
              ModelValues.sha256(AuthorityRows.text(row, "sql").getBytes(StandardCharsets.UTF_8)));
          store.execute("UPDATE format_info SET version=" + VERSION);
          store.execute("PRAGMA user_version=" + VERSION);
          verify();
          return null;
        });
  }

  void verify() {
    new BatchStreamSchema(store).verify(VERSION);
    if (store.count("SELECT COUNT(*) FROM pragma_table_info('document_import_batch_placement')")
        != 4) throw new IllegalStateException("Unsupported batch placement schema");
  }
}
