package com.evidence.rag.repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Supplier;

/** Versioned SQLite format and migrations; no business recovery or remote processing. */
final class AuthoritySchema {
  private static final String FORMAT = "evidence-rag-java-management-v1";
  private final SqliteAuthorityStore store;

  AuthoritySchema(SqliteAuthorityStore store) {
    this.store = store;
  }

  private void execute(String sql, Object... args) {
    store.execute(sql, args);
  }

  private long count(String sql, Object... args) {
    return store.count(sql, args);
  }

  private <T> T transaction(Supplier<T> work) {
    return store.transaction(work);
  }

  void verifyFormat() {
    long version = count("PRAGMA user_version");
    if (count("PRAGMA application_id") != 1163280711
        || (version != 1 && version != 2 && version != 3 && version != 4 && version != 5)
        || count("SELECT COUNT(*) FROM format_info WHERE format=? AND version=?", FORMAT, version)
            != 1) {
      throw new IllegalStateException("Unsupported Java database format");
    }
    if (version >= 3
        && (count(
                    "SELECT COUNT(*) FROM pragma_table_info('indexing_jobs') WHERE name='projection_generation_id'")
                != 1
            || count(
                    "SELECT COUNT(*) FROM pragma_table_info('index_publications') WHERE name='projection_generation_id'")
                != 1
            || count(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('indexing_attempts','index_publication_entries')")
                != 2)) {
      // Pre-generation v3 was never a released format; do not silently reuse its projections.
      throw new IllegalStateException("Unsupported Java indexing generation schema");
    }
    if (version >= 4
        && (count(
                    "SELECT COUNT(*) FROM pragma_table_info('query_traces') WHERE name IN ('id','workspace_id','actor_id','selection_all','scope_count','citation_count','question_sha256','answer_sha256','outcome','reason_code','model_revision','prompt_revision','policy_revision','created_at')")
                != 14
            || count(
                    "SELECT COUNT(*) FROM pragma_table_info('query_trace_documents') WHERE name IN ('trace_id','ordinal','publication_id')")
                != 3
            || count(
                    "SELECT COUNT(*) FROM pragma_table_info('query_trace_evidence') WHERE name IN ('trace_id','citation_ordinal','publication_id','source_segment_id','physical_segment_id','page_number','start_offset','end_offset','text_sha256','page_sha256','quote_sha256','retrieval_score','rerank_score','fact_sha256')")
                != 14
            || count(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN ('query_traces_complete','query_traces_no_replace','query_trace_documents_sealed','query_trace_evidence_sealed','query_trace_evidence_identity','query_traces_no_update','query_traces_no_delete','query_trace_documents_no_update','query_trace_documents_no_delete','query_trace_evidence_no_update','query_trace_evidence_no_delete')")
                != 11)) {
      throw new IllegalStateException("Unsupported Java query trace schema");
    }
    if (version == 5
        && (count(
                    "SELECT COUNT(*) FROM pragma_table_info('document_tombstones') WHERE name IN ('document_id','workspace_id','requested_by','requested_at')")
                != 4
            || count(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN ('document_tombstones_identity','document_tombstones_no_replace','document_tombstones_no_update','document_tombstones_no_delete')")
                != 4)) {
      throw new IllegalStateException("Unsupported Java document removal schema");
    }
  }

  void backupVersionOne(Path directory) throws IOException, SQLException {
    backupVersion(directory, 1, 2);
  }

  void backupVersion(Path directory, int previous, int next) throws IOException, SQLException {
    Path partial =
        Files.createTempFile(
            directory, "java-library.v" + previous + "-before-v" + next + "-", ".partial");
    store.backupSnapshot(partial);
    // A .db suffix denotes a completed consistent snapshot, never a partial VACUUM output.
    Path complete =
        partial.resolveSibling(partial.getFileName().toString().replace(".partial", ".db"));
    Files.move(partial, complete, StandardCopyOption.ATOMIC_MOVE);
  }

  void migrateVersionFive() {
    transaction(
        () -> {
          execute(
              """
              CREATE TABLE document_tombstones(
                document_id TEXT PRIMARY KEY NOT NULL REFERENCES documents(id) ON DELETE RESTRICT,
                workspace_id TEXT NOT NULL CHECK(length(workspace_id)>0),
                requested_by TEXT NOT NULL CHECK(length(requested_by)>0),
                requested_at TEXT NOT NULL CHECK(length(requested_at)>0))
              """);
          execute(
              """
              CREATE TRIGGER document_tombstones_identity BEFORE INSERT ON document_tombstones
              WHEN NOT EXISTS(SELECT 1 FROM documents d WHERE d.id=NEW.document_id AND d.workspace_id=NEW.workspace_id)
              BEGIN SELECT RAISE(ABORT,'invalid removal identity'); END
              """);
          execute(
              """
              CREATE TRIGGER document_tombstones_no_replace BEFORE INSERT ON document_tombstones
              WHEN EXISTS(SELECT 1 FROM document_tombstones WHERE document_id=NEW.document_id)
              BEGIN SELECT RAISE(ABORT,'immutable removal request'); END
              """);
          execute(
              """
              CREATE TRIGGER document_tombstones_no_update BEFORE UPDATE ON document_tombstones
              BEGIN SELECT RAISE(ABORT,'immutable removal request'); END
              """);
          execute(
              """
              CREATE TRIGGER document_tombstones_no_delete BEFORE DELETE ON document_tombstones
              BEGIN SELECT RAISE(ABORT,'immutable removal request'); END
              """);
          execute("UPDATE format_info SET version=5 WHERE format=?", FORMAT);
          execute("PRAGMA user_version=5");
          return null;
        });
  }

  void migrateVersionFour() {
    transaction(
        () -> {
          execute(
              """
          CREATE TABLE query_traces(
            id TEXT PRIMARY KEY NOT NULL,
            workspace_id TEXT NOT NULL,actor_id TEXT NOT NULL,
            selection_all INTEGER NOT NULL CHECK(selection_all IN (0,1)),
            scope_count INTEGER NOT NULL CHECK(scope_count BETWEEN 0 AND 128),
            citation_count INTEGER NOT NULL CHECK(citation_count BETWEEN 0 AND 32),
            question_sha256 TEXT NOT NULL CHECK(length(question_sha256)=64 AND question_sha256 NOT GLOB '*[^a-f0-9]*'),
            answer_sha256 TEXT CHECK(answer_sha256 IS NULL OR (length(answer_sha256)=64 AND answer_sha256 NOT GLOB '*[^a-f0-9]*')),
            outcome TEXT NOT NULL CHECK(outcome IN ('answered','abstained')),
            reason_code TEXT,model_revision TEXT NOT NULL,prompt_revision TEXT NOT NULL,
            policy_revision TEXT NOT NULL,created_at TEXT NOT NULL,
            CHECK((outcome='answered' AND answer_sha256 IS NOT NULL AND reason_code IS NULL AND citation_count>0)
              OR (outcome='abstained' AND answer_sha256 IS NULL AND reason_code IS NOT NULL AND length(reason_code) BETWEEN 1 AND 64 AND citation_count=0)))
          """);
          execute(
              """
          CREATE TABLE query_trace_documents(
            trace_id TEXT NOT NULL REFERENCES query_traces(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
            ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 127),
            publication_id TEXT NOT NULL REFERENCES index_publications(id) ON DELETE RESTRICT,
            PRIMARY KEY(trace_id,ordinal),UNIQUE(trace_id,publication_id))
          """);
          execute(
              """
          CREATE TABLE query_trace_evidence(
            trace_id TEXT NOT NULL REFERENCES query_traces(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
            citation_ordinal INTEGER NOT NULL CHECK(citation_ordinal BETWEEN 1 AND 32),
            publication_id TEXT NOT NULL,source_segment_id TEXT NOT NULL,physical_segment_id TEXT NOT NULL,
            page_number INTEGER NOT NULL CHECK(page_number BETWEEN 1 AND 500),
            start_offset INTEGER NOT NULL CHECK(start_offset>=0),
            end_offset INTEGER NOT NULL CHECK(end_offset>start_offset AND end_offset-start_offset<=1200),
            text_sha256 TEXT NOT NULL CHECK(length(text_sha256)=64 AND text_sha256 NOT GLOB '*[^a-f0-9]*'),
            page_sha256 TEXT NOT NULL CHECK(length(page_sha256)=64 AND page_sha256 NOT GLOB '*[^a-f0-9]*'),
            quote_sha256 TEXT NOT NULL CHECK(length(quote_sha256)=64 AND quote_sha256 NOT GLOB '*[^a-f0-9]*'),
            retrieval_score REAL NOT NULL CHECK(retrieval_score BETWEEN -1.7976931348623157e308 AND 1.7976931348623157e308),
            rerank_score REAL NOT NULL CHECK(rerank_score BETWEEN -1.7976931348623157e308 AND 1.7976931348623157e308),
            fact_sha256 TEXT NOT NULL CHECK(length(fact_sha256) BETWEEN 64 AND 519 AND fact_sha256 NOT GLOB '*[^a-f0-9,]*'),
            PRIMARY KEY(trace_id,citation_ordinal),
            FOREIGN KEY(trace_id,publication_id) REFERENCES query_trace_documents(trace_id,publication_id) ON DELETE RESTRICT,
            FOREIGN KEY(publication_id,source_segment_id) REFERENCES index_publication_entries(publication_id,source_segment_id) ON DELETE RESTRICT)
          """);
          execute(
              "CREATE INDEX query_traces_actor ON query_traces(workspace_id,actor_id,created_at,id)");
          execute(
              """
          CREATE TRIGGER query_traces_no_replace BEFORE INSERT ON query_traces
          WHEN EXISTS(SELECT 1 FROM query_traces WHERE id=NEW.id)
          BEGIN SELECT RAISE(ABORT,'immutable query trace'); END
          """);
          execute(
              """
          CREATE TRIGGER query_traces_complete BEFORE INSERT ON query_traces WHEN
            NEW.scope_count!=(SELECT COUNT(*) FROM query_trace_documents WHERE trace_id=NEW.id)
            OR NEW.citation_count!=(SELECT COUNT(*) FROM query_trace_evidence WHERE trace_id=NEW.id)
            OR (NEW.scope_count>0 AND ((SELECT MIN(ordinal) FROM query_trace_documents WHERE trace_id=NEW.id)!=0
              OR (SELECT MAX(ordinal) FROM query_trace_documents WHERE trace_id=NEW.id)!=NEW.scope_count-1))
            OR (NEW.citation_count>0 AND ((SELECT MIN(citation_ordinal) FROM query_trace_evidence WHERE trace_id=NEW.id)!=1
              OR (SELECT MAX(citation_ordinal) FROM query_trace_evidence WHERE trace_id=NEW.id)!=NEW.citation_count))
            OR EXISTS(SELECT 1 FROM query_trace_documents q JOIN index_publications p ON p.id=q.publication_id
              JOIN documents d ON d.id=p.document_id WHERE q.trace_id=NEW.id AND d.workspace_id!=NEW.workspace_id)
          BEGIN SELECT RAISE(ABORT,'incomplete query trace'); END
          """);
          execute(
              """
          CREATE TRIGGER query_trace_evidence_identity BEFORE INSERT ON query_trace_evidence
          WHEN NOT EXISTS(SELECT 1 FROM index_publication_entries e
            JOIN index_publications p ON p.id=e.publication_id
            JOIN corpus_segments s ON s.id=e.source_segment_id AND s.revision_id=p.revision_id
            JOIN corpus_pages pg ON pg.revision_id=s.revision_id AND pg.page_number=s.page_number
            WHERE e.publication_id=NEW.publication_id AND e.source_segment_id=NEW.source_segment_id
              AND e.physical_segment_id=NEW.physical_segment_id AND s.page_number=NEW.page_number
              AND s.start_offset<=NEW.start_offset AND s.end_offset>=NEW.end_offset
              AND s.text_sha256=NEW.text_sha256 AND pg.text_sha256=NEW.page_sha256)
          BEGIN SELECT RAISE(ABORT,'invalid query citation'); END
          """);
          for (String table : List.of("query_trace_documents", "query_trace_evidence")) {
            execute(
                "CREATE TRIGGER "
                    + table
                    + "_sealed BEFORE INSERT ON "
                    + table
                    + " WHEN EXISTS(SELECT 1 FROM query_traces WHERE id=NEW.trace_id)"
                    + " BEGIN SELECT RAISE(ABORT,'sealed query trace'); END");
          }
          for (String table :
              List.of("query_traces", "query_trace_documents", "query_trace_evidence")) {
            execute(
                "CREATE TRIGGER "
                    + table
                    + "_no_update BEFORE UPDATE ON "
                    + table
                    + " BEGIN SELECT RAISE(ABORT,'immutable query trace'); END");
            execute(
                "CREATE TRIGGER "
                    + table
                    + "_no_delete BEFORE DELETE ON "
                    + table
                    + " BEGIN SELECT RAISE(ABORT,'immutable query trace'); END");
          }
          execute("UPDATE format_info SET version=4 WHERE format=?", FORMAT);
          execute("PRAGMA user_version=4");
          return null;
        });
  }

  void migrateVersionThree() {
    transaction(
        () -> {
          // Incremental sidecars: v2 corpus source/evidence tables and their triggers stay
          // untouched.
          execute(
              "CREATE TABLE indexing_jobs(id TEXT PRIMARY KEY,document_id TEXT NOT NULL UNIQUE REFERENCES corpus_documents(document_id) ON DELETE RESTRICT,revision_id TEXT NOT NULL,source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64),parser_revision TEXT NOT NULL,embedding_identity TEXT NOT NULL,projection_identity TEXT NOT NULL,model_revision TEXT NOT NULL,dimensions INTEGER NOT NULL CHECK(dimensions BETWEEN 2 AND 8192),state TEXT NOT NULL CHECK(state IN ('queued','processing','indexed','failed','cancelled')),attempt INTEGER NOT NULL CHECK(attempt BETWEEN 1 AND 3),claim_token_sha256 TEXT,projection_generation_id TEXT,created_by TEXT NOT NULL,error_code TEXT CHECK(error_code IN ('indexing_failed','indexing_timeout','indexing_output_invalid','worker_interrupted','authorization_changed','index_configuration_changed')),created_at TEXT NOT NULL,updated_at TEXT NOT NULL,FOREIGN KEY(document_id,revision_id) REFERENCES corpus_revisions(document_id,id),FOREIGN KEY(id,attempt,projection_generation_id) REFERENCES indexing_attempts(job_id,attempt,projection_generation_id),UNIQUE(id,document_id,revision_id),CHECK((state='processing' AND claim_token_sha256 IS NOT NULL AND length(claim_token_sha256)=64) OR (state!='processing' AND claim_token_sha256 IS NULL)),CHECK((state='failed' AND error_code IS NOT NULL) OR (state!='failed' AND error_code IS NULL)),CHECK(state NOT IN ('processing','indexed') OR projection_generation_id IS NOT NULL),CHECK(state!='queued' OR projection_generation_id IS NULL))");
          execute(
              "CREATE TABLE indexing_attempts(job_id TEXT NOT NULL REFERENCES indexing_jobs(id) ON DELETE RESTRICT,attempt INTEGER NOT NULL CHECK(attempt BETWEEN 1 AND 3),projection_generation_id TEXT NOT NULL UNIQUE CHECK(length(projection_generation_id)=36),started_at TEXT NOT NULL,PRIMARY KEY(job_id,attempt),UNIQUE(job_id,attempt,projection_generation_id))");
          execute(
              "CREATE TABLE index_publications(id TEXT PRIMARY KEY,job_id TEXT NOT NULL UNIQUE,document_id TEXT NOT NULL,revision_id TEXT NOT NULL,attempt INTEGER NOT NULL CHECK(attempt BETWEEN 1 AND 3),projection_generation_id TEXT NOT NULL,source_sha256 TEXT NOT NULL,parser_revision TEXT NOT NULL,embedding_identity TEXT NOT NULL,projection_identity TEXT NOT NULL,model_revision TEXT NOT NULL,dimensions INTEGER NOT NULL CHECK(dimensions BETWEEN 2 AND 8192),manifest_sha256 TEXT NOT NULL CHECK(length(manifest_sha256)=64),segment_count INTEGER NOT NULL CHECK(segment_count BETWEEN 1 AND 4096),created_at TEXT NOT NULL,FOREIGN KEY(job_id,document_id,revision_id) REFERENCES indexing_jobs(id,document_id,revision_id) ON DELETE RESTRICT,FOREIGN KEY(job_id,attempt,projection_generation_id) REFERENCES indexing_attempts(job_id,attempt,projection_generation_id) ON DELETE RESTRICT,UNIQUE(id,document_id,revision_id))");
          execute(
              "CREATE TABLE index_publication_entries(publication_id TEXT NOT NULL REFERENCES index_publications(id) ON DELETE RESTRICT,source_segment_id TEXT NOT NULL REFERENCES corpus_segments(id) ON DELETE RESTRICT,physical_segment_id TEXT NOT NULL UNIQUE,entry_sha256 TEXT NOT NULL CHECK(length(entry_sha256)=64),PRIMARY KEY(publication_id,source_segment_id))");
          execute(
              "CREATE TABLE active_corpus_publications(document_id TEXT PRIMARY KEY REFERENCES corpus_documents(document_id) ON DELETE RESTRICT,publication_id TEXT NOT NULL UNIQUE,revision_id TEXT NOT NULL,FOREIGN KEY(publication_id,document_id,revision_id) REFERENCES index_publications(id,document_id,revision_id) ON DELETE RESTRICT)");
          execute("CREATE INDEX indexing_state ON indexing_jobs(state,created_at,id)");
          execute(
              "CREATE TRIGGER indexing_identity BEFORE UPDATE OF id,document_id,revision_id,source_sha256,parser_revision,embedding_identity,projection_identity,model_revision,dimensions,created_by,created_at ON indexing_jobs BEGIN SELECT RAISE(ABORT,'immutable indexing identity'); END");
          execute(
              "CREATE TRIGGER indexing_attempt_identity BEFORE INSERT ON indexing_attempts WHEN NOT EXISTS(SELECT 1 FROM indexing_jobs j WHERE j.id=NEW.job_id AND j.attempt=NEW.attempt AND j.state='queued' AND j.projection_generation_id IS NULL) BEGIN SELECT RAISE(ABORT,'invalid indexing attempt'); END");
          execute(
              "CREATE TRIGGER indexing_generation_identity BEFORE UPDATE OF projection_generation_id ON indexing_jobs WHEN NOT ((OLD.state='queued' AND NEW.state='processing' AND OLD.projection_generation_id IS NULL AND EXISTS(SELECT 1 FROM indexing_attempts a WHERE a.job_id=NEW.id AND a.attempt=NEW.attempt AND a.projection_generation_id=NEW.projection_generation_id)) OR (OLD.state IN ('failed','cancelled') AND NEW.state='queued' AND NEW.attempt=OLD.attempt+1 AND NEW.projection_generation_id IS NULL)) BEGIN SELECT RAISE(ABORT,'immutable indexing generation'); END");
          execute(
              "CREATE TRIGGER indexing_transition BEFORE UPDATE ON indexing_jobs WHEN NOT ((OLD.state='queued' AND NEW.state IN ('processing','failed','cancelled') AND NEW.attempt=OLD.attempt) OR (OLD.state='processing' AND NEW.state IN ('indexed','failed','cancelled') AND NEW.attempt=OLD.attempt) OR (OLD.state IN ('failed','cancelled') AND NEW.state='queued' AND NEW.attempt=OLD.attempt+1)) BEGIN SELECT RAISE(ABORT,'invalid indexing transition'); END");
          execute(
              "CREATE TRIGGER indexing_published_state BEFORE UPDATE OF state ON indexing_jobs WHEN NEW.state='indexed' AND NOT EXISTS(SELECT 1 FROM index_publications p JOIN active_corpus_publications a ON a.publication_id=p.id AND a.document_id=p.document_id AND a.revision_id=p.revision_id WHERE p.job_id=NEW.id AND p.attempt=NEW.attempt AND p.projection_generation_id=NEW.projection_generation_id AND p.segment_count=(SELECT COUNT(*) FROM index_publication_entries e WHERE e.publication_id=p.id)) BEGIN SELECT RAISE(ABORT,'missing index publication'); END");
          execute(
              "CREATE TRIGGER index_publication_identity BEFORE INSERT ON index_publications WHEN NOT EXISTS(SELECT 1 FROM indexing_jobs j JOIN corpus_revisions r ON r.id=j.revision_id AND r.document_id=j.document_id WHERE j.id=NEW.job_id AND j.state='processing' AND j.document_id=NEW.document_id AND j.revision_id=NEW.revision_id AND j.attempt=NEW.attempt AND j.projection_generation_id=NEW.projection_generation_id AND j.source_sha256=NEW.source_sha256 AND j.parser_revision=NEW.parser_revision AND j.embedding_identity=NEW.embedding_identity AND j.projection_identity=NEW.projection_identity AND j.model_revision=NEW.model_revision AND j.dimensions=NEW.dimensions AND r.segment_count=NEW.segment_count) BEGIN SELECT RAISE(ABORT,'invalid index publication'); END");
          execute(
              "CREATE TRIGGER index_publication_entry_identity BEFORE INSERT ON index_publication_entries WHEN NOT EXISTS(SELECT 1 FROM index_publications p JOIN indexing_jobs j ON j.id=p.job_id AND j.state='processing' AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id JOIN corpus_segments s ON s.id=NEW.source_segment_id AND s.revision_id=p.revision_id WHERE p.id=NEW.publication_id) BEGIN SELECT RAISE(ABORT,'invalid publication evidence'); END");
          execute(
              "CREATE TRIGGER active_corpus_publication_complete BEFORE INSERT ON active_corpus_publications WHEN NOT EXISTS(SELECT 1 FROM index_publications p JOIN indexing_jobs j ON j.id=p.job_id AND j.state='processing' AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id WHERE p.id=NEW.publication_id AND p.document_id=NEW.document_id AND p.revision_id=NEW.revision_id AND p.segment_count=(SELECT COUNT(*) FROM index_publication_entries e WHERE e.publication_id=p.id)) BEGIN SELECT RAISE(ABORT,'incomplete index publication'); END");
          for (String table :
              List.of(
                  "indexing_jobs",
                  "indexing_attempts",
                  "index_publications",
                  "index_publication_entries",
                  "active_corpus_publications")) {
            execute(
                "CREATE TRIGGER "
                    + table
                    + "_no_delete BEFORE DELETE ON "
                    + table
                    + " BEGIN SELECT RAISE(ABORT,'immutable index history'); END");
          }
          for (String table :
              List.of(
                  "indexing_attempts",
                  "index_publications",
                  "index_publication_entries",
                  "active_corpus_publications")) {
            execute(
                "CREATE TRIGGER "
                    + table
                    + "_no_update BEFORE UPDATE ON "
                    + table
                    + " BEGIN SELECT RAISE(ABORT,'immutable index publication'); END");
          }
          execute("UPDATE format_info SET version=3 WHERE format=?", FORMAT);
          execute("PRAGMA user_version=3");
          return null;
        });
  }

  void migrateVersionTwo() {
    transaction(
        () -> {
          execute(
              "CREATE TABLE corpus_revisions(id TEXT PRIMARY KEY,document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE RESTRICT,parser_revision TEXT NOT NULL,source_sha256 TEXT NOT NULL,created_at TEXT NOT NULL,parsed_at TEXT,page_count INTEGER NOT NULL DEFAULT 0 CHECK(page_count BETWEEN 0 AND 500),segment_count INTEGER NOT NULL DEFAULT 0 CHECK(segment_count BETWEEN 0 AND 4096),UNIQUE(document_id,id))");
          execute(
              "CREATE TABLE corpus_documents(document_id TEXT PRIMARY KEY REFERENCES documents(id) ON DELETE RESTRICT,original_blob BLOB NOT NULL CHECK(length(original_blob) BETWEEN 1 AND 20971520),initial_revision_id TEXT NOT NULL,parsed_revision_id TEXT,active_revision_id TEXT CHECK(active_revision_id IS NULL),FOREIGN KEY(document_id,initial_revision_id) REFERENCES corpus_revisions(document_id,id),FOREIGN KEY(document_id,parsed_revision_id) REFERENCES corpus_revisions(document_id,id))");
          execute(
              "CREATE TABLE ingestion_jobs(id TEXT PRIMARY KEY,document_id TEXT NOT NULL UNIQUE REFERENCES corpus_documents(document_id) ON DELETE RESTRICT,revision_id TEXT NOT NULL,state TEXT NOT NULL CHECK(state IN ('queued','processing','parsed','failed','cancelled')),attempt INTEGER NOT NULL CHECK(attempt BETWEEN 1 AND 3),claim_token_sha256 TEXT,created_by TEXT NOT NULL,error_code TEXT CHECK(error_code IN ('unsupported_document','parser_failed','parser_timeout','parser_output_invalid','worker_interrupted')),created_at TEXT NOT NULL,updated_at TEXT NOT NULL,FOREIGN KEY(document_id,revision_id) REFERENCES corpus_revisions(document_id,id),CHECK((state='processing' AND claim_token_sha256 IS NOT NULL AND length(claim_token_sha256)=64) OR (state!='processing' AND claim_token_sha256 IS NULL)),CHECK((state='failed' AND error_code IS NOT NULL) OR (state!='failed' AND error_code IS NULL)))");
          execute(
              "CREATE TABLE corpus_pages(revision_id TEXT NOT NULL REFERENCES corpus_revisions(id) ON DELETE RESTRICT,page_number INTEGER NOT NULL CHECK(page_number BETWEEN 1 AND 500),text TEXT NOT NULL,text_sha256 TEXT NOT NULL,PRIMARY KEY(revision_id,page_number))");
          execute(
              "CREATE TABLE corpus_segments(id TEXT PRIMARY KEY,revision_id TEXT NOT NULL,ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 4095),page_number INTEGER NOT NULL,start_offset INTEGER NOT NULL CHECK(start_offset>=0),end_offset INTEGER NOT NULL CHECK(end_offset>start_offset AND end_offset-start_offset<=1200),text TEXT NOT NULL,text_sha256 TEXT NOT NULL,UNIQUE(revision_id,ordinal),FOREIGN KEY(revision_id,page_number) REFERENCES corpus_pages(revision_id,page_number) ON DELETE RESTRICT)");
          execute("CREATE INDEX ingestion_state ON ingestion_jobs(state,created_at,id)");
          execute(
              "CREATE TRIGGER corpus_source_identity BEFORE UPDATE OF document_id,original_blob,initial_revision_id ON corpus_documents BEGIN SELECT RAISE(ABORT,'immutable corpus source'); END");
          execute(
              "CREATE TRIGGER corpus_pointer_identity BEFORE UPDATE OF parsed_revision_id ON corpus_documents WHEN OLD.parsed_revision_id IS NOT NULL OR NEW.parsed_revision_id IS NULL OR NEW.parsed_revision_id!=OLD.initial_revision_id OR NOT EXISTS(SELECT 1 FROM corpus_revisions r WHERE r.id=NEW.parsed_revision_id AND r.parsed_at IS NOT NULL) BEGIN SELECT RAISE(ABORT,'invalid parsed revision'); END");
          execute(
              "CREATE TRIGGER corpus_revision_identity BEFORE UPDATE OF id,document_id,parser_revision,source_sha256,created_at ON corpus_revisions BEGIN SELECT RAISE(ABORT,'immutable revision identity'); END");
          execute(
              "CREATE TRIGGER corpus_revision_frozen BEFORE UPDATE ON corpus_revisions WHEN OLD.parsed_at IS NOT NULL BEGIN SELECT RAISE(ABORT,'immutable parsed revision'); END");
          execute(
              "CREATE TRIGGER ingestion_identity BEFORE UPDATE OF id,document_id,revision_id,created_by,created_at ON ingestion_jobs BEGIN SELECT RAISE(ABORT,'immutable ingestion identity'); END");
          execute(
              "CREATE TRIGGER ingestion_transition BEFORE UPDATE ON ingestion_jobs WHEN NOT ((OLD.state='queued' AND NEW.state IN ('processing','cancelled') AND NEW.attempt=OLD.attempt) OR (OLD.state='processing' AND NEW.state IN ('parsed','failed','cancelled') AND NEW.attempt=OLD.attempt) OR (OLD.state IN ('failed','cancelled') AND NEW.state='queued' AND NEW.attempt=OLD.attempt+1)) BEGIN SELECT RAISE(ABORT,'invalid ingestion transition'); END");
          for (String table :
              List.of(
                  "corpus_documents",
                  "corpus_revisions",
                  "corpus_pages",
                  "corpus_segments",
                  "ingestion_jobs")) {
            execute(
                "CREATE TRIGGER "
                    + table
                    + "_no_delete BEFORE DELETE ON "
                    + table
                    + " BEGIN SELECT RAISE(ABORT,'immutable corpus history'); END");
          }
          for (String table : List.of("corpus_pages", "corpus_segments")) {
            execute(
                "CREATE TRIGGER "
                    + table
                    + "_no_update BEFORE UPDATE ON "
                    + table
                    + " BEGIN SELECT RAISE(ABORT,'immutable corpus evidence'); END");
            execute(
                "CREATE TRIGGER "
                    + table
                    + "_frozen BEFORE INSERT ON "
                    + table
                    + " WHEN EXISTS(SELECT 1 FROM corpus_revisions r WHERE r.id=NEW.revision_id AND r.parsed_at IS NOT NULL) BEGIN SELECT RAISE(ABORT,'immutable parsed evidence'); END");
          }
          execute("UPDATE format_info SET version=2 WHERE format=?", FORMAT);
          execute("PRAGMA user_version=2");
          return null;
        });
  }

  void initialize() {
    transaction(
        () -> {
          execute("PRAGMA application_id=1163280711");
          execute("PRAGMA user_version=1");
          execute("CREATE TABLE format_info(format TEXT PRIMARY KEY,version INTEGER NOT NULL)");
          execute("INSERT INTO format_info VALUES(?,1)", FORMAT);
          execute(
              "CREATE TABLE folders(id TEXT PRIMARY KEY,workspace_id TEXT NOT NULL,owner_id TEXT NOT NULL,name TEXT NOT NULL,name_key TEXT NOT NULL,updated_at TEXT NOT NULL,UNIQUE(workspace_id,owner_id,name_key))");
          execute(
              "CREATE TABLE documents(id TEXT PRIMARY KEY,workspace_id TEXT NOT NULL,filename TEXT NOT NULL,document_type TEXT NOT NULL,mime_type TEXT NOT NULL,active_revision_id TEXT NOT NULL,source_sha256 TEXT NOT NULL,size_bytes INTEGER NOT NULL,updated_at TEXT NOT NULL,display_name TEXT NOT NULL,folder_id TEXT REFERENCES folders(id) ON DELETE RESTRICT)");
          execute(
              "CREATE TABLE document_acl(document_id TEXT REFERENCES documents(id) ON DELETE CASCADE,principal_id TEXT NOT NULL,role TEXT NOT NULL CHECK(role IN ('owner','editor','reader')),PRIMARY KEY(document_id,principal_id))");
          execute(
              "CREATE TABLE document_tags(document_id TEXT REFERENCES documents(id) ON DELETE CASCADE,tag TEXT NOT NULL,ordinal INTEGER NOT NULL,PRIMARY KEY(document_id,tag))");
          execute(
              "CREATE TABLE management_audit(id TEXT PRIMARY KEY,workspace_id TEXT NOT NULL,actor_id TEXT NOT NULL,entity_id TEXT NOT NULL,action TEXT NOT NULL,fields_json TEXT NOT NULL,before_sha256 TEXT NOT NULL,after_sha256 TEXT NOT NULL,created_at TEXT NOT NULL)");
          execute("CREATE INDEX documents_workspace ON documents(workspace_id,updated_at,id)");
          execute("CREATE INDEX documents_folder ON documents(folder_id,workspace_id)");
          execute("CREATE INDEX acl_principal ON document_acl(principal_id,document_id)");
          execute("CREATE INDEX audit_actor ON management_audit(workspace_id,actor_id,created_at)");
          execute(
              "CREATE TRIGGER audit_no_update BEFORE UPDATE ON management_audit BEGIN SELECT RAISE(ABORT,'immutable audit'); END");
          execute(
              "CREATE TRIGGER audit_no_delete BEFORE DELETE ON management_audit BEGIN SELECT RAISE(ABORT,'immutable audit'); END");
          execute(
              "CREATE TRIGGER immutable_identity BEFORE UPDATE OF filename,workspace_id,document_type,mime_type,active_revision_id,source_sha256,size_bytes ON documents BEGIN SELECT RAISE(ABORT,'immutable source identity'); END");
          return null;
        });
  }
}
