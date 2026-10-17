package com.evidence.rag.repository;

import com.evidence.rag.model.domain.ModelValues;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * v37 lets a never-published document start a fresh initial index task after its latest task is
 * exhausted (three attempts or a changed index configuration). History stays immutable: the new
 * task records the exhausted one in {@code restart_of} and takes the next rebuild sequence.
 */
final class IndexRestartSchema {
  static final int VERSION = 37;
  private static final String INITIAL_JOB =
      "j.rebuild_sequence=0 AND j.base_publication_id IS NULL";
  private static final String INITIAL_OR_RESTART_JOB =
      "(j.rebuild_sequence=0 OR j.restart_of IS NOT NULL) AND j.base_publication_id IS NULL";
  private final SqliteAuthorityStore store;

  IndexRestartSchema(SqliteAuthorityStore store) {
    this.store = store;
  }

  void migrate() {
    store.transaction(
        () -> {
          new ConversationSchema(store).verify();
          String check =
              "CHECK(model_rebuild_id IS NOT NULL OR replacement_id IS NOT NULL OR (rebuild_sequence=0";
          String jobs = sql("table", "indexing_jobs");
          String identity = sql("trigger", "indexing_identity");
          String initial = sql("trigger", "active_corpus_publication_initial");
          if (!jobs.contains(check)
              || !identity.contains(",model_rebuild_id ON indexing_jobs")
              || !initial.contains(INITIAL_JOB)) {
            throw new IllegalStateException("Unexpected indexing schema before restart migration");
          }
          jobs =
              jobs.replace(
                  check,
                  "CHECK(model_rebuild_id IS NOT NULL OR replacement_id IS NOT NULL OR restart_of IS NOT NULL OR (rebuild_sequence=0");
          int opening = jobs.indexOf('(') + 1;
          int closing = jobs.lastIndexOf(')');
          jobs =
              jobs.substring(0, opening)
                  + "restart_of TEXT CHECK(restart_of IS NULL OR (typeof(restart_of)='text' AND length(restart_of) BETWEEN 1 AND 100)),"
                  + jobs.substring(opening, closing)
                  + ",CHECK(restart_of IS NULL OR (rebuild_sequence>0 AND base_publication_id IS NULL AND base_vector_set_sha256 IS NULL AND replacement_id IS NULL AND model_rebuild_id IS NULL))"
                  + jobs.substring(closing);
          rebuildJobs(jobs);
          store.execute("DROP TRIGGER indexing_identity");
          store.execute(
              identity.replace(
                  ",model_rebuild_id ON indexing_jobs",
                  ",model_rebuild_id,restart_of ON indexing_jobs"));
          wrap("indexing_rebuild_identity", "NEW.restart_of IS NULL");
          store.execute(
              """
          CREATE TRIGGER indexing_restart_identity BEFORE INSERT ON indexing_jobs
          WHEN NEW.restart_of IS NOT NULL AND (NEW.state!='queued' OR NEW.attempt!=1
            OR NEW.rebuild_sequence!=(SELECT COALESCE(MAX(rebuild_sequence),0)+1 FROM indexing_jobs WHERE document_id=NEW.document_id)
            OR NOT EXISTS(SELECT 1 FROM indexing_jobs prior
              JOIN corpus_documents c ON c.document_id=prior.document_id
              JOIN corpus_revisions r ON r.id=c.parsed_revision_id AND r.document_id=c.document_id AND r.parsed_at IS NOT NULL
              JOIN documents d ON d.id=c.document_id AND d.active_revision_id=r.id AND d.source_sha256=r.source_sha256
              JOIN ingestion_jobs parsed ON parsed.document_id=c.document_id AND parsed.revision_id=r.id AND parsed.state='parsed'
              WHERE prior.id=NEW.restart_of AND prior.document_id=NEW.document_id
                AND prior.rebuild_sequence=NEW.rebuild_sequence-1 AND prior.state IN ('failed','cancelled')
                AND prior.base_publication_id IS NULL AND prior.replacement_id IS NULL AND prior.model_rebuild_id IS NULL
                AND r.id=NEW.revision_id AND r.source_sha256=NEW.source_sha256 AND r.parser_revision=NEW.parser_revision)
            OR EXISTS(SELECT 1 FROM active_corpus_publications WHERE document_id=NEW.document_id)
            OR EXISTS(SELECT 1 FROM document_tombstones WHERE document_id=NEW.document_id))
          BEGIN SELECT RAISE(ABORT,'invalid index restart'); END
          """);
          store.execute("DROP TRIGGER active_corpus_publication_initial");
          store.execute(initial.replace(INITIAL_JOB, INITIAL_OR_RESTART_JOB));
          refreshInventory();
          store.execute("UPDATE format_info SET version=" + VERSION);
          store.execute("PRAGMA user_version=" + VERSION);
          verify();
          return null;
        });
  }

  void verify() {
    verify(VERSION);
  }

  void verify(int version) {
    new WikiPagePurgeSchema(store).verify(version);
    if (store.count(
                "SELECT COUNT(*) FROM pragma_table_info('knowledge_conversations') WHERE name IN ('id','workspace_id','actor_id','title','created_at','updated_at','generation','turn_count','active_run_id','summary','covered_turn_count','compression_status')")
            != 12
        || store.count(
                "SELECT COUNT(*) FROM pragma_table_info('knowledge_conversation_turns') WHERE name IN ('conversation_id','ordinal','run_id','request_id','question','status','result_json','created_at','updated_at')")
            != 9
        || store.count(
                "SELECT COUNT(*) FROM pragma_table_info('indexing_jobs') WHERE name='restart_of'")
            != 1) {
      throw new IllegalStateException("Unsupported index restart authority format");
    }
    if (store.count(
            "SELECT COUNT(*) FROM indexing_jobs j WHERE j.restart_of IS NOT NULL AND NOT EXISTS(SELECT 1 FROM indexing_jobs prior WHERE prior.id=j.restart_of AND prior.document_id=j.document_id AND prior.rebuild_sequence=j.rebuild_sequence-1 AND prior.state IN ('failed','cancelled') AND prior.base_publication_id IS NULL AND prior.replacement_id IS NULL AND prior.model_rebuild_id IS NULL)")
        != 0) {
      throw new IllegalStateException("Changed index restart identity");
    }
  }

  private void rebuildJobs(String definition) {
    var objects =
        store.rows(
            "SELECT type,name,sql FROM sqlite_master WHERE tbl_name='indexing_jobs' AND type IN ('index','trigger') AND sql IS NOT NULL ORDER BY type,name");
    String columns =
        String.join(
            ",",
            store.rows("SELECT name FROM pragma_table_info('indexing_jobs') ORDER BY cid").stream()
                .map(row -> AuthorityRows.text(row, "name"))
                .toList());
    store.execute(
        definition.replaceFirst(
            "(?i)CREATE TABLE\\s+\"?indexing_jobs\"?", "CREATE TABLE indexing_jobs_v37"));
    store.execute(
        "INSERT INTO indexing_jobs_v37(" + columns + ") SELECT " + columns + " FROM indexing_jobs");
    if (store.count(
                "SELECT COUNT(*) FROM (SELECT "
                    + columns
                    + " FROM indexing_jobs EXCEPT SELECT "
                    + columns
                    + " FROM indexing_jobs_v37)")
            != 0
        || store.count(
                "SELECT COUNT(*) FROM (SELECT "
                    + columns
                    + " FROM indexing_jobs_v37 EXCEPT SELECT "
                    + columns
                    + " FROM indexing_jobs)")
            != 0
        || store.count("SELECT COUNT(*) FROM indexing_jobs")
            != store.count("SELECT COUNT(*) FROM indexing_jobs_v37")) {
      throw new IllegalStateException("Index restart migration changed task history");
    }
    store.execute("DROP TABLE indexing_jobs");
    store.execute("ALTER TABLE indexing_jobs_v37 RENAME TO indexing_jobs");
    for (var row : objects) {
      store.execute((String) row.get("sql"));
    }
  }

  private String sql(String type, String name) {
    return (String)
        store
            .rows("SELECT sql FROM sqlite_master WHERE type=? AND name=?", type, name)
            .getFirst()
            .get("sql");
  }

  private void wrap(String name, String condition) {
    String original = sql("trigger", name);
    int begin = original.toUpperCase(Locale.ROOT).indexOf("BEGIN");
    var matcher = Pattern.compile("(?i)\\bWHEN\\b").matcher(original.substring(0, begin));
    int when = matcher.find() ? matcher.start() : -1;
    String old = when < 0 ? "1" : original.substring(when + 4, begin).strip();
    store.execute("DROP TRIGGER " + name);
    store.execute(
        original.substring(0, when < 0 ? begin : when)
            + " WHEN ("
            + condition
            + ") AND ("
            + old
            + ") "
            + original.substring(begin));
  }

  private void refreshInventory() {
    String guard = sql("trigger", "cleanup_schema_objects_no_update");
    store.execute("DROP TRIGGER cleanup_schema_objects_no_update");
    for (var row :
        store.rows(
            "SELECT type,name,sql FROM sqlite_master WHERE type IN ('table','index','trigger') AND sql IS NOT NULL ORDER BY type,name")) {
      String name = AuthorityRows.text(row, "name");
      String hash = ModelValues.sha256(((String) row.get("sql")).getBytes(StandardCharsets.UTF_8));
      if (store.count("SELECT COUNT(*) FROM cleanup_schema_objects WHERE name=?", name) == 0) {
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
    store.execute(guard);
  }
}
