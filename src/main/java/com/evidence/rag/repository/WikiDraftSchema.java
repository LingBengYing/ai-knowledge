package com.evidence.rag.repository;

/** v32 adds editable unverified drafts; original authority and Wiki revisions stay unchanged. */
final class WikiDraftSchema {
  private final SqliteAuthorityStore store;

  WikiDraftSchema(SqliteAuthorityStore store) {
    this.store = store;
  }

  void verify() {
    verify(32);
  }

  void verify(int version) {
    new WikiWorkspaceSchema(store).verify(version);
    if (store.count(
                "SELECT COUNT(*) FROM pragma_table_info('wiki_drafts') WHERE name IN ('workspace_id','id','title','body','version','created_at','updated_at')")
            != 7
        || store.count(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name='wiki_drafts_version'")
            != 1) {
      throw new IllegalStateException("Unsupported Wiki draft authority format");
    }
  }
}
