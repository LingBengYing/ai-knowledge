package com.evidence.rag.repository;

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

  void verify() {
    verify(VERSION);
  }

  void verify(int version) {
    new BatchHistorySchema(store).verify(version);
    if (store.count("SELECT COUNT(*) FROM pragma_table_info('document_import_batch_streams')") != 4
        || store.count("SELECT COUNT(*) FROM sqlite_master WHERE name IN (" + names() + ")")
            != OBJECTS.size()) throw new IllegalStateException("Unsupported batch stream schema");
  }

  private static String names() {
    return String.join(",", OBJECTS.stream().map(name -> "'" + name + "'").toList());
  }
}
