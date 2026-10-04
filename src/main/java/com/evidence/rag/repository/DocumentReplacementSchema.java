package com.evidence.rag.repository;

import com.evidence.rag.model.domain.ModelValues;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** v26 adds staged immutable originals while retaining current aliases and historical jobs. */
final class DocumentReplacementSchema {
  private final SqliteAuthorityStore store;

  DocumentReplacementSchema(SqliteAuthorityStore store) {
    this.store = store;
  }

  void migrate() {
    store.transaction(() -> {
      new AuthoritySchema(store).verifyVersionTwentyFive();
      store.execute("CREATE TABLE document_original_revisions(revision_id TEXT PRIMARY KEY,document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE RESTRICT,filename TEXT NOT NULL,document_type TEXT NOT NULL CHECK(document_type IN ('document','image','audio','video')),media_type TEXT NOT NULL,source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64 AND source_sha256 NOT GLOB '*[^a-f0-9]*'),size_bytes INTEGER NOT NULL CHECK(typeof(size_bytes)='integer' AND size_bytes BETWEEN 1 AND 20971520),original_blob BLOB NOT NULL,created_at TEXT NOT NULL,payload_purged INTEGER NOT NULL DEFAULT 0 CHECK(payload_purged IN (0,1)),UNIQUE(document_id,revision_id),CHECK((payload_purged=0 AND length(original_blob)=size_bytes) OR (payload_purged=1 AND length(original_blob)=0)))");
      store.execute("CREATE TABLE document_replacements(id TEXT PRIMARY KEY,document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE RESTRICT,base_revision_id TEXT NOT NULL,candidate_revision_id TEXT NOT NULL UNIQUE,base_publication_id TEXT,pipeline TEXT NOT NULL CHECK(pipeline IN ('corpus','sound','video_av')),state TEXT NOT NULL CHECK(state IN ('stored','queued','processing','parsed','indexing','published','failed','cancelled')),created_by TEXT NOT NULL,ingestion_job_id TEXT,index_job_id TEXT,publication_id TEXT,created_at TEXT NOT NULL,updated_at TEXT NOT NULL,FOREIGN KEY(document_id,base_revision_id) REFERENCES document_original_revisions(document_id,revision_id),FOREIGN KEY(document_id,candidate_revision_id) REFERENCES document_original_revisions(document_id,revision_id),CHECK(base_revision_id!=candidate_revision_id),CHECK((state='published')=(publication_id IS NOT NULL)))");
      store.execute("CREATE UNIQUE INDEX document_replacement_one_pending ON document_replacements(document_id) WHERE state IN ('stored','queued','processing','parsed','indexing')");
      store.execute("CREATE UNIQUE INDEX index_publications_document_identity ON index_publications(id,document_id)");
      String ingestion = sql("table", "ingestion_jobs")
          .replace("document_id TEXT NOT NULL UNIQUE REFERENCES corpus_documents(document_id)", "document_id TEXT NOT NULL REFERENCES corpus_documents(document_id)");
      ingestion = insertColumn(ingestion, "replacement_id TEXT REFERENCES document_replacements(id) ON DELETE RESTRICT");
      ingestion = append(ingestion, "UNIQUE(document_id,revision_id)");
      rebuild("ingestion_jobs", ingestion);
      store.execute("CREATE UNIQUE INDEX ingestion_one_pending ON ingestion_jobs(document_id) WHERE state IN ('queued','processing')");
      String indexing = sql("table", "indexing_jobs")
          .replace("FOREIGN KEY(base_publication_id,document_id,revision_id) REFERENCES index_publications(id,document_id,revision_id)", "FOREIGN KEY(base_publication_id,document_id) REFERENCES index_publications(id,document_id)")
          .replace("CHECK((rebuild_sequence=0 AND base_publication_id IS NULL) OR (rebuild_sequence>0 AND base_publication_id IS NOT NULL))", "CHECK(replacement_id IS NOT NULL OR (rebuild_sequence=0 AND base_publication_id IS NULL) OR (rebuild_sequence>0 AND base_publication_id IS NOT NULL))");
      indexing = insertColumn(indexing, "replacement_id TEXT REFERENCES document_replacements(id) ON DELETE RESTRICT");
      rebuild("indexing_jobs", indexing);
      wrapUpdate("immutable_identity", "OLD.id");
      for (String table : List.of("corpus_documents", "sound_originals", "video_av_originals")) {
        for (var row : store.rows("SELECT name FROM sqlite_master WHERE type='trigger' AND tbl_name=? AND upper(sql) LIKE '%BEFORE UPDATE%'", table)) {
          wrapUpdate(AuthorityRows.text(row, "name"), "OLD.document_id");
        }
      }
      wrapCondition("indexing_rebuild_identity", "NEW.replacement_id IS NULL");
      store.execute("CREATE TRIGGER indexing_replacement_identity BEFORE INSERT ON indexing_jobs WHEN NEW.replacement_id IS NOT NULL AND (NEW.base_vector_set_sha256 IS NOT NULL OR NEW.state!='queued' OR NEW.attempt!=1 OR NEW.rebuild_sequence!=(SELECT COALESCE(MAX(rebuild_sequence),0)+1 FROM indexing_jobs WHERE document_id=NEW.document_id) OR NOT EXISTS(SELECT 1 FROM document_replacements u JOIN document_original_revisions o ON o.revision_id=u.candidate_revision_id AND o.document_id=u.document_id JOIN corpus_revisions r ON r.id=o.revision_id AND r.document_id=o.document_id JOIN ingestion_jobs parsed ON parsed.replacement_id=u.id AND parsed.revision_id=r.id AND parsed.state='parsed' JOIN documents d ON d.id=u.document_id WHERE u.id=NEW.replacement_id AND u.pipeline='corpus' AND u.state IN ('parsed','failed','cancelled') AND d.active_revision_id=u.base_revision_id AND u.document_id=NEW.document_id AND u.candidate_revision_id=NEW.revision_id AND u.base_publication_id IS NEW.base_publication_id AND o.source_sha256=NEW.source_sha256 AND r.source_sha256=o.source_sha256 AND r.parser_revision=NEW.parser_revision AND r.parsed_at IS NOT NULL AND NOT EXISTS(SELECT 1 FROM document_tombstones WHERE document_id=d.id) AND ((u.base_publication_id IS NULL AND NOT EXISTS(SELECT 1 FROM active_corpus_publications WHERE document_id=d.id)) OR EXISTS(SELECT 1 FROM active_corpus_publications a JOIN index_publications p ON p.id=a.publication_id WHERE a.document_id=d.id AND p.id=u.base_publication_id AND p.embedding_identity=NEW.embedding_identity AND p.projection_identity=NEW.projection_identity AND p.model_revision=NEW.model_revision AND p.dimensions=NEW.dimensions)))) BEGIN SELECT RAISE(ABORT,'invalid replacement index identity'); END");
      store.execute("CREATE TRIGGER ingestion_replacement_identity BEFORE INSERT ON ingestion_jobs WHEN NEW.replacement_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM document_replacements u JOIN corpus_revisions r ON r.id=u.candidate_revision_id AND r.document_id=u.document_id JOIN document_original_revisions o ON o.revision_id=r.id AND o.document_id=r.document_id JOIN documents d ON d.id=u.document_id WHERE u.id=NEW.replacement_id AND u.pipeline='corpus' AND u.document_id=NEW.document_id AND u.candidate_revision_id=NEW.revision_id AND u.state='stored' AND d.active_revision_id=u.base_revision_id AND o.source_sha256=r.source_sha256 AND NOT EXISTS(SELECT 1 FROM document_tombstones WHERE document_id=d.id)) BEGIN SELECT RAISE(ABORT,'invalid replacement ingestion identity'); END");
      addIdentityColumn("ingestion_identity", "ingestion_jobs");
      addIdentityColumn("indexing_identity", "indexing_jobs");
      // Both old guarded CAS and old initial insertion stay strict outside the dedicated scope.
      wrapCondition("active_corpus_publications_no_update", "java_replacement_authorized(OLD.document_id)=0");
      store.execute("CREATE TRIGGER active_corpus_replacement_identity BEFORE UPDATE ON active_corpus_publications WHEN java_replacement_authorized(OLD.document_id)=1 AND NOT EXISTS(SELECT 1 FROM index_publications p JOIN indexing_jobs j ON j.id=p.job_id AND j.state='processing' AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id JOIN document_replacements u ON u.id=j.replacement_id JOIN documents d ON d.id=u.document_id WHERE p.id=NEW.publication_id AND p.document_id=NEW.document_id AND p.revision_id=NEW.revision_id AND NEW.document_id=OLD.document_id AND u.document_id=OLD.document_id AND u.base_publication_id=OLD.publication_id AND u.base_revision_id=OLD.revision_id AND u.candidate_revision_id=NEW.revision_id AND d.active_revision_id=NEW.revision_id AND d.source_sha256=p.source_sha256 AND u.state='indexing') BEGIN SELECT RAISE(ABORT,'invalid replacement publication switch'); END");
      wrapCondition("active_corpus_publication_initial", "java_replacement_authorized(NEW.document_id)=0");
      store.execute("CREATE TRIGGER active_corpus_replacement_initial BEFORE INSERT ON active_corpus_publications WHEN java_replacement_authorized(NEW.document_id)=1 AND NOT EXISTS(SELECT 1 FROM index_publications p JOIN indexing_jobs j ON j.id=p.job_id JOIN document_replacements u ON u.id=j.replacement_id WHERE p.id=NEW.publication_id AND p.document_id=NEW.document_id AND p.revision_id=NEW.revision_id AND u.base_publication_id IS NULL AND u.candidate_revision_id=NEW.revision_id AND u.state='indexing') BEGIN SELECT RAISE(ABORT,'invalid replacement initial switch'); END");
      store.execute("CREATE TRIGGER document_original_revisions_no_replace BEFORE INSERT ON document_original_revisions WHEN EXISTS(SELECT 1 FROM document_original_revisions WHERE revision_id=NEW.revision_id) BEGIN SELECT RAISE(ABORT,'immutable original revision'); END");
      store.execute("CREATE TRIGGER document_original_revisions_no_delete BEFORE DELETE ON document_original_revisions BEGIN SELECT RAISE(ABORT,'immutable original revision'); END");
      store.execute("CREATE TRIGGER document_original_revisions_no_update BEFORE UPDATE ON document_original_revisions WHEN NOT (OLD.payload_purged=0 AND NEW.payload_purged=1 AND length(NEW.original_blob)=0 AND NEW.revision_id IS OLD.revision_id AND NEW.document_id IS OLD.document_id AND NEW.filename IS OLD.filename AND NEW.document_type IS OLD.document_type AND NEW.media_type IS OLD.media_type AND NEW.source_sha256 IS OLD.source_sha256 AND NEW.size_bytes IS OLD.size_bytes AND NEW.created_at IS OLD.created_at AND java_cleanup_authorized(OLD.document_id,'document_original_revisions',hex(CAST(OLD.revision_id AS BLOB)))=1) BEGIN SELECT RAISE(ABORT,'immutable original revision'); END");
      store.execute("CREATE TRIGGER document_replacements_identity BEFORE UPDATE ON document_replacements WHEN NEW.id IS NOT OLD.id OR NEW.document_id IS NOT OLD.document_id OR NEW.base_revision_id IS NOT OLD.base_revision_id OR NEW.base_publication_id IS NOT OLD.base_publication_id OR NEW.candidate_revision_id IS NOT OLD.candidate_revision_id OR NEW.pipeline IS NOT OLD.pipeline OR NEW.created_by IS NOT OLD.created_by OR NEW.created_at IS NOT OLD.created_at OR OLD.state='published' OR (NEW.state='published' AND java_replacement_authorized(OLD.document_id)!=1) BEGIN SELECT RAISE(ABORT,'immutable replacement identity'); END");
      store.execute("CREATE TRIGGER document_replacements_no_replace BEFORE INSERT ON document_replacements WHEN EXISTS(SELECT 1 FROM document_replacements WHERE id=NEW.id OR candidate_revision_id=NEW.candidate_revision_id) BEGIN SELECT RAISE(ABORT,'immutable replacement'); END");
      store.execute("CREATE TRIGGER document_replacements_no_delete BEFORE DELETE ON document_replacements BEGIN SELECT RAISE(ABORT,'immutable replacement'); END");
      store.execute("CREATE TRIGGER cleanup_completed_original_revisions BEFORE UPDATE ON document_cleanups WHEN NEW.state='completed' AND EXISTS(SELECT 1 FROM document_original_revisions o WHERE o.document_id=NEW.document_id AND (o.payload_purged!=1 OR length(o.original_blob)!=0)) BEGIN SELECT RAISE(ABORT,'original revision payload remains'); END");
      refreshInventory();
      store.execute("UPDATE format_info SET version=26");
      store.execute("PRAGMA user_version=26");
      verify();
      return null;
    });
  }

  void verify() {
    if (store.count("PRAGMA user_version") != 26 || store.count("PRAGMA application_id") != 1163280711
        || store.count("SELECT COUNT(*) FROM format_info WHERE version=26 AND format='evidence-rag-java-management-v1'") != 1
        || store.count("SELECT COUNT(*) FROM pragma_foreign_key_check") != 0
        || store.count("SELECT COUNT(*) FROM pragma_table_info('ingestion_jobs') WHERE name='replacement_id'") != 1
        || store.count("SELECT COUNT(*) FROM pragma_table_info('indexing_jobs') WHERE name='replacement_id'") != 1) {
      throw new IllegalStateException("Unsupported replacement authority format");
    }
    var expected = new LinkedHashMap<String,String>();
    var actual = new LinkedHashMap<String,String>();
    for (var row : store.rows("SELECT type,name,sql FROM sqlite_master WHERE type IN ('table','index','trigger') AND sql IS NOT NULL ORDER BY type,name")) {
      actual.put(row.get("type")+":"+row.get("name"), ModelValues.sha256(((String)row.get("sql")).getBytes(StandardCharsets.UTF_8)));
    }
    for (var row : store.rows("SELECT object_type,name,sql_sha256 FROM cleanup_schema_objects")) {
      expected.put(row.get("object_type")+":"+row.get("name"),(String)row.get("sql_sha256"));
    }
    if (!actual.equals(expected)) {
      throw new IllegalStateException("Changed replacement authority guards");
    }
    if (store.count("SELECT COUNT(*) FROM indexing_jobs j WHERE j.replacement_id IS NULL AND ((j.rebuild_sequence=0)!=(j.base_publication_id IS NULL) OR (j.rebuild_sequence>0 AND NOT EXISTS(SELECT 1 FROM index_publications p WHERE p.id=j.base_publication_id AND p.document_id=j.document_id AND p.revision_id=j.revision_id AND p.source_sha256=j.source_sha256 AND p.parser_revision=j.parser_revision AND p.embedding_identity=j.embedding_identity AND p.projection_identity=j.projection_identity AND p.model_revision=j.model_revision AND p.dimensions=j.dimensions)))") != 0
        || store.count("SELECT COUNT(*) FROM indexing_jobs j JOIN document_replacements u ON u.id=j.replacement_id WHERE j.document_id!=u.document_id OR j.revision_id!=u.candidate_revision_id OR j.base_publication_id IS NOT u.base_publication_id OR j.base_vector_set_sha256 IS NOT NULL") != 0) {
      throw new IllegalStateException("Changed replacement task identity");
    }
    for (var row : store.rows("SELECT DISTINCT b.publication_id,d.workspace_id FROM (SELECT publication_id FROM image_vector_bindings UNION SELECT publication_id FROM audio_vector_bindings) b JOIN index_publications p ON p.id=b.publication_id JOIN documents d ON d.id=p.document_id")) {
      String workspace = AuthorityRows.text(row,"workspace_id");
      var publication = VectorBindingRows.publication(store,workspace,AuthorityRows.text(row,"publication_id"));
      new ImageVectorRepository(store).allBindings(workspace,publication);
      new AudioVectorRepository(store).allBindings(workspace,publication);
    }
    new DocumentCleanupRepository(store).verifyPurgedRows();
  }

  private void rebuild(String name, String definition) {
    var objects = store.rows("SELECT type,name,sql FROM sqlite_master WHERE tbl_name=? AND type IN ('index','trigger') AND sql IS NOT NULL ORDER BY type,name", name);
    String columns = String.join(",", store.rows("SELECT name FROM pragma_table_info(?) ORDER BY cid", name).stream().map(r -> AuthorityRows.text(r,"name")).toList());
    String replacement = definition.replaceFirst("(?i)CREATE TABLE\\s+\"?"+Pattern.quote(name)+"\"?", "CREATE TABLE "+name+"_v26");
    store.execute(replacement);
    store.execute("INSERT INTO "+name+"_v26("+columns+") SELECT "+columns+" FROM "+name);
    if (store.count("SELECT COUNT(*) FROM (SELECT "+columns+" FROM "+name+" EXCEPT SELECT "+columns+" FROM "+name+"_v26)") != 0 || store.count("SELECT COUNT(*) FROM (SELECT "+columns+" FROM "+name+"_v26 EXCEPT SELECT "+columns+" FROM "+name+")") != 0) {
      throw new IllegalStateException("Replacement migration changed task history");
    }
    store.execute("DROP TABLE "+name);
    store.execute("ALTER TABLE "+name+"_v26 RENAME TO "+name);
    for (var row : objects) {
      store.execute((String) row.get("sql"));
    }
  }

  private static String insertColumn(String sql, String column) {
    int opening = sql.indexOf('(') + 1;
    return sql.substring(0, opening) + column + "," + sql.substring(opening);
  }

  private static String append(String sql,String columns) {
    int end = sql.lastIndexOf(')');
    return sql.substring(0,end)+","+columns+sql.substring(end);
  }

  private String sql(String type,String name) {
    return (String) store.rows("SELECT sql FROM sqlite_master WHERE type=? AND name=?",type,name).getFirst().get("sql");
  }

  private void wrapUpdate(String name,String document) {
    wrapCondition(name, "java_replacement_authorized("+document+")=0");
  }

  private void wrapCondition(String name,String condition) {
    String original = sql("trigger",name);
    int begin = original.toUpperCase(Locale.ROOT).indexOf("BEGIN");
    var matcher = Pattern.compile("(?i)\\bWHEN\\b").matcher(original.substring(0,begin));
    int when = matcher.find() ? matcher.start() : -1;
    String old = when < 0 ? "1" : original.substring(when+4,begin).strip();
    store.execute("DROP TRIGGER "+name);
    store.execute(original.substring(0,when<0?begin:when)+" WHEN ("+condition+") AND ("+old+") "+original.substring(begin));
  }

  private void addIdentityColumn(String name,String table) {
    String original = sql("trigger",name);
    store.execute("DROP TRIGGER "+name);
    store.execute(original.replace(" ON "+table, ",replacement_id ON "+table));
  }

  private void refreshInventory() {
    String guard = sql("trigger","cleanup_schema_objects_no_update");
    store.execute("DROP TRIGGER cleanup_schema_objects_no_update");
    var rows = store.rows("SELECT type,name,sql FROM sqlite_master WHERE type IN ('table','index','trigger') AND sql IS NOT NULL ORDER BY type,name");
    for (var row : rows) {
      String name = AuthorityRows.text(row,"name");
      String hash = ModelValues.sha256(((String)row.get("sql")).getBytes(StandardCharsets.UTF_8));
      if (store.count("SELECT COUNT(*) FROM cleanup_schema_objects WHERE name=?",name) == 0) {
        store.execute("INSERT INTO cleanup_schema_objects(name,object_type,sql_sha256) VALUES(?,?,?)",name,row.get("type"),hash);
      } else {
        store.execute("UPDATE cleanup_schema_objects SET object_type=?,sql_sha256=? WHERE name=?",row.get("type"),hash,name);
      }
    }
    store.execute(guard);
  }
}
