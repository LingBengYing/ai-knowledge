package com.evidence.rag.repository;

/**
 * v37 lets a never-published document start a fresh initial index task after its latest task is
 * exhausted (three attempts or a changed index configuration). History stays immutable: the new
 * task records the exhausted one in {@code restart_of} and takes the next rebuild sequence.
 */
final class IndexRestartSchema {
  static final int VERSION = 37;
  private final SqliteAuthorityStore store;

  IndexRestartSchema(SqliteAuthorityStore store) {
    this.store = store;
  }

  void verify() {
    verify(VERSION);
  }

  void verify(int version) {
    new WikiPagePurgeSchema(store).verify(version);
    if (store.count(
                "SELECT COUNT(*) FROM pragma_table_info('knowledge_conversations') WHERE name IN ('id','workspace_id','actor_id','title','created_at','updated_at','generation','turn_count','active_run_id','summary','covered_turn_count','compression_status')")
            != 12
        || store.count(
                "SELECT COUNT(*) FROM pragma_table_info('knowledge_conversation_turns') WHERE name IN ('conversation_id','ordinal','run_id','request_id','question','status','result_json','created_at','updated_at')")
            != 9
        || store.count(
                "SELECT COUNT(*) FROM pragma_table_info('indexing_jobs') WHERE name='restart_of'")
            != 1) {
      throw new IllegalStateException("Unsupported index restart authority format");
    }
    if (store.count(
            "SELECT COUNT(*) FROM indexing_jobs j WHERE j.restart_of IS NOT NULL AND NOT EXISTS(SELECT 1 FROM indexing_jobs prior WHERE prior.id=j.restart_of AND prior.document_id=j.document_id AND prior.rebuild_sequence=j.rebuild_sequence-1 AND prior.state IN ('failed','cancelled') AND prior.base_publication_id IS NULL AND prior.replacement_id IS NULL AND prior.model_rebuild_id IS NULL)")
        != 0) {
      throw new IllegalStateException("Changed index restart identity");
    }
  }
}
