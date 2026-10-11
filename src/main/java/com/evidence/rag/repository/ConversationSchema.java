package com.evidence.rag.repository;

import com.evidence.rag.model.domain.ModelValues;
import java.nio.charset.StandardCharsets;

/** v36 adds actor-owned dialogue storage without rewriting documents or retrieval indexes. */
final class ConversationSchema {
  private final SqliteAuthorityStore store;

  ConversationSchema(SqliteAuthorityStore store) {
    this.store = store;
  }

  void migrate() {
    store.transaction(
        () -> {
          new WikiPagePurgeSchema(store).verify();
          store.execute(
              """
          CREATE TABLE knowledge_conversations(
            id TEXT PRIMARY KEY,workspace_id TEXT NOT NULL,actor_id TEXT NOT NULL,
            title TEXT NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,
            generation INTEGER NOT NULL DEFAULT 0,turn_count INTEGER NOT NULL DEFAULT 0,
            active_run_id TEXT,summary TEXT NOT NULL DEFAULT '',covered_turn_count INTEGER NOT NULL DEFAULT 0,
            compression_status TEXT NOT NULL DEFAULT 'idle'
              CHECK(compression_status IN ('idle','compressing','compressed','failed')))
          """);
          store.execute(
              """
          CREATE TABLE knowledge_conversation_turns(
            conversation_id TEXT NOT NULL REFERENCES knowledge_conversations(id),
            ordinal INTEGER NOT NULL,run_id TEXT NOT NULL UNIQUE,request_id TEXT NOT NULL,
            question TEXT NOT NULL,status TEXT NOT NULL CHECK(status IN ('running','completed','failed','cancelled')),
            result_json TEXT,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,
            PRIMARY KEY(conversation_id,ordinal),UNIQUE(conversation_id,request_id))
          """);
          store.execute(
              "CREATE INDEX knowledge_conversations_owner ON knowledge_conversations(workspace_id,actor_id,updated_at DESC)");
          for (var row :
              store.rows(
                  "SELECT name,type,sql FROM sqlite_master WHERE name LIKE 'knowledge_conversation%' AND sql IS NOT NULL")) {
            store.execute(
                "INSERT INTO cleanup_schema_objects(name,object_type,sql_sha256) VALUES(?,?,?)",
                row.get("name"),
                row.get("type"),
                ModelValues.sha256(
                    AuthorityRows.text(row, "sql").getBytes(StandardCharsets.UTF_8)));
          }
          store.execute("UPDATE format_info SET version=36");
          store.execute("PRAGMA user_version=36");
          verify();
          return null;
        });
  }

  void verify() {
    new WikiPagePurgeSchema(store).verify(36);
    if (store.count(
                "SELECT COUNT(*) FROM pragma_table_info('knowledge_conversations') WHERE name IN ('id','workspace_id','actor_id','title','created_at','updated_at','generation','turn_count','active_run_id','summary','covered_turn_count','compression_status')")
            != 12
        || store.count(
                "SELECT COUNT(*) FROM pragma_table_info('knowledge_conversation_turns') WHERE name IN ('conversation_id','ordinal','run_id','request_id','question','status','result_json','created_at','updated_at')")
            != 9) {
      throw new IllegalStateException("Unsupported conversation authority format");
    }
  }
}
