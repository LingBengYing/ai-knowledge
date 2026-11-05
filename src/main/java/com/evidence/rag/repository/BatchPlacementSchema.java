package com.evidence.rag.repository;

/** v43 adds optional import placement without rewriting a source, batch, or folder. */
final class BatchPlacementSchema {
  static final int VERSION = 43;
  private final SqliteAuthorityStore store;

  BatchPlacementSchema(SqliteAuthorityStore store) {
    this.store = store;
  }

  void verify() {
    new BatchStreamSchema(store).verify(VERSION);
    if (store.count("SELECT COUNT(*) FROM pragma_table_info('document_import_batch_placement')")
        != 4) throw new IllegalStateException("Unsupported batch placement schema");
  }
}
