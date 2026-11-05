package com.evidence.rag.repository;

/** v38: durable upload manifest and atomic per-attempt embedding reservations. */
final class DocumentBatchSchema {
  private final SqliteAuthorityStore store;

  DocumentBatchSchema(SqliteAuthorityStore store) {
    this.store = store;
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
