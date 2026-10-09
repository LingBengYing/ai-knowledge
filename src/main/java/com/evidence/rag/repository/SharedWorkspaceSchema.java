package com.evidence.rag.repository;

import com.evidence.rag.model.domain.ModelValues;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * v30 preserves immutable history while allowing full-workspace scopes and ordinary RAG citations.
 */
final class SharedWorkspaceSchema {
  private static final List<String> TRACE_TABLES =
      List.of("knowledge_answer_traces", "query_traces", "sound_traces", "video_av_traces");
  private static final List<String> DOCUMENT_TABLES =
      List.of(
          "knowledge_answer_documents",
          "query_trace_documents",
          "sound_trace_documents",
          "video_av_trace_documents");
  private final SqliteAuthorityStore store;

  SharedWorkspaceSchema(SqliteAuthorityStore store) {
    this.store = store;
  }

  void migrate() {
    store.transaction(
        () -> {
          new AuthoritySchema(store).verifyVersionTwentyNine();
          for (String table : TRACE_TABLES) {
            String definition =
                replaceRequired(
                    sql("table", table), "scope_count BETWEEN 0 AND 128", "scope_count>=0");
            if (table.equals("knowledge_answer_traces")) {
              definition =
                  replaceRequired(
                      definition, "citation_count BETWEEN 0 AND 32", "citation_count>=0");
            }
            rebuild(table, definition);
          }
          for (String table : DOCUMENT_TABLES) {
            rebuild(
                table,
                replaceRequired(sql("table", table), "ordinal BETWEEN 0 AND 127", "ordinal>=0"));
          }
          String citations =
              replaceRequired(
                  sql("table", "knowledge_answer_citations"),
                  "ordinal BETWEEN 1 AND 32",
                  "ordinal>=1");
          citations =
              replaceRequired(
                  citations,
                  "end_offset>start_offset AND end_offset-start_offset<=1200",
                  "end_offset>start_offset");
          rebuild("knowledge_answer_citations", citations);
          for (String name : List.of("sound_traces_complete", "video_av_traces_complete")) {
            String definition =
                replaceRequired(
                    sql("trigger", name), "JOIN document_acl a ON a.document_id=d.id", "");
            definition =
                replaceRequired(
                    definition,
                    "AND a.principal_id=NEW.actor_id AND a.role IN ('owner','editor','reader')",
                    "");
            store.execute("DROP TRIGGER " + name);
            store.execute(definition);
          }
          if (store.count("SELECT COUNT(*) FROM pragma_foreign_key_check") != 0) {
            throw new IllegalStateException("Shared workspace migration changed foreign keys");
          }
          refreshInventory();
          store.execute("UPDATE format_info SET version=30");
          store.execute("PRAGMA user_version=30");
          verify();
          return null;
        });
  }

  /** The Store disables foreign keys and enables legacy rename for this atomic physical rebuild. */
  private void rebuild(String table, String definition) {
    var objects =
        store.rows(
            "SELECT sql FROM sqlite_master WHERE tbl_name=? AND type IN ('index','trigger') AND sql IS NOT NULL ORDER BY type,name",
            table);
    String columns =
        String.join(
            ",",
            store.rows("SELECT name FROM pragma_table_info(?) ORDER BY cid", table).stream()
                .map(row -> AuthorityRows.text(row, "name"))
                .toList());
    String temporary = table + "_v30";
    store.execute(
        definition.replaceFirst(
            "(?i)CREATE TABLE\\s+\"?" + table + "\"?", "CREATE TABLE " + temporary));
    store.execute(
        "INSERT INTO " + temporary + "(" + columns + ") SELECT " + columns + " FROM " + table);
    if (store.count("SELECT COUNT(*) FROM " + table)
            != store.count("SELECT COUNT(*) FROM " + temporary)
        || store.count(
                "SELECT COUNT(*) FROM (SELECT "
                    + columns
                    + " FROM "
                    + table
                    + " EXCEPT SELECT "
                    + columns
                    + " FROM "
                    + temporary
                    + ")")
            != 0
        || store.count(
                "SELECT COUNT(*) FROM (SELECT "
                    + columns
                    + " FROM "
                    + temporary
                    + " EXCEPT SELECT "
                    + columns
                    + " FROM "
                    + table
                    + ")")
            != 0) {
      throw new IllegalStateException("Shared workspace migration changed trace history");
    }
    store.execute("DROP TABLE " + table);
    store.execute("ALTER TABLE " + temporary + " RENAME TO " + table);
    for (var object : objects) {
      store.execute(AuthorityRows.text(object, "sql"));
    }
  }

  void verify() {
    new VideoTextEvidenceSchema(store).verify(30);
    if (store.count(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('knowledge_answer_traces','knowledge_answer_documents','knowledge_answer_citations')")
        != 3) {
      throw new IllegalStateException("Unsupported shared workspace authority format");
    }
  }

  private String sql(String type, String name) {
    return AuthorityRows.text(
        store.rows("SELECT sql FROM sqlite_master WHERE type=? AND name=?", type, name).getFirst(),
        "sql");
  }

  private static String replaceRequired(String definition, String before, String after) {
    if (!definition.contains(before)) {
      throw new IllegalStateException("Unexpected shared workspace migration input");
    }
    return definition.replace(before, after);
  }

  private void refreshInventory() {
    String guard = sql("trigger", "cleanup_schema_objects_no_update");
    store.execute("DROP TRIGGER cleanup_schema_objects_no_update");
    for (var row :
        store.rows(
            "SELECT type,name,sql FROM sqlite_master WHERE type IN ('table','index','trigger') AND sql IS NOT NULL ORDER BY type,name")) {
      String name = AuthorityRows.text(row, "name");
      String hash =
          ModelValues.sha256(AuthorityRows.text(row, "sql").getBytes(StandardCharsets.UTF_8));
      store.execute(
          "UPDATE cleanup_schema_objects SET object_type=?,sql_sha256=? WHERE name=?",
          row.get("type"),
          hash,
          name);
    }
    store.execute(guard);
  }
}
