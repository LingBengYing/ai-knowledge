package com.evidence.rag.repository;

import com.evidence.rag.model.domain.ModelValues;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** v27 keeps candidate publications invisible until one authority/configuration selection. */
final class ModelRebuildSchema {
  private final SqliteAuthorityStore store;

  ModelRebuildSchema(SqliteAuthorityStore store) {
    this.store = store;
  }

  void migrate() {
    store.transaction(() -> {
      new AuthoritySchema(store).verifyVersionTwentySix();
      store.execute("""
          CREATE TABLE text_runtime_selection(id INTEGER PRIMARY KEY CHECK(id=1),
            initialized INTEGER NOT NULL CHECK(initialized IN (0,1)),active_version INTEGER,
            configuration_sha256 TEXT,anchor_sha256 TEXT,batch_id TEXT,
            updated_at TEXT NOT NULL,
            FOREIGN KEY(batch_id) REFERENCES model_rebuilds(id) ON DELETE RESTRICT,
            CHECK((active_version IS NULL AND configuration_sha256 IS NULL AND anchor_sha256 IS NULL AND batch_id IS NULL)
              OR (initialized=1 AND typeof(active_version)='integer' AND active_version BETWEEN 1 AND 9007199254740991
                AND length(configuration_sha256)=64 AND configuration_sha256 NOT GLOB '*[^a-f0-9]*'
                AND length(anchor_sha256)=64 AND anchor_sha256 NOT GLOB '*[^a-f0-9]*')))
          """);
      store.execute("""
          CREATE TABLE model_rebuilds(id TEXT PRIMARY KEY,workspace_id TEXT NOT NULL,created_by TEXT NOT NULL,
            base_active_version INTEGER,base_configuration_sha256 TEXT,base_anchor_sha256 TEXT,
            base_batch_id TEXT,base_selected_at TEXT NOT NULL,target_version INTEGER NOT NULL,
            configuration_sha256 TEXT NOT NULL,anchor_sha256 TEXT NOT NULL,
            embedding_identity TEXT NOT NULL,projection_identity TEXT NOT NULL,model_revision TEXT NOT NULL,
            dimensions INTEGER NOT NULL CHECK(typeof(dimensions)='integer' AND dimensions BETWEEN 2 AND 8192),
            state TEXT NOT NULL CHECK(state IN ('queued','running','applying','completed','failed')),
            total_documents INTEGER NOT NULL CHECK(typeof(total_documents)='integer' AND total_documents>=0),
            error_code TEXT,created_at TEXT NOT NULL,updated_at TEXT NOT NULL,
            FOREIGN KEY(base_batch_id) REFERENCES model_rebuilds(id) ON DELETE RESTRICT,
            CHECK(typeof(target_version)='integer' AND target_version BETWEEN 1 AND 9007199254740991),
            CHECK(length(configuration_sha256)=64 AND configuration_sha256 NOT GLOB '*[^a-f0-9]*'),
            CHECK(length(anchor_sha256)=64 AND anchor_sha256 NOT GLOB '*[^a-f0-9]*'),
            CHECK((base_active_version IS NULL AND base_configuration_sha256 IS NULL AND base_anchor_sha256 IS NULL AND base_batch_id IS NULL)
              OR (typeof(base_active_version)='integer' AND base_active_version BETWEEN 1 AND 9007199254740991
                AND length(base_configuration_sha256)=64 AND base_configuration_sha256 NOT GLOB '*[^a-f0-9]*'
                AND length(base_anchor_sha256)=64 AND base_anchor_sha256 NOT GLOB '*[^a-f0-9]*')),
            CHECK((state='failed' AND error_code IS NOT NULL) OR (state!='failed' AND error_code IS NULL)))
          """);
      store.execute("CREATE UNIQUE INDEX model_rebuild_one_pending ON model_rebuilds((1)) WHERE state IN ('queued','running','applying')");
      store.execute("""
          CREATE TABLE model_rebuild_items(batch_id TEXT NOT NULL REFERENCES model_rebuilds(id) ON DELETE RESTRICT,
            ordinal INTEGER NOT NULL CHECK(typeof(ordinal)='integer' AND ordinal>=0),
            document_id TEXT NOT NULL REFERENCES corpus_documents(document_id) ON DELETE RESTRICT,
            revision_id TEXT NOT NULL,source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64 AND source_sha256 NOT GLOB '*[^a-f0-9]*'),
            parser_revision TEXT NOT NULL,base_publication_id TEXT,base_vector_set_sha256 TEXT NOT NULL CHECK(length(base_vector_set_sha256)=64 AND base_vector_set_sha256 NOT GLOB '*[^a-f0-9]*'),
            job_id TEXT NOT NULL UNIQUE,publication_id TEXT,state TEXT NOT NULL CHECK(state IN ('queued','prepared','published','failed')),
            PRIMARY KEY(batch_id,ordinal),UNIQUE(batch_id,document_id),
            FOREIGN KEY(document_id,revision_id) REFERENCES corpus_revisions(document_id,id) ON DELETE RESTRICT,
            FOREIGN KEY(base_publication_id,document_id,revision_id) REFERENCES index_publications(id,document_id,revision_id) ON DELETE RESTRICT,
            FOREIGN KEY(job_id) REFERENCES indexing_jobs(id) ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED,
            FOREIGN KEY(publication_id,document_id,revision_id) REFERENCES index_publications(id,document_id,revision_id) ON DELETE RESTRICT,
            CHECK(state NOT IN ('prepared','published') OR publication_id IS NOT NULL))
          """);
      store.execute("INSERT INTO text_runtime_selection(id,initialized,updated_at) VALUES(1,0,'1970-01-01T00:00:00Z')");
      String jobs = sql("table","indexing_jobs")
          .replace("'queued','processing','indexed','failed','cancelled'", "'queued','processing','prepared','indexed','failed','cancelled'")
          .replace("state NOT IN ('processing','indexed')", "state NOT IN ('processing','prepared','indexed')")
          .replace("CHECK(replacement_id IS NOT NULL OR ","CHECK(model_rebuild_id IS NOT NULL OR replacement_id IS NOT NULL OR ");
      jobs = insertColumn(jobs,"model_rebuild_id TEXT REFERENCES model_rebuilds(id) ON DELETE RESTRICT");
      rebuildJobs(jobs);
      store.execute("DROP INDEX indexing_one_pending");
      store.execute("CREATE UNIQUE INDEX indexing_one_pending ON indexing_jobs(document_id) WHERE state IN ('queued','processing','prepared')");
      String identity = sql("trigger","indexing_identity");
      store.execute("DROP TRIGGER indexing_identity");
      store.execute(identity.replace(" ON indexing_jobs",",model_rebuild_id ON indexing_jobs"));
      wrap("indexing_rebuild_identity","NEW.model_rebuild_id IS NULL");
      wrap("indexing_transition","OLD.model_rebuild_id IS NULL");
      store.execute("""
          CREATE TRIGGER indexing_model_rebuild_identity BEFORE INSERT ON indexing_jobs
          WHEN NEW.model_rebuild_id IS NOT NULL AND (NEW.replacement_id IS NOT NULL OR NEW.state!='queued'
            OR NEW.attempt!=1 OR NEW.rebuild_sequence!=(SELECT COALESCE(MAX(rebuild_sequence),0)+1 FROM indexing_jobs WHERE document_id=NEW.document_id)
            OR NOT EXISTS(SELECT 1 FROM model_rebuild_items i JOIN model_rebuilds b ON b.id=i.batch_id
              JOIN documents d ON d.id=i.document_id AND d.workspace_id=b.workspace_id
              JOIN corpus_documents c ON c.document_id=d.id AND c.parsed_revision_id=i.revision_id
              JOIN corpus_revisions r ON r.id=c.parsed_revision_id AND r.document_id=c.document_id AND r.parsed_at IS NOT NULL
              JOIN ingestion_jobs parsed ON parsed.document_id=d.id AND parsed.revision_id=r.id AND parsed.state='parsed'
              WHERE b.id=NEW.model_rebuild_id AND b.state='queued' AND i.state='queued' AND i.job_id=NEW.id
                AND i.document_id=NEW.document_id AND i.revision_id=NEW.revision_id
                AND r.id=d.active_revision_id AND r.source_sha256=d.source_sha256
                AND i.source_sha256=NEW.source_sha256 AND r.source_sha256=i.source_sha256
                AND i.parser_revision=NEW.parser_revision AND r.parser_revision=i.parser_revision
                AND i.base_publication_id IS NEW.base_publication_id
                AND ((i.base_publication_id IS NULL AND NEW.base_vector_set_sha256 IS NULL AND NOT EXISTS(SELECT 1 FROM active_corpus_publications WHERE document_id=d.id))
                  OR (i.base_vector_set_sha256=NEW.base_vector_set_sha256 AND EXISTS(SELECT 1 FROM active_corpus_publications a WHERE a.document_id=d.id AND a.publication_id=i.base_publication_id AND a.revision_id=r.id)))
                AND b.embedding_identity=NEW.embedding_identity AND b.projection_identity=NEW.projection_identity
                AND b.model_revision=NEW.model_revision AND b.dimensions=NEW.dimensions
                AND NOT EXISTS(SELECT 1 FROM document_tombstones WHERE document_id=d.id)))
          BEGIN SELECT RAISE(ABORT,'invalid model rebuild task'); END
          """);
      store.execute("""
          CREATE TRIGGER indexing_model_rebuild_transition BEFORE UPDATE ON indexing_jobs
          WHEN OLD.model_rebuild_id IS NOT NULL AND NOT (NEW.attempt=OLD.attempt AND (
            (OLD.state='queued' AND NEW.state IN ('processing','failed','cancelled'))
            OR (OLD.state='processing' AND NEW.state IN ('prepared','failed','cancelled'))
            OR (OLD.state='prepared' AND NEW.state='failed')
            OR (OLD.state='prepared' AND NEW.state='indexed' AND java_model_rebuild_authorized(OLD.document_id)=1)))
          BEGIN SELECT RAISE(ABORT,'invalid model rebuild transition'); END
          """);
      store.execute("""
          CREATE TRIGGER indexing_model_rebuild_prepared BEFORE UPDATE OF state ON indexing_jobs
          WHEN NEW.state='prepared' AND NOT EXISTS(SELECT 1 FROM model_rebuild_items i JOIN index_publications p ON p.id=i.publication_id
            WHERE i.job_id=NEW.id AND i.batch_id=NEW.model_rebuild_id AND i.state='prepared'
              AND p.job_id=NEW.id AND p.attempt=NEW.attempt AND p.projection_generation_id=NEW.projection_generation_id)
          BEGIN SELECT RAISE(ABORT,'unsealed model rebuild publication'); END
          """);
      preparePublicationGates();
      for (String route : List.of("image","audio")) {
        vectorBindings(route);
      }
      freezeRows();
      mutationGates();
      refreshInventory();
      store.execute("UPDATE format_info SET version=27");
      store.execute("PRAGMA user_version=27");
      verify();
      return null;
    });
  }

  private void preparePublicationGates() {
    for (String trigger : List.of("active_corpus_publication_complete","active_corpus_publication_rebuild_complete")) {
      String original = sql("trigger",trigger);
      wrap(trigger,"java_model_rebuild_authorized(NEW.document_id)=0");
      String candidate = original.replace(trigger,trigger+"_model_rebuild").replace("j.state='processing'","j.state='prepared'");
      store.execute(withCondition(candidate,"java_model_rebuild_authorized(NEW.document_id)=1"));
    }
    wrap("active_corpus_publications_no_update","java_model_rebuild_authorized(OLD.document_id)=0");
    wrap("active_corpus_publication_initial","java_model_rebuild_authorized(NEW.document_id)=0");
    String valid = """
        EXISTS(SELECT 1 FROM model_rebuild_items i JOIN model_rebuilds b ON b.id=i.batch_id
          JOIN index_publications p ON p.id=i.publication_id
          JOIN indexing_jobs j ON j.id=i.job_id AND j.model_rebuild_id=b.id AND j.state='prepared'
            AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id
          JOIN documents d ON d.id=i.document_id AND d.workspace_id=b.workspace_id
          JOIN corpus_documents c ON c.document_id=d.id AND c.parsed_revision_id=i.revision_id
          WHERE i.document_id=NEW.document_id AND i.publication_id=NEW.publication_id AND i.revision_id=NEW.revision_id
            AND b.state='applying' AND i.state='prepared' AND java_model_rebuild_batch_authorized(b.id)=1
            AND p.job_id=j.id AND p.document_id=i.document_id AND p.revision_id=i.revision_id
            AND p.source_sha256=i.source_sha256 AND d.active_revision_id=i.revision_id AND d.source_sha256=i.source_sha256
            AND p.parser_revision=i.parser_revision AND p.embedding_identity=b.embedding_identity
            AND p.projection_identity=b.projection_identity AND p.model_revision=b.model_revision AND p.dimensions=b.dimensions
            AND NOT EXISTS(SELECT 1 FROM document_tombstones WHERE document_id=d.id)
        """;
    store.execute("CREATE TRIGGER active_corpus_model_rebuild_initial BEFORE INSERT ON active_corpus_publications WHEN java_model_rebuild_authorized(NEW.document_id)=1 AND NOT ("+valid+" AND i.base_publication_id IS NULL)) BEGIN SELECT RAISE(ABORT,'invalid model rebuild initial publication'); END");
    store.execute("CREATE TRIGGER active_corpus_model_rebuild_switch BEFORE UPDATE ON active_corpus_publications WHEN java_model_rebuild_authorized(NEW.document_id)=1 AND (NEW.document_id IS NOT OLD.document_id OR NEW.revision_id IS NOT OLD.revision_id OR NOT ("+valid+" AND i.base_publication_id=OLD.publication_id))) BEGIN SELECT RAISE(ABORT,'invalid model rebuild publication switch'); END");
    store.execute("""
        CREATE TRIGGER text_runtime_selection_batch BEFORE UPDATE ON text_runtime_selection
        WHEN NEW.batch_id IS NOT NULL AND (java_model_rebuild_batch_authorized(NEW.batch_id)!=1 OR NOT EXISTS(
          SELECT 1 FROM model_rebuilds b WHERE b.id=NEW.batch_id AND b.state='applying'
            AND b.target_version=NEW.active_version AND b.configuration_sha256=NEW.configuration_sha256 AND b.anchor_sha256=NEW.anchor_sha256
            AND b.total_documents=(SELECT COUNT(*) FROM model_rebuild_items WHERE batch_id=b.id)
            AND NOT EXISTS(SELECT 1 FROM model_rebuild_items i LEFT JOIN active_corpus_publications a ON a.document_id=i.document_id
              WHERE i.batch_id=b.id AND (i.state!='published' OR a.publication_id IS NOT i.publication_id OR a.revision_id IS NOT i.revision_id))))
        BEGIN SELECT RAISE(ABORT,'incomplete model rebuild configuration switch'); END
        """);
    store.execute("CREATE TRIGGER text_runtime_selection_pending BEFORE UPDATE ON text_runtime_selection WHEN NEW.batch_id IS NULL AND EXISTS(SELECT 1 FROM model_rebuilds WHERE state IN ('queued','running','applying')) BEGIN SELECT RAISE(ABORT,'model rebuild in progress'); END");
    store.execute("CREATE TRIGGER text_runtime_selection_initialized BEFORE UPDATE ON text_runtime_selection WHEN OLD.initialized=1 AND NEW.initialized!=1 BEGIN SELECT RAISE(ABORT,'immutable runtime selection initialization'); END");
    store.execute("CREATE TRIGGER text_runtime_selection_no_delete BEFORE DELETE ON text_runtime_selection BEGIN SELECT RAISE(ABORT,'immutable runtime selection'); END");
    store.execute("CREATE TRIGGER text_runtime_selection_no_replace BEFORE INSERT ON text_runtime_selection WHEN EXISTS(SELECT 1 FROM text_runtime_selection) BEGIN SELECT RAISE(ABORT,'immutable runtime selection'); END");
  }

  private void vectorBindings(String route) {
    String table = route+"_vector_bindings";
    store.execute("ALTER TABLE "+table+" ADD COLUMN model_rebuild_id TEXT REFERENCES model_rebuilds(id) ON DELETE RESTRICT");
    String trigger = table+"_identity";
    String original = sql("trigger",trigger);
    wrap(trigger,"NEW.model_rebuild_id IS NULL");
    // Preserve all original source/evidence/receipt completeness checks; only the explicit grant
    // substitutes for the two text-target equalities, and itself remains immutable provenance.
    String inherited = original.replace(trigger,table+"_model_rebuild_identity")
        .replace("AND p.embedding_identity=prior.embedding_identity AND p.projection_identity=prior.projection_identity AND p.model_revision=prior.model_revision AND p.dimensions=prior.dimensions","")
        .replace("AND origin.embedding_identity=p.embedding_identity AND origin.projection_identity=p.projection_identity AND origin.model_revision=p.model_revision AND origin.dimensions=p.dimensions","");
    String allowed = """
        EXISTS(SELECT 1 FROM model_rebuilds grant_batch WHERE grant_batch.id=NEW.model_rebuild_id AND (
          (j.model_rebuild_id=grant_batch.id AND grant_batch.state IN ('queued','running')
            AND p.embedding_identity=grant_batch.embedding_identity AND p.projection_identity=grant_batch.projection_identity
            AND p.model_revision=grant_batch.model_revision AND p.dimensions=grant_batch.dimensions
            AND EXISTS(SELECT 1 FROM model_rebuild_items i WHERE i.batch_id=grant_batch.id AND i.job_id=j.id
              AND i.document_id=p.document_id AND i.base_publication_id=prior.id AND i.base_vector_set_sha256=j.base_vector_set_sha256))
          OR (j.model_rebuild_id IS NULL AND grant_batch.state='completed'
            AND p.embedding_identity=prior.embedding_identity AND p.projection_identity=prior.projection_identity
            AND p.model_revision=prior.model_revision AND p.dimensions=prior.dimensions
            AND EXISTS(SELECT 1 FROM %s previous WHERE previous.publication_id=prior.id
              AND previous.origin_vector_publication_id=v.id AND previous.model_rebuild_id=grant_batch.id))))
        """.formatted(table);
    inherited = inherited.replace("AND j.rebuild_sequence>0 AND j.base_vector_set_sha256 IS NOT NULL",
        "AND j.rebuild_sequence>0 AND j.base_vector_set_sha256 IS NOT NULL AND "+allowed);
    store.execute(withCondition(inherited,"NEW.model_rebuild_id IS NOT NULL"));
  }

  private void freezeRows() {
    store.execute("""
        CREATE TRIGGER model_rebuild_identity BEFORE UPDATE ON model_rebuilds WHEN
          NEW.id IS NOT OLD.id OR NEW.workspace_id IS NOT OLD.workspace_id OR NEW.created_by IS NOT OLD.created_by
          OR NEW.base_active_version IS NOT OLD.base_active_version OR NEW.base_configuration_sha256 IS NOT OLD.base_configuration_sha256
          OR NEW.base_anchor_sha256 IS NOT OLD.base_anchor_sha256 OR NEW.base_batch_id IS NOT OLD.base_batch_id OR NEW.base_selected_at IS NOT OLD.base_selected_at
          OR NEW.target_version IS NOT OLD.target_version OR NEW.configuration_sha256 IS NOT OLD.configuration_sha256 OR NEW.anchor_sha256 IS NOT OLD.anchor_sha256
          OR NEW.embedding_identity IS NOT OLD.embedding_identity OR NEW.projection_identity IS NOT OLD.projection_identity
          OR NEW.model_revision IS NOT OLD.model_revision OR NEW.dimensions IS NOT OLD.dimensions OR NEW.total_documents IS NOT OLD.total_documents OR NEW.created_at IS NOT OLD.created_at
          OR NOT ((OLD.state='queued' AND NEW.state IN ('running','applying','failed'))
            OR (OLD.state='running' AND NEW.state IN ('running','applying','failed'))
            OR (OLD.state='applying' AND NEW.state='failed')
            OR (OLD.state='applying' AND NEW.state='completed' AND java_model_rebuild_batch_authorized(OLD.id)=1))
        BEGIN SELECT RAISE(ABORT,'immutable model rebuild'); END
        """);
    store.execute("""
        CREATE TRIGGER model_rebuild_item_identity BEFORE UPDATE ON model_rebuild_items WHEN
          NEW.batch_id IS NOT OLD.batch_id OR NEW.ordinal IS NOT OLD.ordinal OR NEW.document_id IS NOT OLD.document_id
          OR NEW.revision_id IS NOT OLD.revision_id OR NEW.source_sha256 IS NOT OLD.source_sha256 OR NEW.parser_revision IS NOT OLD.parser_revision
          OR NEW.base_publication_id IS NOT OLD.base_publication_id OR NEW.base_vector_set_sha256 IS NOT OLD.base_vector_set_sha256 OR NEW.job_id IS NOT OLD.job_id
          OR NOT ((OLD.state='queued' AND NEW.state='prepared' AND OLD.publication_id IS NULL AND NEW.publication_id IS NOT NULL
              AND EXISTS(SELECT 1 FROM index_publications p JOIN indexing_jobs j ON j.id=p.job_id AND j.state='processing'
                WHERE p.id=NEW.publication_id AND p.job_id=OLD.job_id AND p.document_id=OLD.document_id AND p.revision_id=OLD.revision_id))
            OR (OLD.state IN ('queued','prepared') AND NEW.state='failed' AND NEW.publication_id IS OLD.publication_id)
            OR (OLD.state='prepared' AND NEW.state='published' AND NEW.publication_id IS OLD.publication_id
              AND java_model_rebuild_authorized(OLD.document_id)=1))
        BEGIN SELECT RAISE(ABORT,'immutable model rebuild item'); END
        """);
    for (String table : List.of("model_rebuilds","model_rebuild_items")) {
      String key = table.equals("model_rebuilds") ? "id=NEW.id" : "batch_id=NEW.batch_id AND ordinal=NEW.ordinal";
      store.execute("CREATE TRIGGER "+table+"_no_replace BEFORE INSERT ON "+table+" WHEN EXISTS(SELECT 1 FROM "+table+" WHERE "+key+") BEGIN SELECT RAISE(ABORT,'immutable model rebuild history'); END");
      store.execute("CREATE TRIGGER "+table+"_no_delete BEFORE DELETE ON "+table+" BEGIN SELECT RAISE(ABORT,'immutable model rebuild history'); END");
    }
    store.execute("""
        CREATE TRIGGER model_rebuild_item_initial BEFORE INSERT ON model_rebuild_items WHEN NEW.state!='queued' OR NEW.publication_id IS NOT NULL
          OR NOT EXISTS(SELECT 1 FROM model_rebuilds b JOIN documents d ON d.id=NEW.document_id AND d.workspace_id=b.workspace_id
            JOIN corpus_documents c ON c.document_id=d.id AND c.parsed_revision_id=NEW.revision_id
            JOIN corpus_revisions r ON r.id=c.parsed_revision_id AND r.document_id=d.id
            WHERE b.id=NEW.batch_id AND b.state='queued' AND NEW.ordinal<b.total_documents
              AND d.active_revision_id=r.id AND d.source_sha256=NEW.source_sha256 AND r.source_sha256=NEW.source_sha256
              AND r.parser_revision=NEW.parser_revision AND r.parsed_at IS NOT NULL
              AND NOT EXISTS(SELECT 1 FROM document_tombstones WHERE document_id=d.id))
        BEGIN SELECT RAISE(ABORT,'invalid model rebuild source'); END
        """);
  }

  private void mutationGates() {
    store.execute("CREATE TRIGGER model_rebuild_block_index BEFORE INSERT ON indexing_jobs WHEN NEW.model_rebuild_id IS NULL AND EXISTS(SELECT 1 FROM model_rebuilds WHERE state IN ('queued','running','applying')) BEGIN SELECT RAISE(ABORT,'model rebuild in progress'); END");
    store.execute("CREATE TRIGGER model_rebuild_block_index_retry BEFORE UPDATE OF state ON indexing_jobs WHEN OLD.model_rebuild_id IS NULL AND NEW.state='queued' AND EXISTS(SELECT 1 FROM model_rebuilds WHERE state IN ('queued','running','applying')) BEGIN SELECT RAISE(ABORT,'model rebuild in progress'); END");
    store.execute("CREATE TRIGGER model_rebuild_block_ingestion_retry BEFORE UPDATE OF state ON ingestion_jobs WHEN NEW.state='queued' AND EXISTS(SELECT 1 FROM model_rebuilds WHERE state IN ('queued','running','applying')) BEGIN SELECT RAISE(ABORT,'model rebuild in progress'); END");
    for (String table : List.of("ingestion_jobs","document_replacements","document_tombstones","document_cleanups","documents","sound_originals","video_av_originals")) {
      store.execute("CREATE TRIGGER model_rebuild_block_"+table+" BEFORE INSERT ON "+table+" WHEN EXISTS(SELECT 1 FROM model_rebuilds WHERE state IN ('queued','running','applying')) BEGIN SELECT RAISE(ABORT,'model rebuild in progress'); END");
    }
    for (String table : List.of("image_vector_publications","audio_vector_publications","sound_publications","video_av_publications")) {
      store.execute("CREATE TRIGGER model_rebuild_block_"+table+" BEFORE INSERT ON "+table+" WHEN EXISTS(SELECT 1 FROM model_rebuilds WHERE state IN ('queued','running','applying')) BEGIN SELECT RAISE(ABORT,'model rebuild in progress'); END");
    }
    store.execute("CREATE TRIGGER model_rebuild_block_source_update BEFORE UPDATE OF active_revision_id,source_sha256,document_type ON documents WHEN EXISTS(SELECT 1 FROM model_rebuilds WHERE state IN ('queued','running','applying')) BEGIN SELECT RAISE(ABORT,'model rebuild in progress'); END");
    for (String action : List.of("INSERT","UPDATE")) {
      store.execute("CREATE TRIGGER model_rebuild_block_active_"+action.toLowerCase(Locale.ROOT)+" BEFORE "+action+" ON active_corpus_publications WHEN java_model_rebuild_authorized(NEW.document_id)=0 AND EXISTS(SELECT 1 FROM model_rebuilds WHERE state IN ('queued','running','applying')) BEGIN SELECT RAISE(ABORT,'model rebuild in progress'); END");
    }
  }

  void verify() {
    if (store.count("PRAGMA user_version")!=27 || store.count("PRAGMA application_id")!=1163280711
        || store.count("SELECT COUNT(*) FROM format_info WHERE version=27 AND format='evidence-rag-java-management-v1'")!=1
        || store.count("SELECT COUNT(*) FROM pragma_foreign_key_check")!=0
        || store.count("SELECT COUNT(*) FROM pragma_table_info('indexing_jobs') WHERE name='model_rebuild_id'")!=1
        || store.count("SELECT COUNT(*) FROM text_runtime_selection WHERE id=1")!=1) {
      throw new IllegalStateException("Unsupported model rebuild authority format");
    }
    var expected = new LinkedHashMap<String,String>();
    var actual = new LinkedHashMap<String,String>();
    for (var row : store.rows("SELECT type,name,sql FROM sqlite_master WHERE type IN ('table','index','trigger') AND sql IS NOT NULL ORDER BY type,name")) {
      actual.put(row.get("type")+":"+row.get("name"),ModelValues.sha256(((String)row.get("sql")).getBytes(StandardCharsets.UTF_8)));
    }
    for (var row : store.rows("SELECT object_type,name,sql_sha256 FROM cleanup_schema_objects")) {
      expected.put(row.get("object_type")+":"+row.get("name"),(String)row.get("sql_sha256"));
    }
    if (!actual.equals(expected)) {
      throw new IllegalStateException("Changed model rebuild authority guards");
    }
    if (store.count("SELECT COUNT(*) FROM indexing_jobs j WHERE j.model_rebuild_id IS NULL AND j.replacement_id IS NULL AND ((j.rebuild_sequence=0)!=(j.base_publication_id IS NULL) OR (j.rebuild_sequence>0 AND NOT EXISTS(SELECT 1 FROM index_publications p WHERE p.id=j.base_publication_id AND p.document_id=j.document_id AND p.revision_id=j.revision_id AND p.source_sha256=j.source_sha256 AND p.parser_revision=j.parser_revision AND p.embedding_identity=j.embedding_identity AND p.projection_identity=j.projection_identity AND p.model_revision=j.model_revision AND p.dimensions=j.dimensions)))")!=0
        || store.count("SELECT COUNT(*) FROM indexing_jobs j LEFT JOIN model_rebuild_items i ON i.job_id=j.id LEFT JOIN model_rebuilds b ON b.id=j.model_rebuild_id WHERE j.model_rebuild_id IS NOT NULL AND (i.batch_id IS NOT j.model_rebuild_id OR i.document_id IS NOT j.document_id OR i.revision_id IS NOT j.revision_id OR i.base_publication_id IS NOT j.base_publication_id OR b.embedding_identity IS NOT j.embedding_identity OR b.projection_identity IS NOT j.projection_identity OR b.model_revision IS NOT j.model_revision OR b.dimensions IS NOT j.dimensions)")!=0) {
      throw new IllegalStateException("Changed model rebuild task identity");
    }
    for (var row : store.rows("SELECT DISTINCT b.publication_id,d.workspace_id FROM (SELECT publication_id FROM image_vector_bindings UNION SELECT publication_id FROM audio_vector_bindings) b JOIN index_publications p ON p.id=b.publication_id JOIN documents d ON d.id=p.document_id")) {
      String workspace = AuthorityRows.text(row,"workspace_id");
      var publication = VectorBindingRows.publication(store,workspace,AuthorityRows.text(row,"publication_id"));
      new ImageVectorRepository(store).allBindings(workspace,publication);
      new AudioVectorRepository(store).allBindings(workspace,publication);
    }
    new DocumentCleanupRepository(store).verifyPurgedRows();
  }

  private void rebuildJobs(String definition) {
    String table = "indexing_jobs";
    var objects = store.rows("SELECT type,name,sql FROM sqlite_master WHERE tbl_name=? AND type IN ('index','trigger') AND sql IS NOT NULL ORDER BY type,name",table);
    String columns = String.join(",",store.rows("SELECT name FROM pragma_table_info(?) ORDER BY cid",table).stream().map(r -> AuthorityRows.text(r,"name")).toList());
    store.execute(definition.replaceFirst("(?i)CREATE TABLE\\s+\"?indexing_jobs\"?","CREATE TABLE indexing_jobs_v27"));
    store.execute("INSERT INTO indexing_jobs_v27("+columns+") SELECT "+columns+" FROM indexing_jobs");
    if (store.count("SELECT COUNT(*) FROM (SELECT "+columns+" FROM indexing_jobs EXCEPT SELECT "+columns+" FROM indexing_jobs_v27)")!=0
        || store.count("SELECT COUNT(*) FROM (SELECT "+columns+" FROM indexing_jobs_v27 EXCEPT SELECT "+columns+" FROM indexing_jobs)")!=0) {
      throw new IllegalStateException("Model rebuild migration changed task history");
    }
    store.execute("DROP TABLE indexing_jobs");
    store.execute("ALTER TABLE indexing_jobs_v27 RENAME TO indexing_jobs");
    for (var row : objects) {
      store.execute((String)row.get("sql"));
    }
  }

  private static String insertColumn(String sql,String column) {
    int opening=sql.indexOf('(')+1;
    return sql.substring(0,opening)+column+","+sql.substring(opening);
  }

  private String sql(String type,String name) {
    return (String)store.rows("SELECT sql FROM sqlite_master WHERE type=? AND name=?",type,name).getFirst().get("sql");
  }

  private static String withCondition(String original,String condition) {
    int begin=original.toUpperCase(Locale.ROOT).indexOf("BEGIN");
    var matcher=Pattern.compile("(?i)\\bWHEN\\b").matcher(original.substring(0,begin));
    int when=matcher.find()?matcher.start():-1;
    String old=when<0?"1":original.substring(when+4,begin).strip();
    return original.substring(0,when<0?begin:when)+" WHEN ("+condition+") AND ("+old+") "+original.substring(begin);
  }

  private void wrap(String name,String condition) {
    String original=sql("trigger",name);
    store.execute("DROP TRIGGER "+name);
    store.execute(withCondition(original,condition));
  }

  private void refreshInventory() {
    String guard=sql("trigger","cleanup_schema_objects_no_update");
    store.execute("DROP TRIGGER cleanup_schema_objects_no_update");
    for (var row : store.rows("SELECT type,name,sql FROM sqlite_master WHERE type IN ('table','index','trigger') AND sql IS NOT NULL ORDER BY type,name")) {
      String name=AuthorityRows.text(row,"name");
      String hash=ModelValues.sha256(((String)row.get("sql")).getBytes(StandardCharsets.UTF_8));
      if (store.count("SELECT COUNT(*) FROM cleanup_schema_objects WHERE name=?",name)==0) {
        store.execute("INSERT INTO cleanup_schema_objects(name,object_type,sql_sha256) VALUES(?,?,?)",name,row.get("type"),hash);
      } else {
        store.execute("UPDATE cleanup_schema_objects SET object_type=?,sql_sha256=? WHERE name=?",row.get("type"),hash,name);
      }
    }
    store.execute(guard);
  }
}
