package com.evidence.rag.repository;

/** v35 permits one explicitly selected deleted Wiki page to be purged, with content-free audit. */
final class WikiPagePurgeSchema {
  private final SqliteAuthorityStore store;

  WikiPagePurgeSchema(SqliteAuthorityStore store) {
    this.store = store;
  }

  void verify() {
    verify(35);
  }

  void verify(int version) {
    new ImportIndexSchema(store).verify(version);
    if (store.count(
                "SELECT COUNT(*) FROM pragma_table_info('wiki_page_purges') WHERE name IN ('workspace_id','page_id','content_version','lifecycle_version','actor_id','created_at')")
            != 6
        || store.count(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN ('wiki_page_purges_authorized','wiki_page_purges_no_update','wiki_page_purges_no_delete','wiki_page_revisions_purged','wiki_proposals_purged')")
            != 5) {
      throw new IllegalStateException("Unsupported Wiki purge authority format");
    }
  }
}
