package com.evidence.rag.repository;

/** v28 preserves decoded frames while permitting an explicit caption-free video compiler. */
final class VideoTextEvidenceSchema {
  private final SqliteAuthorityStore store;

  VideoTextEvidenceSchema(SqliteAuthorityStore store) {
    this.store = store;
  }

  void verify() {
    verify(28);
  }

  void verify(int version) {
    new ModelRebuildSchema(store).verify(version);
    if (store.count(
                "SELECT COUNT(*) FROM pragma_table_info('video_frames') WHERE name IN ('recall_text','recall_sha256','description_revision') AND \"notnull\"=0")
            != 3
        || store.count(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN ('video_frames_recall_mode','video_frame_publication_has_recall','corpus_revision_video_text_complete','index_publication_video_text_complete')")
            != 4) {
      throw new IllegalStateException("Unsupported video text authority format");
    }
  }
}
