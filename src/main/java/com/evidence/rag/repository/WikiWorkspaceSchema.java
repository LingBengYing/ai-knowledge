package com.evidence.rag.repository;

/** v31 adds immutable Wiki revisions and reviewable proposals without rewriting source history. */
final class WikiWorkspaceSchema {
  private final SqliteAuthorityStore store;

  WikiWorkspaceSchema(SqliteAuthorityStore store) {
    this.store = store;
  }

  void verify() {
    verify(31);
  }

  void verify(int version) {
    new VideoTextEvidenceSchema(store).verify(version);
    if (store.count(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('wiki_page_revisions','wiki_proposals')")
            != 2
        || store.count(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN ('wiki_page_revisions_contiguous','wiki_page_revisions_no_update','wiki_page_revisions_no_delete','wiki_proposals_initial','wiki_proposals_review','wiki_proposals_no_delete')")
            != 6) {
      throw new IllegalStateException("Unsupported Wiki workspace authority format");
    }
  }
}
