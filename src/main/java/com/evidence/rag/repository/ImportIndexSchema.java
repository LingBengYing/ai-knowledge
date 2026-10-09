package com.evidence.rag.repository;

import com.evidence.rag.model.domain.ModelValues;
import java.nio.charset.StandardCharsets;

/** Durable admission intents only; migration never backfills existing originals. */
final class ImportIndexSchema {
  private final SqliteAuthorityStore store;

  ImportIndexSchema(SqliteAuthorityStore store) {
    this.store = store;
  }

  void migrate() {
    store.transaction(
        () -> {
          new WikiPageLifecycleSchema(store).verify();
          store.execute(
              """
          CREATE TABLE import_index_requests(
            revision_id TEXT PRIMARY KEY,document_id TEXT NOT NULL REFERENCES documents(id),
            workspace_id TEXT NOT NULL,actor_id TEXT NOT NULL,
            pipeline TEXT NOT NULL CHECK(pipeline IN ('corpus','sound','video_av')),
            replacement_id TEXT,base_revision_id TEXT,
            state TEXT NOT NULL CHECK(state IN ('pending','dispatching','submitted','failed')),
            error_code TEXT,task_id TEXT,created_at TEXT NOT NULL,updated_at TEXT NOT NULL,
            CHECK((replacement_id IS NULL)=(base_revision_id IS NULL)))
          """);
          for (var row :
              store.rows(
                  "SELECT type,name,sql FROM sqlite_master WHERE name='import_index_requests'")) {
            store.execute(
                "INSERT INTO cleanup_schema_objects(name,object_type,sql_sha256) VALUES(?,?,?)",
                row.get("name"),
                row.get("type"),
                ModelValues.sha256(
                    AuthorityRows.text(row, "sql").getBytes(StandardCharsets.UTF_8)));
          }
          store.execute("UPDATE format_info SET version=34");
          store.execute("PRAGMA user_version=34");
          verify();
          return null;
        });
  }

  void verify() {
    verify(34);
  }

  void verify(int version) {
    new WikiPageLifecycleSchema(store).verify(version);
    if (store.count(
            "SELECT COUNT(*) FROM pragma_table_info('import_index_requests') WHERE name IN ('revision_id','document_id','workspace_id','actor_id','pipeline','replacement_id','base_revision_id','state','error_code','task_id','created_at','updated_at')")
        != 12) {
      throw new IllegalStateException("Unsupported import continuation authority format");
    }
  }
}
