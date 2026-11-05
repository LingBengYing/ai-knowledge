package com.evidence.rag.repository;

import java.util.List;

/**
 * v40: one maintained state row per batch file, plus the lookup indexes the fair queue, the
 * duplicate check and the batch page need. Existing tables are not rewritten.
 *
 * <p>{@code document_import_file_states.state} is the same derivation the batch page used to
 * compute on every poll from four tables; triggers recompute it for exactly the affected files
 * whenever one of its inputs changes (the file row, its parsing task, its import index request, the
 * request's indexing task, or a tombstone), inside the same transaction. A {@code duplicate} file
 * is skipped at registration and never gets an input, so it stays duplicate.
 */
final class BatchThroughputSchema {
  static final int VERSION = 40;

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
