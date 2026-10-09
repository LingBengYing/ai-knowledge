package com.evidence.rag.repository;

import com.evidence.rag.model.domain.ModelValues;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** v33 records reversible page lifecycle events without changing original or Wiki revisions. */
final class WikiPageLifecycleSchema {
  private final SqliteAuthorityStore store;

  WikiPageLifecycleSchema(SqliteAuthorityStore store) {
    this.store = store;
  }

  void migrate() {
    store.transaction(
        () -> {
          new WikiDraftSchema(store).verify();
          store.execute(
              """
          CREATE TABLE wiki_page_lifecycle(
            workspace_id TEXT NOT NULL,page_id TEXT NOT NULL,
            lifecycle_version INTEGER NOT NULL CHECK(typeof(lifecycle_version)='integer' AND lifecycle_version BETWEEN 1 AND 9007199254740991),
            state TEXT NOT NULL CHECK(state IN ('active','deleted')),
            content_version INTEGER NOT NULL CHECK(typeof(content_version)='integer' AND content_version>=1),
            actor_id TEXT NOT NULL,created_at INTEGER NOT NULL CHECK(typeof(created_at)='integer' AND created_at>=0),
            PRIMARY KEY(workspace_id,page_id,lifecycle_version),
            FOREIGN KEY(workspace_id,page_id,content_version) REFERENCES wiki_page_revisions(workspace_id,page_id,version))
          """);
          store.execute(
              """
          CREATE TRIGGER wiki_page_lifecycle_contiguous BEFORE INSERT ON wiki_page_lifecycle
          WHEN NEW.lifecycle_version!=COALESCE((SELECT MAX(lifecycle_version)+1 FROM wiki_page_lifecycle WHERE workspace_id=NEW.workspace_id AND page_id=NEW.page_id),1)
            OR NEW.state=COALESCE((SELECT state FROM wiki_page_lifecycle WHERE workspace_id=NEW.workspace_id AND page_id=NEW.page_id ORDER BY lifecycle_version DESC LIMIT 1),'active')
            OR NEW.content_version!=COALESCE((SELECT MAX(version) FROM wiki_page_revisions WHERE workspace_id=NEW.workspace_id AND page_id=NEW.page_id),0)
          BEGIN SELECT RAISE(ABORT,'invalid wiki lifecycle'); END
          """);
          for (String operation : List.of("UPDATE", "DELETE")) {
            store.execute(
                "CREATE TRIGGER wiki_page_lifecycle_no_"
                    + operation.toLowerCase(java.util.Locale.ROOT)
                    + " BEFORE "
                    + operation
                    + " ON wiki_page_lifecycle BEGIN SELECT RAISE(ABORT,'immutable wiki lifecycle'); END");
          }
          for (var row :
              store.rows(
                  "SELECT type,name,sql FROM sqlite_master WHERE name LIKE 'wiki_page_lifecycle%' AND sql IS NOT NULL")) {
            store.execute(
                "INSERT INTO cleanup_schema_objects(name,object_type,sql_sha256) VALUES(?,?,?)",
                row.get("name"),
                row.get("type"),
                ModelValues.sha256(
                    AuthorityRows.text(row, "sql").getBytes(StandardCharsets.UTF_8)));
          }
          store.execute("UPDATE format_info SET version=33");
          store.execute("PRAGMA user_version=33");
          verify();
          return null;
        });
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
