package com.evidence.rag.repository;

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

  void verify() {
    verify(VERSION);
  }

  void verify(int version) {
    new BatchThroughputSchema(store).verify(version);
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
