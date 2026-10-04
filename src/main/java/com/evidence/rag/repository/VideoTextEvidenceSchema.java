package com.evidence.rag.repository;

import com.evidence.rag.model.domain.ModelValues;
import java.nio.charset.StandardCharsets;

/** v28 preserves decoded frames while permitting an explicit caption-free video compiler. */
final class VideoTextEvidenceSchema {
  private final SqliteAuthorityStore store;

  VideoTextEvidenceSchema(SqliteAuthorityStore store) {
    this.store = store;
  }

  void migrate() {
    store.transaction(
        () -> {
          var authority = new AuthoritySchema(store);
          authority.verifyVersionTwentySeven();
          String compilation = replaceRequired(
              sql("table", "video_compilations"),
              "projection_count BETWEEN frame_count AND frame_count+span_count",
              "projection_count BETWEEN CASE WHEN compiler_revision LIKE 'java-video-compiler-v4:%' THEN 0 ELSE frame_count END AND CASE WHEN compiler_revision LIKE 'java-video-compiler-v4:%' THEN span_count ELSE frame_count+span_count END");
          compilation = insertConstraint(compilation,
              "CHECK(compiler_revision NOT LIKE 'java-video-compiler-v4:%' OR (length(compiler_revision)=87 AND substr(compiler_revision,24) NOT GLOB '*[^a-f0-9]*'))");
          rebuild("video_compilations", compilation);
          String frames = sql("table", "video_frames");
          for (String column : new String[] {"recall_text", "recall_sha256", "description_revision"}) {
            frames = replaceRequired(frames, column + " TEXT NOT NULL", column + " TEXT");
          }
          frames = insertConstraint(frames,
              "CHECK((recall_text IS NULL AND recall_sha256 IS NULL AND description_revision IS NULL) OR (recall_text IS NOT NULL AND recall_sha256 IS NOT NULL AND description_revision IS NOT NULL))");
          rebuild("video_frames", frames);
          updateVideoGuards();
          store.execute("""
              CREATE TRIGGER video_frames_recall_mode BEFORE INSERT ON video_frames
              WHEN NOT EXISTS(SELECT 1 FROM video_compilations h WHERE h.revision_id=NEW.revision_id
                AND ((h.compiler_revision LIKE 'java-video-compiler-v4:%' AND NEW.recall_text IS NULL)
                  OR (h.compiler_revision NOT LIKE 'java-video-compiler-v4:%' AND NEW.recall_text IS NOT NULL)))
              BEGIN SELECT RAISE(ABORT,'invalid video frame recall mode'); END
              """);
          store.execute("""
              CREATE TRIGGER video_frame_publication_has_recall BEFORE INSERT ON video_frame_publication_entries
              WHEN NOT EXISTS(SELECT 1 FROM video_frames WHERE id=NEW.video_frame_id AND recall_text IS NOT NULL)
              BEGIN SELECT RAISE(ABORT,'video frame has no recall projection'); END
              """);
          store.execute(
              "CREATE TRIGGER corpus_revision_video_text_complete BEFORE UPDATE OF parsed_at ON corpus_revisions"
                  + " WHEN NEW.parser_revision LIKE 'java-video-compiler-v4:%' AND NEW.parsed_at IS NOT NULL AND NOT ("
                  + authority.videoTextPreparationComplete("NEW")
                  + ") BEGIN SELECT RAISE(ABORT,'incomplete video text preparation'); END");
          store.execute(
              "CREATE TRIGGER index_publication_video_text_complete BEFORE INSERT ON index_publications"
                  + " WHEN NEW.parser_revision LIKE 'java-video-compiler-v4:%' AND NOT EXISTS(SELECT 1 FROM corpus_revisions r WHERE r.id=NEW.revision_id AND r.parsed_at IS NOT NULL AND "
                  + authority.videoTextPreparationComplete("r")
                  + ") BEGIN SELECT RAISE(ABORT,'incomplete video text publication'); END");
          refreshInventory();
          store.execute("UPDATE format_info SET version=28");
          store.execute("PRAGMA user_version=28");
          verify();
          return null;
        });
  }

  private void updateVideoGuards() {
    for (var row : store.rows("SELECT name,sql FROM sqlite_master WHERE type='trigger' ORDER BY name")) {
      String original = AuthorityRows.text(row, "sql");
      String updated = original
          .replace("GLOB 'java-video-compiler-v[123]:*'", "GLOB 'java-video-compiler-v[1234]:*'")
          .replace("GLOB 'java-video-compiler-v[23]:*'", "GLOB 'java-video-compiler-v[234]:*'")
          .replace("h.compiler_revision LIKE 'java-video-compiler-v3:%'", "h.compiler_revision GLOB 'java-video-compiler-v[34]:*'")
          .replace("(SELECT COUNT(*) FROM video_frames WHERE revision_id=r.id)",
              "(SELECT COUNT(*) FROM video_frames WHERE revision_id=r.id AND recall_text IS NOT NULL)")
          .replace("h.projection_count=h.frame_count+",
              "h.projection_count=(SELECT COUNT(*) FROM video_frames WHERE revision_id=h.revision_id AND recall_text IS NOT NULL)+")
          .replace("h.projection_count-h.frame_count",
              "h.projection_count-(SELECT COUNT(*) FROM video_frames WHERE revision_id=h.revision_id AND recall_text IS NOT NULL)");
      if (!updated.equals(original)) {
        store.execute("DROP TRIGGER " + AuthorityRows.text(row, "name"));
        store.execute(updated);
      }
    }
  }

  /** Called with foreign keys disabled and legacy rename semantics by the owning Store. */
  private void rebuild(String table, String definition) {
    var objects = store.rows(
        "SELECT sql FROM sqlite_master WHERE tbl_name=? AND type IN ('index','trigger') AND sql IS NOT NULL ORDER BY type,name", table);
    String columns = String.join(",", store.rows("SELECT name FROM pragma_table_info(?) ORDER BY cid", table)
        .stream().map(row -> AuthorityRows.text(row, "name")).toList());
    String temporary = table + "_v28";
    store.execute(definition.replaceFirst("(?i)CREATE TABLE\\s+\"?" + table + "\"?", "CREATE TABLE " + temporary));
    store.execute("INSERT INTO " + temporary + "(" + columns + ") SELECT " + columns + " FROM " + table);
    if (store.count("SELECT COUNT(*) FROM " + table) != store.count("SELECT COUNT(*) FROM " + temporary)
        || store.count("SELECT COUNT(*) FROM (SELECT " + columns + " FROM " + table + " EXCEPT SELECT " + columns + " FROM " + temporary + ")") != 0
        || store.count("SELECT COUNT(*) FROM (SELECT " + columns + " FROM " + temporary + " EXCEPT SELECT " + columns + " FROM " + table + ")") != 0) {
      throw new IllegalStateException("Video text migration changed original evidence");
    }
    store.execute("DROP TABLE " + table);
    store.execute("ALTER TABLE " + temporary + " RENAME TO " + table);
    for (var object : objects) {
      store.execute(AuthorityRows.text(object, "sql"));
    }
  }

  void verify() {
    verify(28);
  }

  void verify(int version) {
    new ModelRebuildSchema(store).verify(version);
    if (store.count("SELECT COUNT(*) FROM pragma_table_info('video_frames') WHERE name IN ('recall_text','recall_sha256','description_revision') AND \"notnull\"=0") != 3
        || store.count("SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN ('video_frames_recall_mode','video_frame_publication_has_recall','corpus_revision_video_text_complete','index_publication_video_text_complete')") != 4) {
      throw new IllegalStateException("Unsupported video text authority format");
    }
  }

  private String sql(String type, String name) {
    return AuthorityRows.text(store.rows("SELECT sql FROM sqlite_master WHERE type=? AND name=?", type, name).getFirst(), "sql");
  }

  private static String replaceRequired(String sql, String oldText, String newText) {
    if (!sql.contains(oldText)) {
      throw new IllegalStateException("Unexpected video authority format");
    }
    return sql.replace(oldText, newText);
  }

  private static String insertConstraint(String sql, String constraint) {
    int closing = sql.lastIndexOf(')');
    return sql.substring(0, closing) + "," + constraint + sql.substring(closing);
  }

  private void refreshInventory() {
    String guard = sql("trigger", "cleanup_schema_objects_no_update");
    store.execute("DROP TRIGGER cleanup_schema_objects_no_update");
    for (var row : store.rows("SELECT type,name,sql FROM sqlite_master WHERE type IN ('table','index','trigger') AND sql IS NOT NULL ORDER BY type,name")) {
      String name = AuthorityRows.text(row, "name");
      String hash = ModelValues.sha256(AuthorityRows.text(row, "sql").getBytes(StandardCharsets.UTF_8));
      if (store.count("SELECT COUNT(*) FROM cleanup_schema_objects WHERE name=?", name) == 0) {
        store.execute("INSERT INTO cleanup_schema_objects(name,object_type,sql_sha256) VALUES(?,?,?)", name, row.get("type"), hash);
      } else {
        store.execute("UPDATE cleanup_schema_objects SET object_type=?,sql_sha256=? WHERE name=?", row.get("type"), hash, name);
      }
    }
    store.execute(guard);
  }
}
