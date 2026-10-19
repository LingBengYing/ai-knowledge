package com.evidence.rag.repository;

import com.evidence.rag.model.domain.ModelValues;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * v42: batches whose manifest grows while files are uploaded. Existing tables are not rewritten;
 * one table is added.
 *
 * <ul>
 *   <li>{@code document_import_batch_streams}: one row per batch registered with {@code open:true}.
 *       It records how many files the browser announced ({@code expected_total}), whether the batch
 *       skips duplicates (appended entries are checked one chunk at a time) and when the manifest
 *       was sealed. A batch without a row is a one-shot manifest and behaves exactly as before.
 * </ul>
 */
final class BatchStreamSchema {
  static final int VERSION = 42;

  private static final List<String> OBJECTS = List.of("document_import_batch_streams");

  private final SqliteAuthorityStore store;

  BatchStreamSchema(SqliteAuthorityStore store) {
    this.store = store;
  }

  void migrate() {
    store.transaction(
        () -> {
          new BatchHistorySchema(store).verify(BatchHistorySchema.VERSION);
          store.execute(
              """
          CREATE TABLE document_import_batch_streams(
            batch_id TEXT PRIMARY KEY REFERENCES document_import_batches(id),
            expected_total INTEGER NOT NULL CHECK(expected_total BETWEEN 1 AND 10000),
            include_duplicates INTEGER NOT NULL CHECK(include_duplicates IN (0,1)),
            sealed_at TEXT)
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
    new BatchHistorySchema(store).verify(VERSION);
    if (store.count("SELECT COUNT(*) FROM pragma_table_info('document_import_batch_streams')") != 4
        || store.count("SELECT COUNT(*) FROM sqlite_master WHERE name IN (" + names() + ")")
            != OBJECTS.size()) throw new IllegalStateException("Unsupported batch stream schema");
  }

  private static String names() {
    return String.join(",", OBJECTS.stream().map(name -> "'" + name + "'").toList());
  }
}
