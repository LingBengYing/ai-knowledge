package com.evidence.rag.repository;

import com.evidence.rag.model.domain.ModelValues;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** v31 adds immutable Wiki revisions and reviewable proposals without rewriting source history. */
final class WikiWorkspaceSchema {
  private final SqliteAuthorityStore store;

  WikiWorkspaceSchema(SqliteAuthorityStore store) {
    this.store = store;
  }

  void migrate() {
    store.transaction(
        () -> {
          new AuthoritySchema(store).verifyVersionThirty();
          store.execute(
              """
          CREATE TABLE wiki_page_revisions(
            workspace_id TEXT NOT NULL,page_id TEXT NOT NULL,
            version INTEGER NOT NULL CHECK(typeof(version)='integer' AND version>=1),
            content_json TEXT NOT NULL CHECK(json_valid(content_json)),
            model_revision TEXT NOT NULL,policy_revision TEXT NOT NULL,
            created_at INTEGER NOT NULL CHECK(typeof(created_at)='integer' AND created_at>=0),
            PRIMARY KEY(workspace_id,page_id,version))
          """);
          store.execute(
              """
          CREATE TABLE wiki_proposals(
            workspace_id TEXT NOT NULL,id TEXT NOT NULL,page_id TEXT NOT NULL,
            base_version INTEGER NOT NULL CHECK(typeof(base_version)='integer' AND base_version>=0),
            before_json TEXT CHECK(before_json IS NULL OR json_valid(before_json)),
            after_json TEXT NOT NULL CHECK(json_valid(after_json)),
            generation_method TEXT NOT NULL CHECK(generation_method IN ('extractive','model')),
            model_revision TEXT NOT NULL,policy_revision TEXT NOT NULL,
            status TEXT NOT NULL CHECK(status IN ('pending','accepted','dismissed')),
            created_at INTEGER NOT NULL CHECK(typeof(created_at)='integer' AND created_at>=0),
            reviewed_at INTEGER CHECK(reviewed_at IS NULL OR (typeof(reviewed_at)='integer' AND reviewed_at>=created_at)),
            PRIMARY KEY(workspace_id,id),
            CHECK((base_version=0 AND before_json IS NULL) OR (base_version>0 AND before_json IS NOT NULL)),
            CHECK((status='pending' AND reviewed_at IS NULL) OR (status!='pending' AND reviewed_at IS NOT NULL)))
          """);
          store.execute(
              "CREATE INDEX wiki_pages_updated ON wiki_page_revisions(workspace_id,created_at DESC,page_id,version)");
          store.execute(
              "CREATE INDEX wiki_proposals_status ON wiki_proposals(workspace_id,status,created_at DESC,id)");
          store.execute(
              """
          CREATE TRIGGER wiki_page_revisions_contiguous BEFORE INSERT ON wiki_page_revisions
          WHEN NEW.version!=COALESCE((SELECT MAX(version)+1 FROM wiki_page_revisions WHERE workspace_id=NEW.workspace_id AND page_id=NEW.page_id),1)
          BEGIN SELECT RAISE(ABORT,'invalid wiki revision'); END
          """);
          store.execute(
              """
          CREATE TRIGGER wiki_proposals_initial BEFORE INSERT ON wiki_proposals
          WHEN NEW.status!='pending' OR EXISTS(SELECT 1 FROM wiki_proposals WHERE workspace_id=NEW.workspace_id AND id=NEW.id)
          BEGIN SELECT RAISE(ABORT,'invalid wiki proposal'); END
          """);
          store.execute(
              """
          CREATE TRIGGER wiki_proposals_review BEFORE UPDATE ON wiki_proposals
          WHEN OLD.status!='pending' OR NEW.status NOT IN ('accepted','dismissed')
            OR NEW.workspace_id IS NOT OLD.workspace_id OR NEW.id IS NOT OLD.id
            OR NEW.page_id IS NOT OLD.page_id OR NEW.base_version IS NOT OLD.base_version
            OR NEW.before_json IS NOT OLD.before_json OR NEW.after_json IS NOT OLD.after_json
            OR NEW.generation_method IS NOT OLD.generation_method OR NEW.model_revision IS NOT OLD.model_revision
            OR NEW.policy_revision IS NOT OLD.policy_revision OR NEW.created_at IS NOT OLD.created_at
          BEGIN SELECT RAISE(ABORT,'invalid wiki proposal review'); END
          """);
          for (String operation : List.of("UPDATE", "DELETE")) {
            store.execute(
                "CREATE TRIGGER wiki_page_revisions_no_"
                    + operation.toLowerCase(java.util.Locale.ROOT)
                    + " BEFORE "
                    + operation
                    + " ON wiki_page_revisions BEGIN SELECT RAISE(ABORT,'immutable wiki revision'); END");
          }
          store.execute(
              "CREATE TRIGGER wiki_proposals_no_delete BEFORE DELETE ON wiki_proposals BEGIN SELECT RAISE(ABORT,'immutable wiki proposal'); END");
          for (var row :
              store.rows(
                  "SELECT type,name,sql FROM sqlite_master WHERE type IN ('table','index','trigger') AND sql IS NOT NULL AND name LIKE 'wiki_%' ORDER BY type,name")) {
            store.execute(
                "INSERT INTO cleanup_schema_objects(name,object_type,sql_sha256) VALUES(?,?,?)",
                row.get("name"),
                row.get("type"),
                ModelValues.sha256(
                    AuthorityRows.text(row, "sql").getBytes(StandardCharsets.UTF_8)));
          }
          store.execute("UPDATE format_info SET version=31");
          store.execute("PRAGMA user_version=31");
          verify();
          return null;
        });
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
