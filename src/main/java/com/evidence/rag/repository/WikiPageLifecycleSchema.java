package com.evidence.rag.repository;

/** v33 records reversible page lifecycle events without changing original or Wiki revisions. */
final class WikiPageLifecycleSchema {
  private final SqliteAuthorityStore store;

  WikiPageLifecycleSchema(SqliteAuthorityStore store) {
    this.store = store;
  }

  void verify() {
    verify(33);
  }

  void verify(int version) {
    new WikiDraftSchema(store).verify(version);
    if (store.count(
                "SELECT COUNT(*) FROM pragma_table_info('wiki_page_lifecycle') WHERE name IN ('workspace_id','page_id','lifecycle_version','state','content_version','actor_id','created_at')")
            != 7
        || store.count(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN ('wiki_page_lifecycle_contiguous','wiki_page_lifecycle_no_update','wiki_page_lifecycle_no_delete')")
            != 3) {
      throw new IllegalStateException("Unsupported Wiki lifecycle authority format");
    }
  }
}
