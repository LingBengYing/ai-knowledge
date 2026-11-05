package com.evidence.rag.repository;

/** Durable admission intents only; migration never backfills existing originals. */
final class ImportIndexSchema {
  private final SqliteAuthorityStore store;

  ImportIndexSchema(SqliteAuthorityStore store) {
    this.store = store;
  }

  void verify() {
    verify(34);
  }

  void verify(int version) {
    new WikiPageLifecycleSchema(store).verify(version);
    if (store.count(
            "SELECT COUNT(*) FROM pragma_table_info('import_index_requests') WHERE name IN ('revision_id','document_id','workspace_id','actor_id','pipeline','replacement_id','base_revision_id','state','error_code','task_id','created_at','updated_at')")
        != 12) {
      throw new IllegalStateException("Unsupported import continuation authority format");
    }
  }
}
