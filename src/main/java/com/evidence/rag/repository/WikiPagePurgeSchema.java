package com.evidence.rag.repository;

import com.evidence.rag.model.domain.ModelValues;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** v35 permits one explicitly selected deleted Wiki page to be purged, with content-free audit. */
final class WikiPagePurgeSchema {
  private final SqliteAuthorityStore store;

  WikiPagePurgeSchema(SqliteAuthorityStore store) {
    this.store = store;
  }

  void migrate() {
    store.transaction(
        () -> {
          new ImportIndexSchema(store).verify();
          store.execute(
              """
              CREATE TABLE wiki_page_purges(
                workspace_id TEXT NOT NULL,page_id TEXT NOT NULL,
                content_version INTEGER NOT NULL CHECK(typeof(content_version)='integer' AND content_version>=1),
                lifecycle_version INTEGER NOT NULL CHECK(typeof(lifecycle_version)='integer' AND lifecycle_version>=1),
                actor_id TEXT NOT NULL,created_at INTEGER NOT NULL CHECK(typeof(created_at)='integer' AND created_at>=0),
                PRIMARY KEY(workspace_id,page_id))
              """);
          store.execute(
              """
              CREATE TRIGGER wiki_page_purges_authorized BEFORE INSERT ON wiki_page_purges
              WHEN java_wiki_page_purge_authorized(NEW.workspace_id,NEW.page_id)!=1
                OR NEW.content_version IS NOT (SELECT MAX(version) FROM wiki_page_revisions WHERE workspace_id=NEW.workspace_id AND page_id=NEW.page_id)
                OR NEW.lifecycle_version IS NOT (SELECT MAX(lifecycle_version) FROM wiki_page_lifecycle WHERE workspace_id=NEW.workspace_id AND page_id=NEW.page_id)
                OR 'deleted' IS NOT (SELECT state FROM wiki_page_lifecycle WHERE workspace_id=NEW.workspace_id AND page_id=NEW.page_id ORDER BY lifecycle_version DESC LIMIT 1)
              BEGIN SELECT RAISE(ABORT,'invalid wiki purge'); END
              """);
          for (String operation : List.of("UPDATE", "DELETE")) {
            store.execute(
                "CREATE TRIGGER wiki_page_purges_no_"
                    + operation.toLowerCase(java.util.Locale.ROOT)
                    + " BEFORE "
                    + operation
                    + " ON wiki_page_purges BEGIN SELECT RAISE(ABORT,'immutable wiki purge'); END");
          }
          for (String table :
              List.of("wiki_page_revisions", "wiki_proposals", "wiki_page_lifecycle")) {
            store.execute("DROP TRIGGER " + table + "_no_delete");
            store.execute(
                "CREATE TRIGGER "
                    + table
                    + "_no_delete BEFORE DELETE ON "
                    + table
                    + " WHEN java_wiki_page_purge_authorized(OLD.workspace_id,OLD.page_id)!=1"
                    + " OR NOT EXISTS(SELECT 1 FROM wiki_page_purges p WHERE p.workspace_id=OLD.workspace_id AND p.page_id=OLD.page_id)"
                    + " BEGIN SELECT RAISE(ABORT,'immutable wiki content'); END");
          }
          for (String table : List.of("wiki_page_revisions", "wiki_proposals")) {
            store.execute(
                "CREATE TRIGGER "
                    + table
                    + "_purged BEFORE INSERT ON "
                    + table
                    + " WHEN EXISTS(SELECT 1 FROM wiki_page_purges p WHERE p.workspace_id=NEW.workspace_id AND p.page_id=NEW.page_id)"
                    + " BEGIN SELECT RAISE(ABORT,'purged wiki page'); END");
          }
          // The inventory is immutable during normal operation. Refresh only these migration
          // objects inside this transaction, then restore its exact original guard before commit.
          String inventoryGuard =
              AuthorityRows.text(
                  store
                      .rows(
                          "SELECT sql FROM sqlite_master WHERE type='trigger' AND name='cleanup_schema_objects_no_update'")
                      .getFirst(),
                  "sql");
          store.execute("DROP TRIGGER cleanup_schema_objects_no_update");
          for (var row :
              store.rows(
                  "SELECT type,name,sql FROM sqlite_master WHERE name LIKE 'wiki_page_purges%' OR name IN ('wiki_page_revisions_no_delete','wiki_proposals_no_delete','wiki_page_lifecycle_no_delete','wiki_page_revisions_purged','wiki_proposals_purged')")) {
            String name = AuthorityRows.text(row, "name");
            String hash =
                ModelValues.sha256(AuthorityRows.text(row, "sql").getBytes(StandardCharsets.UTF_8));
            if (store.count("SELECT COUNT(*) FROM cleanup_schema_objects WHERE name=?", name)
                == 0) {
              store.execute(
                  "INSERT INTO cleanup_schema_objects(name,object_type,sql_sha256) VALUES(?,?,?)",
                  name,
                  row.get("type"),
                  hash);
            } else {
              store.execute(
                  "UPDATE cleanup_schema_objects SET object_type=?,sql_sha256=? WHERE name=?",
                  row.get("type"),
                  hash,
                  name);
            }
          }
          store.execute(inventoryGuard);
          store.execute("UPDATE format_info SET version=35");
          store.execute("PRAGMA user_version=35");
          verify();
          return null;
        });
  }

  void verify() {
    new ImportIndexSchema(store).verify(35);
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
