package com.evidence.rag.repository;

import com.evidence.rag.model.domain.ModelValues;
import java.nio.charset.StandardCharsets;

/** v32 adds editable unverified drafts; original authority and Wiki revisions stay unchanged. */
final class WikiDraftSchema {
  private final SqliteAuthorityStore store;

  WikiDraftSchema(SqliteAuthorityStore store) {
    this.store = store;
  }

  void migrate() {
    store.transaction(
        () -> {
          new WikiWorkspaceSchema(store).verify();
          store.execute(
              """
          CREATE TABLE wiki_drafts(
            workspace_id TEXT NOT NULL,id TEXT NOT NULL,title TEXT NOT NULL,body TEXT NOT NULL,
            version INTEGER NOT NULL CHECK(typeof(version)='integer' AND version>=1),
            created_at INTEGER NOT NULL CHECK(typeof(created_at)='integer' AND created_at>=0),
            updated_at INTEGER NOT NULL CHECK(typeof(updated_at)='integer' AND updated_at>=created_at),
            PRIMARY KEY(workspace_id,id))
          """);
          store.execute(
              "CREATE INDEX wiki_drafts_updated ON wiki_drafts(workspace_id,updated_at DESC,id)");
          store.execute(
              """
          CREATE TRIGGER wiki_drafts_version BEFORE UPDATE ON wiki_drafts
          WHEN NEW.workspace_id IS NOT OLD.workspace_id OR NEW.id IS NOT OLD.id
            OR NEW.created_at IS NOT OLD.created_at OR NEW.version!=OLD.version+1
            OR NEW.updated_at<OLD.updated_at
          BEGIN SELECT RAISE(ABORT,'invalid wiki draft version'); END
          """);
          for (var row :
              store.rows(
                  "SELECT type,name,sql FROM sqlite_master WHERE name IN ('wiki_drafts','wiki_drafts_updated','wiki_drafts_version') AND sql IS NOT NULL")) {
            store.execute(
                "INSERT INTO cleanup_schema_objects(name,object_type,sql_sha256) VALUES(?,?,?)",
                row.get("name"),
                row.get("type"),
                ModelValues.sha256(
                    AuthorityRows.text(row, "sql").getBytes(StandardCharsets.UTF_8)));
          }
          store.execute("UPDATE format_info SET version=32");
          store.execute("PRAGMA user_version=32");
          verify();
          return null;
        });
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
