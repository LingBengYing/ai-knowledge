package com.evidence.rag.repository;

import com.evidence.rag.model.domain.ModelValues;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
        || (version < 1 || version > 21)
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
    if (version >= 5
        && (count(
                    "SELECT COUNT(*) FROM pragma_table_info('document_tombstones') WHERE name IN ('document_id','workspace_id','requested_by','requested_at')")
                != 4
            || count(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN ('document_tombstones_identity','document_tombstones_no_replace','document_tombstones_no_update','document_tombstones_no_delete')")
                != 4)) {
      throw new IllegalStateException("Unsupported Java document removal schema");
    }
    if (version >= 6
        && (count(
                    "SELECT COUNT(*) FROM pragma_table_info('image_text_regions') WHERE name IN ('revision_id','page_number','ordinal','start_offset','end_offset','left_pixel','top_pixel','right_pixel','bottom_pixel')")
                != 9
            || count(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN ('image_text_regions_identity','image_text_regions_frozen','image_text_regions_no_replace','image_text_regions_no_update','image_text_regions_no_delete')")
                != 5)) {
      throw new IllegalStateException("Unsupported Java image region schema");
    }
    if (version >= 7
        && (count(
                    "SELECT COUNT(*) FROM pragma_table_info('image_evidence') WHERE name IN ('id','revision_id','width','height','recall_text','recall_sha256','description_revision','created_at')")
                != 8
            || count(
                    "SELECT COUNT(*) FROM pragma_table_info('image_publication_entries') WHERE name IN ('publication_id','image_evidence_id','physical_segment_id','entry_sha256')")
                != 4
            || count(
                    "SELECT COUNT(*) FROM pragma_table_info('image_trace_evidence') WHERE name IN ('trace_id','citation_ordinal','publication_id','image_evidence_id','physical_segment_id','source_sha256','retrieval_score','rerank_score','fact_sha256','visual_model_revision','visual_policy_revision')")
                != 11
            || count(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN ('image_evidence_identity','image_evidence_frozen','image_evidence_no_replace','image_evidence_no_update','image_evidence_no_delete','corpus_revision_visual_complete','image_publication_entry_identity','image_publication_entries_no_replace','image_publication_entries_no_update','image_publication_entries_no_delete','index_publication_entries_no_image_collision','image_trace_evidence_identity','image_trace_evidence_sealed','image_trace_evidence_no_replace','image_trace_evidence_no_update','image_trace_evidence_no_delete')")
                != 16)) {
      throw new IllegalStateException("Unsupported Java visual evidence schema");
    }
    if (version >= 8
        && (count(
                    "SELECT COUNT(*) FROM pragma_table_info('audio_compilations') WHERE name IN ('revision_id','source_sha256','decoder_revision','model_revision','compiler_revision','duration_ms','span_count','projection_count','transcript_sha256','created_at')")
                != 10
            || count(
                    "SELECT COUNT(*) FROM pragma_table_info('audio_spans') WHERE name IN ('id','revision_id','ordinal','start_ms','end_ms','text','text_sha256','index_ordinal')")
                != 8
            || count(
                    "SELECT COUNT(*) FROM pragma_table_info('audio_publication_entries') WHERE name IN ('publication_id','audio_span_id','physical_segment_id','entry_sha256')")
                != 4
            || count(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN ('audio_compilations_identity','audio_compilations_frozen','audio_compilations_no_replace','audio_compilations_no_update','audio_compilations_no_delete','audio_spans_identity','audio_spans_frozen','audio_spans_no_replace','audio_spans_no_update','audio_spans_no_delete','corpus_revision_audio_complete','audio_publication_entry_identity','audio_publication_entries_no_replace','audio_publication_entries_no_update','audio_publication_entries_no_delete','index_publication_entries_no_audio_collision','image_publication_entries_no_audio_collision')")
                != 17)) {
      throw new IllegalStateException("Unsupported Java audio evidence schema");
    }
    if (version >= 9
        && (count(
                    "SELECT COUNT(*) FROM pragma_table_info('audio_trace_evidence') WHERE name IN ('trace_id','citation_ordinal','publication_id','audio_span_id','physical_segment_id','start_code_point','end_code_point','start_ms','end_ms','source_sha256','text_sha256','transcript_sha256','quote_sha256','retrieval_score','rerank_score','fact_sha256')")
                != 16
            || count(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN ('audio_trace_evidence_identity','audio_trace_evidence_sealed','audio_trace_evidence_no_replace','audio_trace_evidence_no_update','audio_trace_evidence_no_delete')")
                != 5)) {
      throw new IllegalStateException("Unsupported Java audio trace schema");
    }
    if (version >= 10) {
      verifyVideoFormat();
    }
    if (version >= 11) {
      verifyVideoTraceFormat();
    }
    if (version >= 12) {
      verifyVideoOcrFormat();
    }
    if (version >= 13) {
      verifySynopsisFormat();
    }
    if (version >= 14) {
      verifySynopsisHierarchyFormat();
    }
    if (version >= 15) {
      verifyVideoSubtitleFormat();
    }
    if (version >= 16) {
      verifyQueryTraceFormat();
    }
    if (version >= 17) {
      verifyImageVectorFormat();
    }
    if (version >= 18) {
      verifyAudioVectorFormat();
    }
    if (version >= 19) {
      verifySoundFormat();
    }
    if (version >= 20) {
      verifyVideoAvFormat();
    }
    if (version >= 21) {
      verifyVideoAvQueryFormat();
    }
  }

  void migrateVersionTwentySeven() {
    new ModelRebuildSchema(store).migrate();
  }

  void verifyVersionTwentySeven() {
    new ModelRebuildSchema(store).verify();
  }

  void migrateVersionTwentySix() {
    new DocumentReplacementSchema(store).migrate();
  }

  void verifyVersionTwentySix() {
    new DocumentReplacementSchema(store).verify();
  }

  /** v25 freezes a complete vector set and records truthful immutable receipt inheritance. */
  void migrateVersionTwentyFive() {
    transaction(
        () -> {
          verifyVersionTwentyFour();
          execute(
              "ALTER TABLE indexing_jobs ADD COLUMN base_vector_set_sha256 TEXT CHECK(base_vector_set_sha256 IS NULL OR (typeof(base_vector_set_sha256)='text' AND length(base_vector_set_sha256)=64 AND base_vector_set_sha256 NOT GLOB '*[^a-f0-9]*'))");
          String identity = schemaSql("trigger", "indexing_identity");
          if (!identity.contains("rebuild_sequence,base_publication_id ON indexing_jobs")) {
            throw new IllegalStateException("Unexpected indexing identity guard");
          }
          execute("DROP TRIGGER indexing_identity");
          execute(
              identity.replace(
                  "rebuild_sequence,base_publication_id ON indexing_jobs",
                  "rebuild_sequence,base_publication_id,base_vector_set_sha256 ON indexing_jobs"));
          execute(
              "CREATE TRIGGER indexing_vector_snapshot_initial BEFORE INSERT ON indexing_jobs WHEN NEW.rebuild_sequence=0 AND NEW.base_vector_set_sha256 IS NOT NULL BEGIN SELECT RAISE(ABORT,'invalid initial vector snapshot'); END");
          execute(
              """
          CREATE TABLE image_vector_bindings(publication_id TEXT NOT NULL REFERENCES index_publications(id) ON DELETE RESTRICT,
            origin_vector_publication_id TEXT NOT NULL REFERENCES image_vector_publications(id) ON DELETE RESTRICT,
            inherited_from_publication_id TEXT NOT NULL REFERENCES index_publications(id) ON DELETE RESTRICT,
            binding_sha256 TEXT NOT NULL CHECK(typeof(binding_sha256)='text' AND length(binding_sha256)=64 AND binding_sha256 NOT GLOB '*[^a-f0-9]*'),
            created_at TEXT NOT NULL,PRIMARY KEY(publication_id,origin_vector_publication_id),
            CHECK(publication_id!=inherited_from_publication_id))
          """);
          execute(
              """
          CREATE TRIGGER image_vector_bindings_identity BEFORE INSERT ON image_vector_bindings
          WHEN NOT EXISTS(SELECT 1 FROM index_publications p
            JOIN indexing_jobs j ON j.id=p.job_id AND j.state='processing' AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id
            JOIN index_publications prior ON prior.id=j.base_publication_id
            JOIN active_corpus_publications a ON a.publication_id=prior.id AND a.document_id=prior.document_id AND a.revision_id=prior.revision_id
            JOIN image_vector_publications v ON v.id=NEW.origin_vector_publication_id
            JOIN index_publications origin ON origin.id=v.publication_id
            WHERE p.id=NEW.publication_id AND NEW.inherited_from_publication_id=prior.id
              AND j.rebuild_sequence>0 AND j.base_vector_set_sha256 IS NOT NULL
              AND p.document_id=prior.document_id AND p.revision_id=prior.revision_id AND p.source_sha256=prior.source_sha256 AND p.parser_revision=prior.parser_revision
              AND p.embedding_identity=prior.embedding_identity AND p.projection_identity=prior.projection_identity AND p.model_revision=prior.model_revision AND p.dimensions=prior.dimensions
              AND origin.document_id=p.document_id AND origin.revision_id=p.revision_id AND origin.source_sha256=p.source_sha256 AND origin.parser_revision=p.parser_revision
              AND origin.embedding_identity=p.embedding_identity AND origin.projection_identity=p.projection_identity AND origin.model_revision=p.model_revision AND origin.dimensions=p.dimensions
              AND origin.segment_count=p.segment_count AND v.document_id=p.document_id AND v.source_revision_id=p.revision_id AND v.source_sha256=p.source_sha256
              AND (v.publication_id=prior.id OR EXISTS(SELECT 1 FROM image_vector_bindings b WHERE b.publication_id=prior.id AND b.origin_vector_publication_id=v.id))
              AND NOT EXISTS(SELECT 1 FROM document_tombstones WHERE document_id=p.document_id)
              AND EXISTS(SELECT 1 FROM image_publication_entries olde JOIN image_publication_entries newe ON newe.image_evidence_id=olde.image_evidence_id
                JOIN image_evidence e ON e.id=newe.image_evidence_id AND e.revision_id=p.revision_id
                WHERE olde.publication_id=origin.id AND olde.image_evidence_id=v.image_evidence_id AND olde.physical_segment_id=v.base_physical_segment_id AND newe.publication_id=p.id)
              AND NOT EXISTS(SELECT 1 FROM image_vector_publications direct WHERE direct.publication_id=p.id AND direct.embedding_identity=v.embedding_identity AND direct.projection_identity=v.projection_identity AND direct.model_revision=v.model_revision AND direct.dimensions=v.dimensions)
              AND NOT EXISTS(SELECT 1 FROM image_vector_bindings b JOIN image_vector_publications same ON same.id=b.origin_vector_publication_id WHERE b.publication_id=p.id AND same.embedding_identity=v.embedding_identity AND same.projection_identity=v.projection_identity AND same.model_revision=v.model_revision AND same.dimensions=v.dimensions))
          BEGIN SELECT RAISE(ABORT,'invalid vector inheritance'); END
          """);
          execute(
              "CREATE TRIGGER image_vector_bindings_no_replace BEFORE INSERT ON image_vector_bindings WHEN EXISTS(SELECT 1 FROM image_vector_bindings WHERE publication_id=NEW.publication_id AND origin_vector_publication_id=NEW.origin_vector_publication_id) BEGIN SELECT RAISE(ABORT,'immutable vector inheritance'); END");
          execute(
              "CREATE TRIGGER image_vector_bindings_no_update BEFORE UPDATE ON image_vector_bindings BEGIN SELECT RAISE(ABORT,'immutable vector inheritance'); END");
          execute(
              "CREATE TRIGGER image_vector_bindings_no_delete BEFORE DELETE ON image_vector_bindings BEGIN SELECT RAISE(ABORT,'immutable vector inheritance'); END");
          execute(
              """
          CREATE TRIGGER image_vector_publications_no_binding_shadow BEFORE INSERT ON image_vector_publications
          WHEN EXISTS(SELECT 1 FROM image_vector_bindings b JOIN image_vector_publications v ON v.id=b.origin_vector_publication_id WHERE b.publication_id=NEW.publication_id
            AND v.embedding_identity=NEW.embedding_identity AND v.projection_identity=NEW.projection_identity AND v.model_revision=NEW.model_revision AND v.dimensions=NEW.dimensions)
          BEGIN SELECT RAISE(ABORT,'duplicate effective vector profile'); END
          """);
          execute(
              """
          CREATE TABLE audio_vector_bindings(publication_id TEXT NOT NULL REFERENCES index_publications(id) ON DELETE RESTRICT,
            origin_vector_publication_id TEXT NOT NULL REFERENCES audio_vector_publications(id) ON DELETE RESTRICT,
            inherited_from_publication_id TEXT NOT NULL REFERENCES index_publications(id) ON DELETE RESTRICT,
            binding_sha256 TEXT NOT NULL CHECK(typeof(binding_sha256)='text' AND length(binding_sha256)=64 AND binding_sha256 NOT GLOB '*[^a-f0-9]*'),
            created_at TEXT NOT NULL,PRIMARY KEY(publication_id,origin_vector_publication_id),
            CHECK(publication_id!=inherited_from_publication_id))
          """);
          execute(
              """
          CREATE TRIGGER audio_vector_bindings_identity BEFORE INSERT ON audio_vector_bindings
          WHEN NOT EXISTS(SELECT 1 FROM index_publications p
            JOIN indexing_jobs j ON j.id=p.job_id AND j.state='processing' AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id
            JOIN index_publications prior ON prior.id=j.base_publication_id
            JOIN active_corpus_publications a ON a.publication_id=prior.id AND a.document_id=prior.document_id AND a.revision_id=prior.revision_id
            JOIN audio_vector_publications v ON v.id=NEW.origin_vector_publication_id
            JOIN index_publications origin ON origin.id=v.publication_id
            WHERE p.id=NEW.publication_id AND NEW.inherited_from_publication_id=prior.id
              AND j.rebuild_sequence>0 AND j.base_vector_set_sha256 IS NOT NULL
              AND p.document_id=prior.document_id AND p.revision_id=prior.revision_id AND p.source_sha256=prior.source_sha256 AND p.parser_revision=prior.parser_revision
              AND p.embedding_identity=prior.embedding_identity AND p.projection_identity=prior.projection_identity AND p.model_revision=prior.model_revision AND p.dimensions=prior.dimensions
              AND origin.document_id=p.document_id AND origin.revision_id=p.revision_id AND origin.source_sha256=p.source_sha256 AND origin.parser_revision=p.parser_revision
              AND origin.embedding_identity=p.embedding_identity AND origin.projection_identity=p.projection_identity AND origin.model_revision=p.model_revision AND origin.dimensions=p.dimensions
              AND origin.segment_count=p.segment_count AND v.document_id=p.document_id AND v.source_revision_id=p.revision_id AND v.source_sha256=p.source_sha256
              AND (v.publication_id=prior.id OR EXISTS(SELECT 1 FROM audio_vector_bindings b WHERE b.publication_id=prior.id AND b.origin_vector_publication_id=v.id))
              AND NOT EXISTS(SELECT 1 FROM document_tombstones WHERE document_id=p.document_id)
              AND v.segment_count=(SELECT COUNT(*) FROM audio_vector_entries WHERE audio_vector_publication_id=v.id)
              AND v.segment_count=(SELECT COUNT(*) FROM audio_publication_entries WHERE publication_id=p.id)
              AND NOT EXISTS(SELECT 1 FROM audio_vector_entries ve
                WHERE ve.audio_vector_publication_id=v.id AND NOT EXISTS(SELECT 1 FROM audio_publication_entries olde
                  JOIN audio_publication_entries newe ON newe.audio_span_id=olde.audio_span_id
                  JOIN audio_spans e ON e.id=newe.audio_span_id AND e.revision_id=p.revision_id AND e.ordinal=ve.ordinal AND e.index_ordinal IS NOT NULL
                  WHERE olde.publication_id=origin.id AND olde.audio_span_id=ve.audio_evidence_id AND olde.physical_segment_id=ve.base_physical_segment_id AND newe.publication_id=p.id))
              AND NOT EXISTS(SELECT 1 FROM audio_vector_publications direct WHERE direct.publication_id=p.id AND direct.embedding_identity=v.embedding_identity AND direct.projection_identity=v.projection_identity AND direct.model_revision=v.model_revision AND direct.dimensions=v.dimensions)
              AND NOT EXISTS(SELECT 1 FROM audio_vector_bindings b JOIN audio_vector_publications same ON same.id=b.origin_vector_publication_id WHERE b.publication_id=p.id AND same.embedding_identity=v.embedding_identity AND same.projection_identity=v.projection_identity AND same.model_revision=v.model_revision AND same.dimensions=v.dimensions))
          BEGIN SELECT RAISE(ABORT,'invalid vector inheritance'); END
          """);
          execute(
              "CREATE TRIGGER audio_vector_bindings_no_replace BEFORE INSERT ON audio_vector_bindings WHEN EXISTS(SELECT 1 FROM audio_vector_bindings WHERE publication_id=NEW.publication_id AND origin_vector_publication_id=NEW.origin_vector_publication_id) BEGIN SELECT RAISE(ABORT,'immutable vector inheritance'); END");
          execute(
              "CREATE TRIGGER audio_vector_bindings_no_update BEFORE UPDATE ON audio_vector_bindings BEGIN SELECT RAISE(ABORT,'immutable vector inheritance'); END");
          execute(
              "CREATE TRIGGER audio_vector_bindings_no_delete BEFORE DELETE ON audio_vector_bindings BEGIN SELECT RAISE(ABORT,'immutable vector inheritance'); END");
          execute(
              """
          CREATE TRIGGER audio_vector_publications_no_binding_shadow BEFORE INSERT ON audio_vector_publications
          WHEN EXISTS(SELECT 1 FROM audio_vector_bindings b JOIN audio_vector_publications v ON v.id=b.origin_vector_publication_id WHERE b.publication_id=NEW.publication_id
            AND v.embedding_identity=NEW.embedding_identity AND v.projection_identity=NEW.projection_identity AND v.model_revision=NEW.model_revision AND v.dimensions=NEW.dimensions)
          BEGIN SELECT RAISE(ABORT,'duplicate effective vector profile'); END
          """);
          String rebuild = schemaSql("trigger", "indexing_rebuild_identity");
          // Match SQL independently of indentation generated by text blocks.
          String replacement =
              "OR (NEW.base_vector_set_sha256 IS NULL AND (EXISTS(SELECT 1 FROM image_vector_publications WHERE publication_id=NEW.base_publication_id) OR EXISTS(SELECT 1 FROM audio_vector_publications WHERE publication_id=NEW.base_publication_id) OR EXISTS(SELECT 1 FROM image_vector_bindings WHERE publication_id=NEW.base_publication_id) OR EXISTS(SELECT 1 FROM audio_vector_bindings WHERE publication_id=NEW.base_publication_id)))";
          String updated =
              rebuild.replaceAll(
                  "OR EXISTS\\(SELECT 1 FROM image_vector_publications WHERE publication_id=NEW\\.base_publication_id\\)\\s+OR EXISTS\\(SELECT 1 FROM audio_vector_publications WHERE publication_id=NEW\\.base_publication_id\\)",
                  replacement);
          if (updated.equals(rebuild)) {
            throw new IllegalStateException("Unexpected rebuild vector fence");
          }
          execute("DROP TRIGGER indexing_rebuild_identity");
          execute(updated);
          String active = schemaSql("trigger", "active_corpus_publications_no_update");
          String noVectors =
              "AND NOT EXISTS(SELECT 1 FROM image_vector_publications WHERE publication_id=OLD.publication_id)";
          if (!active.contains(noVectors)) {
            throw new IllegalStateException("Unexpected active vector fence");
          }
          active =
              active
                  .replace(
                      noVectors,
                      "AND (j.base_vector_set_sha256 IS NOT NULL OR (NOT EXISTS(SELECT 1 FROM image_vector_publications WHERE publication_id=OLD.publication_id) AND NOT EXISTS(SELECT 1 FROM image_vector_bindings WHERE publication_id=OLD.publication_id) AND NOT EXISTS(SELECT 1 FROM audio_vector_bindings WHERE publication_id=OLD.publication_id)")
                  .replace(
                      "AND NOT EXISTS(SELECT 1 FROM audio_vector_publications WHERE publication_id=OLD.publication_id))",
                      "AND NOT EXISTS(SELECT 1 FROM audio_vector_publications WHERE publication_id=OLD.publication_id)))"
                          + vectorInheritanceCompleteSql()
                          + ")");
          execute("DROP TRIGGER active_corpus_publications_no_update");
          execute(active);
          String inventoryGuard = schemaSql("trigger", "cleanup_schema_objects_no_update");
          execute("DROP TRIGGER cleanup_schema_objects_no_update");
          for (var row :
              store.rows(
                  "SELECT type,name,sql FROM sqlite_master WHERE type IN ('table','index','trigger') AND sql IS NOT NULL ORDER BY type,name")) {
            String name = (String) row.get("name");
            String type = (String) row.get("type");
            String digest =
                ModelValues.sha256(((String) row.get("sql")).getBytes(StandardCharsets.UTF_8));
            if (count(
                    "SELECT COUNT(*) FROM cleanup_schema_objects WHERE name=? AND object_type=?",
                    name,
                    type)
                == 0) {
              execute(
                  "INSERT INTO cleanup_schema_objects(name,object_type,sql_sha256) VALUES(?,?,?)",
                  name,
                  type,
                  digest);
            } else if (List.of(
                    "indexing_jobs",
                    "indexing_identity",
                    "indexing_rebuild_identity",
                    "active_corpus_publications_no_update")
                .contains(name)) {
              execute(
                  "UPDATE cleanup_schema_objects SET sql_sha256=? WHERE name=? AND object_type=?",
                  digest,
                  name,
                  type);
            }
          }
          execute(inventoryGuard);
          execute("UPDATE format_info SET version=25 WHERE format=?", FORMAT);
          execute("PRAGMA user_version=25");
          verifyVersionTwentyFive();
          return null;
        });
  }

  private String vectorInheritanceCompleteSql() {
    StringBuilder result = new StringBuilder();
    for (String route : List.of("image", "audio")) {
      String origins = route + "_vector_publications";
      String bindings = route + "_vector_bindings";
      result
          .append(" AND NOT EXISTS(SELECT 1 FROM ")
          .append(origins)
          .append(" v WHERE (v.publication_id=OLD.publication_id OR EXISTS(SELECT 1 FROM ")
          .append(bindings)
          .append(
              " b WHERE b.publication_id=OLD.publication_id AND b.origin_vector_publication_id=v.id)) AND NOT EXISTS(SELECT 1 FROM ")
          .append(bindings)
          .append(
              " n WHERE n.publication_id=NEW.publication_id AND n.origin_vector_publication_id=v.id AND n.inherited_from_publication_id=OLD.publication_id))")
          .append(" AND NOT EXISTS(SELECT 1 FROM ")
          .append(bindings)
          .append(" n WHERE n.publication_id=NEW.publication_id AND NOT EXISTS(SELECT 1 FROM ")
          .append(origins)
          .append(
              " v WHERE v.id=n.origin_vector_publication_id AND (v.publication_id=OLD.publication_id OR EXISTS(SELECT 1 FROM ")
          .append(bindings)
          .append(
              " b WHERE b.publication_id=OLD.publication_id AND b.origin_vector_publication_id=v.id))))");
    }
    return result.toString();
  }

  void verifyVersionTwentyFive() {
    if (count("PRAGMA user_version") != 25
        || count("SELECT COUNT(*) FROM format_info WHERE version=25 AND format=?", FORMAT) != 1
        || count("PRAGMA application_id") != 1163280711
        || count("SELECT COUNT(*) FROM pragma_foreign_key_check") != 0
        || count(
                "SELECT COUNT(*) FROM pragma_table_info('indexing_jobs') WHERE name IN ('rebuild_sequence','base_publication_id')")
            != 2
        || count(
                "SELECT COUNT(*) FROM indexing_jobs WHERE (rebuild_sequence=0)!=(base_publication_id IS NULL)")
            != 0
        || count(
                "SELECT COUNT(*) FROM indexing_jobs j WHERE j.rebuild_sequence>0 AND NOT EXISTS(SELECT 1 FROM index_publications p WHERE p.id=j.base_publication_id AND p.document_id=j.document_id AND p.revision_id=j.revision_id AND p.source_sha256=j.source_sha256 AND p.parser_revision=j.parser_revision AND p.embedding_identity=j.embedding_identity AND p.projection_identity=j.projection_identity AND p.model_revision=j.model_revision AND p.dimensions=j.dimensions)")
            != 0) {
      throw new IllegalStateException("Unsupported vector continuation authority format");
    }
    if (count(
                "SELECT COUNT(*) FROM pragma_table_info('indexing_jobs') WHERE name='base_vector_set_sha256'")
            != 1
        || count(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('image_vector_bindings','audio_vector_bindings')")
            != 2
        || count(
                "SELECT COUNT(*) FROM indexing_jobs WHERE rebuild_sequence=0 AND base_vector_set_sha256 IS NOT NULL")
            != 0) {
      throw new IllegalStateException("Unsupported vector inheritance schema");
    }
    var expected = new LinkedHashMap<String, String>();
    var actual = new LinkedHashMap<String, String>();
    for (var row :
        store.rows(
            "SELECT type,name,sql FROM sqlite_master WHERE type IN ('table','index','trigger') AND sql IS NOT NULL ORDER BY type,name")) {
      actual.put(
          row.get("type") + ":" + row.get("name"),
          ModelValues.sha256(((String) row.get("sql")).getBytes(StandardCharsets.UTF_8)));
    }
    for (var row : store.rows("SELECT name,object_type,sql_sha256 FROM cleanup_schema_objects")) {
      expected.put(row.get("object_type") + ":" + row.get("name"), (String) row.get("sql_sha256"));
    }
    if (!actual.equals(expected)) {
      throw new IllegalStateException("Changed vector continuation authority guards");
    }
    for (var row :
        store.rows(
            "SELECT DISTINCT b.publication_id,d.workspace_id FROM (SELECT publication_id FROM image_vector_bindings UNION SELECT publication_id FROM audio_vector_bindings) b JOIN index_publications p ON p.id=b.publication_id JOIN documents d ON d.id=p.document_id")) {
      String workspace = AuthorityRows.text(row, "workspace_id");
      var publication =
          VectorBindingRows.publication(
              store, workspace, AuthorityRows.text(row, "publication_id"));
      new ImageVectorRepository(store).allBindings(workspace, publication);
      new AudioVectorRepository(store).allBindings(workspace, publication);
    }
    new DocumentCleanupRepository(store).verifyPurgedRows();
  }

  /** v24 permits explicit same-source rebuilds without mutating an earlier indexed task. */
  void migrateVersionTwentyFour() {
    transaction(
        () -> {
          verifyVersionTwentyThree();
          String original = schemaSql("table", "indexing_jobs");
          String uniqueDocument =
              "document_id TEXT NOT NULL UNIQUE REFERENCES corpus_documents(document_id)";
          String columnsEnd = "updated_at TEXT NOT NULL,FOREIGN KEY";
          if (!original.contains(uniqueDocument) || !original.contains(columnsEnd)) {
            throw new IllegalStateException("Unexpected indexing schema before rebuild migration");
          }
          var objects =
              store.rows(
                  "SELECT type,name,sql FROM sqlite_master WHERE tbl_name='indexing_jobs' AND type IN ('index','trigger') AND sql IS NOT NULL ORDER BY type,name");
          String columns =
              String.join(
                  ",",
                  store
                      .rows("SELECT name FROM pragma_table_info('indexing_jobs') ORDER BY cid")
                      .stream()
                      .map(row -> (String) row.get("name"))
                      .toList());
          String updated =
              original
                  .replace(
                      uniqueDocument,
                      "document_id TEXT NOT NULL REFERENCES corpus_documents(document_id)")
                  .replace(
                      columnsEnd,
                      "updated_at TEXT NOT NULL,rebuild_sequence INTEGER NOT NULL DEFAULT 0 CHECK(typeof(rebuild_sequence)='integer' AND rebuild_sequence BETWEEN 0 AND 2147483647),base_publication_id TEXT,UNIQUE(document_id,rebuild_sequence),CHECK((rebuild_sequence=0 AND base_publication_id IS NULL) OR (rebuild_sequence>0 AND base_publication_id IS NOT NULL)),FOREIGN KEY(base_publication_id,document_id,revision_id) REFERENCES index_publications(id,document_id,revision_id) ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED,FOREIGN KEY");
          execute(
              updated.replaceFirst(
                  "CREATE TABLE \"?indexing_jobs\"?\\(", "CREATE TABLE indexing_jobs_v24("));
          execute(
              "INSERT INTO indexing_jobs_v24("
                  + columns
                  + ") SELECT "
                  + columns
                  + " FROM indexing_jobs");
          if (count("SELECT COUNT(*) FROM indexing_jobs")
                  != count("SELECT COUNT(*) FROM indexing_jobs_v24")
              || count(
                      "SELECT COUNT(*) FROM (SELECT "
                          + columns
                          + " FROM indexing_jobs EXCEPT SELECT "
                          + columns
                          + " FROM indexing_jobs_v24)")
                  != 0
              || count(
                      "SELECT COUNT(*) FROM (SELECT "
                          + columns
                          + " FROM indexing_jobs_v24 EXCEPT SELECT "
                          + columns
                          + " FROM indexing_jobs)")
                  != 0) {
            throw new IllegalStateException("Rebuild migration changed indexing history");
          }
          execute("DROP TABLE indexing_jobs");
          execute("ALTER TABLE indexing_jobs_v24 RENAME TO indexing_jobs");
          for (var object : objects) {
            String sql = (String) object.get("sql");
            if ("indexing_identity".equals(object.get("name"))) {
              sql =
                  sql.replace(
                      "created_by,created_at ON indexing_jobs",
                      "created_by,created_at,rebuild_sequence,base_publication_id ON indexing_jobs");
            }
            execute(sql);
          }
          execute(
              "CREATE UNIQUE INDEX indexing_one_pending ON indexing_jobs(document_id) WHERE state IN ('queued','processing')");
          execute(
              """
              CREATE TRIGGER indexing_rebuild_identity BEFORE INSERT ON indexing_jobs
              WHEN NEW.rebuild_sequence>0 AND (NEW.state!='queued' OR NEW.attempt!=1 OR (
                  NEW.rebuild_sequence!=(SELECT COALESCE(MAX(rebuild_sequence),0)+1 FROM indexing_jobs WHERE document_id=NEW.document_id)
                  OR NOT EXISTS(SELECT 1 FROM active_corpus_publications a
                    JOIN index_publications p ON p.id=a.publication_id AND p.document_id=a.document_id AND p.revision_id=a.revision_id
                    JOIN indexing_jobs prior ON prior.id=p.job_id AND prior.state='indexed'
                    JOIN corpus_documents c ON c.document_id=a.document_id AND c.parsed_revision_id=a.revision_id
                    JOIN corpus_revisions r ON r.id=c.parsed_revision_id AND r.document_id=c.document_id AND r.parsed_at IS NOT NULL
                    JOIN documents d ON d.id=c.document_id AND d.source_sha256=r.source_sha256
                    JOIN ingestion_jobs parsed ON parsed.document_id=c.document_id AND parsed.revision_id=r.id AND parsed.state='parsed'
                    WHERE a.document_id=NEW.document_id AND a.publication_id=NEW.base_publication_id
                      AND p.revision_id=NEW.revision_id AND p.source_sha256=NEW.source_sha256 AND p.parser_revision=NEW.parser_revision
                      AND p.embedding_identity=NEW.embedding_identity AND p.projection_identity=NEW.projection_identity
                      AND p.model_revision=NEW.model_revision AND p.dimensions=NEW.dimensions
                      AND r.source_sha256=NEW.source_sha256 AND r.parser_revision=NEW.parser_revision)
                  OR EXISTS(SELECT 1 FROM image_vector_publications WHERE publication_id=NEW.base_publication_id)
                  OR EXISTS(SELECT 1 FROM audio_vector_publications WHERE publication_id=NEW.base_publication_id)
                  OR EXISTS(SELECT 1 FROM document_tombstones WHERE document_id=NEW.document_id)))
              BEGIN SELECT RAISE(ABORT,'invalid rebuild identity'); END
              """);
          String complete = schemaSql("trigger", "active_corpus_publication_complete");
          if (!complete.contains("BEFORE INSERT ON active_corpus_publications")) {
            throw new IllegalStateException("Unexpected active publication completeness guard");
          }
          execute(
              complete
                  .replace(
                      "active_corpus_publication_complete",
                      "active_corpus_publication_rebuild_complete")
                  .replace(
                      "BEFORE INSERT ON active_corpus_publications",
                      "BEFORE UPDATE ON active_corpus_publications"));
          execute("DROP TRIGGER active_corpus_publications_no_update");
          execute(
              """
              CREATE TRIGGER active_corpus_publications_no_update BEFORE UPDATE ON active_corpus_publications
              WHEN NEW.document_id IS NOT OLD.document_id OR NEW.revision_id IS NOT OLD.revision_id
                OR NOT EXISTS(SELECT 1 FROM index_publications p
                  JOIN indexing_jobs j ON j.id=p.job_id AND j.state='processing'
                    AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id
                  JOIN index_publications base ON base.id=OLD.publication_id AND base.document_id=OLD.document_id AND base.revision_id=OLD.revision_id
                  WHERE p.id=NEW.publication_id AND p.document_id=NEW.document_id AND p.revision_id=NEW.revision_id
                    AND j.rebuild_sequence>0 AND j.base_publication_id=OLD.publication_id
                    AND p.id!=base.id AND p.projection_generation_id!=base.projection_generation_id
                    AND p.source_sha256=base.source_sha256 AND p.parser_revision=base.parser_revision
                    AND p.embedding_identity=base.embedding_identity AND p.projection_identity=base.projection_identity
                    AND p.model_revision=base.model_revision AND p.dimensions=base.dimensions
                    AND NOT EXISTS(SELECT 1 FROM document_tombstones WHERE document_id=NEW.document_id)
                    AND NOT EXISTS(SELECT 1 FROM image_vector_publications WHERE publication_id=OLD.publication_id)
                    AND NOT EXISTS(SELECT 1 FROM audio_vector_publications WHERE publication_id=OLD.publication_id))
              BEGIN SELECT RAISE(ABORT,'immutable index publication'); END
              """);
          execute(
              """
              CREATE TRIGGER active_corpus_publication_initial BEFORE INSERT ON active_corpus_publications
              WHEN EXISTS(SELECT 1 FROM active_corpus_publications WHERE document_id=NEW.document_id)
                OR NOT EXISTS(SELECT 1 FROM index_publications p JOIN indexing_jobs j ON j.id=p.job_id
                  WHERE p.id=NEW.publication_id AND j.rebuild_sequence=0 AND j.base_publication_id IS NULL)
              BEGIN SELECT RAISE(ABORT,'invalid initial active publication'); END
              """);
          String inventoryGuard = schemaSql("trigger", "cleanup_schema_objects_no_update");
          execute("DROP TRIGGER cleanup_schema_objects_no_update");
          for (var row :
              store.rows(
                  "SELECT type,name,sql FROM sqlite_master WHERE type IN ('table','index','trigger') AND sql IS NOT NULL ORDER BY type,name")) {
            String name = (String) row.get("name");
            String type = (String) row.get("type");
            String digest =
                ModelValues.sha256(((String) row.get("sql")).getBytes(StandardCharsets.UTF_8));
            if (count(
                    "SELECT COUNT(*) FROM cleanup_schema_objects WHERE name=? AND object_type=?",
                    name,
                    type)
                == 0) {
              execute(
                  "INSERT INTO cleanup_schema_objects(name,object_type,sql_sha256) VALUES(?,?,?)",
                  name,
                  type,
                  digest);
            } else if (List.of(
                    "indexing_jobs", "indexing_identity", "active_corpus_publications_no_update")
                .contains(name)) {
              execute(
                  "UPDATE cleanup_schema_objects SET sql_sha256=? WHERE name=? AND object_type=?",
                  digest,
                  name,
                  type);
            }
          }
          execute(inventoryGuard);
          execute("UPDATE format_info SET version=24 WHERE format=?", FORMAT);
          execute("PRAGMA user_version=24");
          verifyVersionTwentyFour();
          return null;
        });
  }

  private String schemaSql(String type, String name) {
    var rows = store.rows("SELECT sql FROM sqlite_master WHERE type=? AND name=?", type, name);
    if (rows.size() != 1 || !(rows.getFirst().get("sql") instanceof String value)) {
      throw new IllegalStateException("Missing authority schema object");
    }
    return value;
  }

  void verifyVersionTwentyFour() {
    if (count("PRAGMA user_version") != 24
        || count("SELECT COUNT(*) FROM format_info WHERE version=24 AND format=?", FORMAT) != 1
        || count("PRAGMA application_id") != 1163280711
        || count("SELECT COUNT(*) FROM pragma_foreign_key_check") != 0
        || count(
                "SELECT COUNT(*) FROM pragma_table_info('indexing_jobs') WHERE name IN ('rebuild_sequence','base_publication_id')")
            != 2
        || count(
                "SELECT COUNT(*) FROM indexing_jobs WHERE (rebuild_sequence=0)!=(base_publication_id IS NULL)")
            != 0
        || count(
                "SELECT COUNT(*) FROM indexing_jobs j WHERE j.rebuild_sequence>0 AND NOT EXISTS(SELECT 1 FROM index_publications p WHERE p.id=j.base_publication_id AND p.document_id=j.document_id AND p.revision_id=j.revision_id AND p.source_sha256=j.source_sha256 AND p.parser_revision=j.parser_revision AND p.embedding_identity=j.embedding_identity AND p.projection_identity=j.projection_identity AND p.model_revision=j.model_revision AND p.dimensions=j.dimensions)")
            != 0) {
      throw new IllegalStateException("Unsupported rebuild authority format");
    }
    var expected = new LinkedHashMap<String, String>();
    var actual = new LinkedHashMap<String, String>();
    for (var row :
        store.rows(
            "SELECT type,name,sql FROM sqlite_master WHERE type IN ('table','index','trigger') AND sql IS NOT NULL ORDER BY type,name")) {
      actual.put(
          row.get("type") + ":" + row.get("name"),
          ModelValues.sha256(((String) row.get("sql")).getBytes(StandardCharsets.UTF_8)));
    }
    for (var row : store.rows("SELECT name,object_type,sql_sha256 FROM cleanup_schema_objects")) {
      expected.put(row.get("object_type") + ":" + row.get("name"), (String) row.get("sql_sha256"));
    }
    if (!actual.equals(expected)) {
      throw new IllegalStateException("Changed rebuild authority guards");
    }
    new DocumentCleanupRepository(store).verifyPurgedRows();
  }

  /** v23 extends only the persisted ingestion failure vocabulary for managed text configuration. */
  void migrateVersionTwentyThree() {
    transaction(
        () -> {
          new DocumentCleanupSchema(store).verify();
          String oldCheck =
              "error_code TEXT CHECK(error_code IN ('unsupported_document','parser_failed','parser_timeout','parser_output_invalid','worker_interrupted'))";
          String newCheck =
              "error_code TEXT CHECK(error_code IN ('unsupported_document','parser_failed','parser_timeout','parser_output_invalid','worker_interrupted','text_configuration_required','media_text_configuration_mismatch'))";
          String original =
              (String)
                  store
                      .rows(
                          "SELECT sql FROM sqlite_master WHERE type='table' AND name='ingestion_jobs'")
                      .getFirst()
                      .get("sql");
          if (!original.contains(oldCheck)
              || original.indexOf(oldCheck) != original.lastIndexOf(oldCheck)
              || !(original.startsWith("CREATE TABLE ingestion_jobs(")
                  || original.startsWith("CREATE TABLE \"ingestion_jobs\"("))) {
            throw new IllegalStateException(
                "Unexpected ingestion schema before managed configuration migration");
          }
          var objects =
              store.rows(
                  "SELECT type,name,sql FROM sqlite_master WHERE tbl_name='ingestion_jobs' AND type IN ('index','trigger') AND sql IS NOT NULL ORDER BY type,name");
          String columns =
              String.join(
                  ",",
                  store
                      .rows("SELECT name FROM pragma_table_info('ingestion_jobs') ORDER BY cid")
                      .stream()
                      .map(row -> (String) row.get("name"))
                      .toList());
          execute(
              original
                  .replace(oldCheck, newCheck)
                  .replaceFirst(
                      "CREATE TABLE \"?ingestion_jobs\"?\\(", "CREATE TABLE ingestion_jobs_v23("));
          execute(
              "INSERT INTO ingestion_jobs_v23("
                  + columns
                  + ") SELECT "
                  + columns
                  + " FROM ingestion_jobs");
          if (count("SELECT COUNT(*) FROM ingestion_jobs")
                  != count("SELECT COUNT(*) FROM ingestion_jobs_v23")
              || count(
                      "SELECT COUNT(*) FROM (SELECT "
                          + columns
                          + " FROM ingestion_jobs EXCEPT SELECT "
                          + columns
                          + " FROM ingestion_jobs_v23)")
                  != 0
              || count(
                      "SELECT COUNT(*) FROM (SELECT "
                          + columns
                          + " FROM ingestion_jobs_v23 EXCEPT SELECT "
                          + columns
                          + " FROM ingestion_jobs)")
                  != 0) {
            throw new IllegalStateException("Ingestion migration changed task history");
          }
          execute("DROP TABLE ingestion_jobs");
          execute("ALTER TABLE ingestion_jobs_v23 RENAME TO ingestion_jobs");
          for (var object : objects) {
            execute((String) object.get("sql"));
          }
          String immutableGuard =
              (String)
                  store
                      .rows(
                          "SELECT sql FROM sqlite_master WHERE type='trigger' AND name='cleanup_schema_objects_no_update'")
                      .getFirst()
                      .get("sql");
          String updated =
              (String)
                  store
                      .rows(
                          "SELECT sql FROM sqlite_master WHERE type='table' AND name='ingestion_jobs'")
                      .getFirst()
                      .get("sql");
          execute("DROP TRIGGER cleanup_schema_objects_no_update");
          execute(
              "UPDATE cleanup_schema_objects SET sql_sha256=? WHERE name='ingestion_jobs' AND object_type='table'",
              ModelValues.sha256(updated.getBytes(StandardCharsets.UTF_8)));
          execute(immutableGuard);
          execute("UPDATE format_info SET version=23 WHERE format=?", FORMAT);
          execute("PRAGMA user_version=23");
          verifyVersionTwentyThree();
          return null;
        });
  }

  /** Keeps the v22 complete schema inventory and purge verification in force after v23. */
  void verifyVersionTwentyThree() {
    if (count("PRAGMA user_version") != 23
        || count("SELECT COUNT(*) FROM format_info WHERE version=23 AND format=?", FORMAT) != 1
        || count("PRAGMA application_id") != 1163280711
        || count("SELECT COUNT(*) FROM pragma_foreign_key_check") != 0) {
      throw new IllegalStateException("Unsupported managed text authority format");
    }
    var expected = new LinkedHashMap<String, String>();
    var actual = new LinkedHashMap<String, String>();
    for (var row :
        store.rows(
            "SELECT type,name,sql FROM sqlite_master WHERE type IN ('table','index','trigger') AND sql IS NOT NULL ORDER BY type,name")) {
      actual.put(
          row.get("type") + ":" + row.get("name"),
          ModelValues.sha256(((String) row.get("sql")).getBytes(StandardCharsets.UTF_8)));
    }
    for (var row : store.rows("SELECT name,object_type,sql_sha256 FROM cleanup_schema_objects")) {
      expected.put(row.get("object_type") + ":" + row.get("name"), (String) row.get("sql_sha256"));
    }
    if (!actual.equals(expected)) {
      throw new IllegalStateException("Changed managed text authority guards");
    }
    new DocumentCleanupRepository(store).verifyPurgedRows();
  }

  /** Adds immutable hash-only query preparation sidecars without rewriting v20 authority. */
  void migrateVersionTwentyOne() {
    transaction(
        () -> {
          execute(
              """
        CREATE TABLE video_av_query_preparations(
          trace_id TEXT PRIMARY KEY NOT NULL REFERENCES video_av_traces(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
          attachment_count INTEGER NOT NULL CHECK(typeof(attachment_count)='integer' AND attachment_count BETWEEN 1 AND 3),
          mode TEXT NOT NULL CHECK(mode IN ('VISUAL','AUDIO','JOINT')),
          question_sha256 TEXT NOT NULL CHECK(length(question_sha256)=64 AND question_sha256 NOT GLOB '*[^a-f0-9]*'),
          profile_fingerprint TEXT NOT NULL CHECK(length(profile_fingerprint)=64 AND profile_fingerprint NOT GLOB '*[^a-f0-9]*'),
          embedding_revision TEXT NOT NULL CHECK(length(embedding_revision) BETWEEN 1 AND 200),
          preparation_revision TEXT NOT NULL CHECK(preparation_revision='java-video-av-query-preparation-v1'),
          manifest_sha256 TEXT NOT NULL CHECK(length(manifest_sha256)=64 AND manifest_sha256 NOT GLOB '*[^a-f0-9]*'))
        """);
          execute(
              """
        CREATE TABLE video_av_query_attachments(
          trace_id TEXT NOT NULL REFERENCES video_av_query_preparations(trace_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
          ordinal INTEGER NOT NULL CHECK(typeof(ordinal)='integer' AND ordinal BETWEEN 0 AND 2),
          source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64 AND source_sha256 NOT GLOB '*[^a-f0-9]*'),
          media_kind TEXT NOT NULL CHECK(media_kind='video'),
          compiler_revision TEXT NOT NULL CHECK(length(compiler_revision) BETWEEN 1 AND 200),
          content_sha256 TEXT,window_count INTEGER,visual_window_count INTEGER,audio_window_count INTEGER,audio_present INTEGER,
          used_mode TEXT NOT NULL CHECK(used_mode IN ('VISUAL','AUDIO','JOINT')),
          status TEXT NOT NULL CHECK(status IN ('prepared','not_prepared')),
          PRIMARY KEY(trace_id,ordinal),
          CHECK((status='not_prepared' AND content_sha256 IS NULL AND window_count IS NULL
            AND visual_window_count IS NULL AND audio_window_count IS NULL AND audio_present IS NULL)
            OR (status='prepared' AND content_sha256 IS NOT NULL AND length(content_sha256)=64
            AND content_sha256 NOT GLOB '*[^a-f0-9]*' AND window_count IS NOT NULL AND typeof(window_count)='integer' AND window_count BETWEEN 1 AND 1201
            AND visual_window_count IS NOT NULL AND typeof(visual_window_count)='integer' AND visual_window_count BETWEEN 1 AND window_count
            AND audio_window_count IS NOT NULL AND typeof(audio_window_count)='integer' AND audio_window_count BETWEEN 0 AND window_count
            AND visual_window_count+audio_window_count>=window_count
            AND audio_present IS NOT NULL AND typeof(audio_present)='integer' AND audio_present IN (0,1) AND audio_present=(audio_window_count>0)
            AND (used_mode='VISUAL' OR audio_window_count>0))))
        """);
          execute(
              """
        CREATE TRIGGER video_av_query_preparations_complete BEFORE INSERT ON video_av_query_preparations
        WHEN EXISTS(SELECT 1 FROM video_av_traces WHERE id=NEW.trace_id)
          OR NEW.attachment_count!=(SELECT COUNT(*) FROM video_av_query_attachments WHERE trace_id=NEW.trace_id)
          OR (SELECT MIN(ordinal) FROM video_av_query_attachments WHERE trace_id=NEW.trace_id)!=0
          OR (SELECT MAX(ordinal) FROM video_av_query_attachments WHERE trace_id=NEW.trace_id)!=NEW.attachment_count-1
          OR (SELECT COUNT(DISTINCT status) FROM video_av_query_attachments WHERE trace_id=NEW.trace_id)!=1
          OR (SELECT COUNT(DISTINCT compiler_revision) FROM video_av_query_attachments WHERE trace_id=NEW.trace_id)!=1
          OR EXISTS(SELECT 1 FROM video_av_query_attachments WHERE trace_id=NEW.trace_id AND used_mode!=NEW.mode)
          OR (SELECT SUM(window_count) FROM video_av_query_attachments WHERE trace_id=NEW.trace_id)>1201
        BEGIN SELECT RAISE(ABORT,'incomplete video query preparation'); END
        """);
          execute(
              """
        CREATE TRIGGER video_av_query_preparations_no_replace BEFORE INSERT ON video_av_query_preparations
        WHEN EXISTS(SELECT 1 FROM video_av_query_preparations WHERE trace_id=NEW.trace_id)
        BEGIN SELECT RAISE(ABORT,'immutable video query preparation'); END
        """);
          execute(
              """
        CREATE TRIGGER video_av_query_attachments_sealed BEFORE INSERT ON video_av_query_attachments
        WHEN EXISTS(SELECT 1 FROM video_av_query_preparations WHERE trace_id=NEW.trace_id)
          OR EXISTS(SELECT 1 FROM video_av_traces WHERE id=NEW.trace_id)
        BEGIN SELECT RAISE(ABORT,'sealed video query preparation'); END
        """);
          execute(
              """
        CREATE TRIGGER video_av_query_attachments_no_replace BEFORE INSERT ON video_av_query_attachments
        WHEN EXISTS(SELECT 1 FROM video_av_query_attachments WHERE trace_id=NEW.trace_id AND ordinal=NEW.ordinal)
        BEGIN SELECT RAISE(ABORT,'immutable video query attachment'); END
        """);
          execute(
              """
        CREATE TRIGGER video_av_query_parent_complete BEFORE INSERT ON video_av_traces
        WHEN (EXISTS(SELECT 1 FROM video_av_query_attachments WHERE trace_id=NEW.id)
          AND NOT EXISTS(SELECT 1 FROM video_av_query_preparations WHERE trace_id=NEW.id))
          OR EXISTS(SELECT 1 FROM video_av_query_preparations q WHERE q.trace_id=NEW.id
            AND (q.mode!=NEW.mode OR q.question_sha256!=NEW.question_sha256
              OR (NEW.status='answered' AND EXISTS(SELECT 1 FROM video_av_query_attachments a
                WHERE a.trace_id=NEW.id AND a.status!='prepared'))
              OR EXISTS(SELECT 1 FROM video_av_trace_documents d JOIN video_av_publications p ON p.id=d.publication_id
                WHERE d.trace_id=NEW.id AND (p.profile_fingerprint!=q.profile_fingerprint
                  OR p.visual_embedding_identity!=q.embedding_revision OR p.audio_embedding_identity!=q.embedding_revision))))
        BEGIN SELECT RAISE(ABORT,'inconsistent video query trace'); END
        """);
          for (String table :
              List.of("video_av_query_preparations", "video_av_query_attachments")) {
            for (String operation : List.of("UPDATE", "DELETE")) {
              execute(
                  "CREATE TRIGGER "
                      + table
                      + "_no_"
                      + operation.toLowerCase(java.util.Locale.ROOT)
                      + " BEFORE "
                      + operation
                      + " ON "
                      + table
                      + " BEGIN SELECT RAISE(ABORT,'immutable video query authority'); END");
            }
          }
          verifyVideoAvQueryFormat();
          if (count("SELECT COUNT(*) FROM pragma_foreign_key_check") != 0) {
            throw new IllegalStateException("Video query migration changed foreign keys");
          }
          execute("UPDATE format_info SET version=21 WHERE format=?", FORMAT);
          execute("PRAGMA user_version=21");
          return null;
        });
  }

  private void verifyVideoAvQueryFormat() {
    if (count(
                "SELECT COUNT(*) FROM pragma_table_info('video_av_query_preparations') WHERE name IN ('trace_id','attachment_count','mode','question_sha256','profile_fingerprint','embedding_revision','preparation_revision','manifest_sha256')")
            != 8
        || count("SELECT COUNT(*) FROM pragma_table_info('video_av_query_preparations')") != 8
        || count(
                "SELECT COUNT(*) FROM pragma_table_info('video_av_query_attachments') WHERE name IN ('trace_id','ordinal','source_sha256','media_kind','compiler_revision','content_sha256','window_count','visual_window_count','audio_window_count','audio_present','used_mode','status')")
            != 12
        || count("SELECT COUNT(*) FROM pragma_table_info('video_av_query_attachments')") != 12
        || count(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN ('video_av_query_preparations_complete','video_av_query_preparations_no_replace','video_av_query_preparations_no_update','video_av_query_preparations_no_delete','video_av_query_attachments_sealed','video_av_query_attachments_no_replace','video_av_query_attachments_no_update','video_av_query_attachments_no_delete','video_av_query_parent_complete')")
            != 9) {
      throw new IllegalStateException("Unsupported Java video query preparation schema");
    }
  }

  /**
   * Adds independent raw-video audiovisual authority without changing speech or historical trace
   * semantics.
   */
  void migrateVersionTwenty() {
    transaction(
        () -> {
          execute(
              """
        CREATE TABLE video_av_originals(
          document_id TEXT PRIMARY KEY NOT NULL REFERENCES documents(id) ON DELETE RESTRICT,
          source_revision_id TEXT NOT NULL UNIQUE,
          source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64 AND source_sha256 NOT GLOB '*[^a-f0-9]*'),
          filename TEXT NOT NULL,media_type TEXT NOT NULL,
          size_bytes INTEGER NOT NULL CHECK(size_bytes BETWEEN 1 AND 20971520),
          original_blob BLOB NOT NULL CHECK(length(original_blob)=0 OR length(original_blob)=size_bytes),
          created_at TEXT NOT NULL)
        """);
          execute(
              """
        CREATE TRIGGER video_av_originals_identity BEFORE INSERT ON video_av_originals
        WHEN length(NEW.original_blob)!=NEW.size_bytes OR NOT EXISTS(SELECT 1 FROM documents d
          WHERE d.id=NEW.document_id AND d.active_revision_id=NEW.source_revision_id
            AND d.source_sha256=NEW.source_sha256 AND d.filename=NEW.filename
            AND d.document_type='video' AND d.mime_type=NEW.media_type AND d.size_bytes=NEW.size_bytes
            AND d.mime_type IN ('video/mp4','video/quicktime','video/webm','video/x-matroska')
            AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id))
        BEGIN SELECT RAISE(ABORT,'invalid video audiovisual original identity'); END
        """);
          execute(
              """
        CREATE TRIGGER video_av_originals_no_replace BEFORE INSERT ON video_av_originals
        WHEN EXISTS(SELECT 1 FROM video_av_originals WHERE document_id=NEW.document_id OR source_revision_id=NEW.source_revision_id)
        BEGIN SELECT RAISE(ABORT,'immutable video audiovisual original'); END
        """);
          execute(
              """
        CREATE TRIGGER video_av_originals_no_update BEFORE UPDATE ON video_av_originals
        WHEN NEW.document_id IS NOT OLD.document_id OR NEW.source_revision_id IS NOT OLD.source_revision_id
          OR NEW.source_sha256 IS NOT OLD.source_sha256 OR NEW.filename IS NOT OLD.filename
          OR NEW.media_type IS NOT OLD.media_type OR NEW.size_bytes IS NOT OLD.size_bytes
          OR NEW.created_at IS NOT OLD.created_at
          OR (NEW.original_blob IS NOT OLD.original_blob AND NOT(length(NEW.original_blob)=0
            AND EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=OLD.document_id)))
        BEGIN SELECT RAISE(ABORT,'immutable video audiovisual original'); END
        """);
          execute(
              "CREATE TRIGGER video_av_originals_no_delete BEFORE DELETE ON video_av_originals BEGIN SELECT RAISE(ABORT,'immutable video audiovisual original'); END");
          execute(
              """
        CREATE TABLE video_av_publications(
          id TEXT PRIMARY KEY NOT NULL CHECK(length(id)=36),workspace_id TEXT NOT NULL,
          document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE RESTRICT,
          source_revision_id TEXT NOT NULL,source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64 AND source_sha256 NOT GLOB '*[^a-f0-9]*'),
          filename TEXT NOT NULL,media_type TEXT NOT NULL,size_bytes INTEGER NOT NULL CHECK(size_bytes BETWEEN 1 AND 20971520),
          source_first_pts INTEGER NOT NULL,source_time_base_num INTEGER NOT NULL CHECK(source_time_base_num>0),
          source_time_base_den INTEGER NOT NULL CHECK(source_time_base_den>0),ticks_per_second INTEGER NOT NULL CHECK(ticks_per_second>0 AND ticks_per_second%16000=0),
          duration_tick INTEGER NOT NULL CHECK(duration_tick>0 AND duration_tick/ticks_per_second<=600),has_audio INTEGER NOT NULL CHECK(has_audio IN(0,1)),
          decoder_revision TEXT NOT NULL,analysis_model_revision TEXT NOT NULL,
          profile_fingerprint TEXT NOT NULL CHECK(length(profile_fingerprint)=64 AND profile_fingerprint NOT GLOB '*[^a-f0-9]*'),
          visual_embedding_identity TEXT NOT NULL,visual_projection_identity TEXT NOT NULL CHECK(length(visual_projection_identity)=64 AND visual_projection_identity NOT GLOB '*[^a-f0-9]*'),
          visual_model_revision TEXT NOT NULL,visual_dimensions INTEGER NOT NULL CHECK(visual_dimensions BETWEEN 2 AND 3072),
          audio_embedding_identity TEXT NOT NULL,audio_projection_identity TEXT NOT NULL CHECK(length(audio_projection_identity)=64 AND audio_projection_identity NOT GLOB '*[^a-f0-9]*'),
          audio_model_revision TEXT NOT NULL,audio_dimensions INTEGER NOT NULL CHECK(audio_dimensions BETWEEN 2 AND 3072),
          chunk_seconds INTEGER NOT NULL CHECK(chunk_seconds BETWEEN 1 AND 30),window_count INTEGER NOT NULL CHECK(window_count BETWEEN 1 AND 1201),
          window_manifest_sha256 TEXT NOT NULL CHECK(length(window_manifest_sha256)=64 AND window_manifest_sha256 NOT GLOB '*[^a-f0-9]*'),
          visual_entry_count INTEGER NOT NULL CHECK(visual_entry_count BETWEEN 1 AND 1201),visual_manifest_sha256 TEXT NOT NULL CHECK(length(visual_manifest_sha256)=64 AND visual_manifest_sha256 NOT GLOB '*[^a-f0-9]*'),
          audio_entry_count INTEGER NOT NULL CHECK(audio_entry_count BETWEEN 0 AND 1201),audio_manifest_sha256 TEXT NOT NULL CHECK(length(audio_manifest_sha256)=64 AND audio_manifest_sha256 NOT GLOB '*[^a-f0-9]*'),
          created_at_ms INTEGER NOT NULL CHECK(created_at_ms>0),
          CHECK(visual_embedding_identity=audio_embedding_identity AND visual_model_revision=audio_model_revision AND visual_embedding_identity=visual_model_revision AND visual_dimensions=audio_dimensions AND visual_projection_identity!=audio_projection_identity),
          UNIQUE(document_id,source_revision_id,profile_fingerprint))
        """);
          execute(
              """
        CREATE TABLE video_av_windows(
          publication_id TEXT NOT NULL REFERENCES video_av_publications(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
          id TEXT NOT NULL CHECK(length(id)=67 AND substr(id,1,3)='av-' AND substr(id,4) NOT GLOB '*[^a-f0-9]*'),
          ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 1200),start_tick INTEGER NOT NULL CHECK(start_tick>=0),end_tick INTEGER NOT NULL CHECK(end_tick>start_tick),
          clip_sha256 TEXT,frame_count INTEGER,frames_manifest_sha256 TEXT,first_local_tick INTEGER,end_local_tick INTEGER,
          pcm_sha256 TEXT,wav_sha256 TEXT,audio_start_sample INTEGER,audio_end_sample INTEGER,sample_rate INTEGER,
          visual_physical_id TEXT UNIQUE,visual_entry_sha256 TEXT,audio_physical_id TEXT UNIQUE,audio_entry_sha256 TEXT,
          CHECK((clip_sha256 IS NULL AND frame_count IS NULL AND frames_manifest_sha256 IS NULL AND first_local_tick IS NULL AND end_local_tick IS NULL AND visual_physical_id IS NULL AND visual_entry_sha256 IS NULL)
            OR (clip_sha256 IS NOT NULL AND length(clip_sha256)=64 AND clip_sha256 NOT GLOB '*[^a-f0-9]*' AND frame_count IS NOT NULL AND frame_count>0 AND frames_manifest_sha256 IS NOT NULL AND length(frames_manifest_sha256)=64 AND frames_manifest_sha256 NOT GLOB '*[^a-f0-9]*'
              AND first_local_tick IS NOT NULL AND end_local_tick IS NOT NULL AND first_local_tick>=0 AND end_local_tick>first_local_tick AND end_local_tick<=end_tick-start_tick AND visual_physical_id IS NOT NULL AND length(visual_physical_id)=68 AND substr(visual_physical_id,1,4)='seg-' AND substr(visual_physical_id,5) NOT GLOB '*[^a-f0-9]*' AND visual_entry_sha256 IS NOT NULL AND length(visual_entry_sha256)=64 AND visual_entry_sha256 NOT GLOB '*[^a-f0-9]*')),
          CHECK((pcm_sha256 IS NULL AND wav_sha256 IS NULL AND audio_start_sample IS NULL AND audio_end_sample IS NULL AND sample_rate IS NULL AND audio_physical_id IS NULL AND audio_entry_sha256 IS NULL)
            OR (pcm_sha256 IS NOT NULL AND length(pcm_sha256)=64 AND pcm_sha256 NOT GLOB '*[^a-f0-9]*' AND wav_sha256 IS NOT NULL AND length(wav_sha256)=64 AND wav_sha256 NOT GLOB '*[^a-f0-9]*' AND audio_start_sample IS NOT NULL AND audio_end_sample IS NOT NULL AND sample_rate IS NOT NULL AND audio_start_sample>=0 AND audio_end_sample>audio_start_sample AND audio_end_sample<=9600000 AND audio_end_sample-audio_start_sample<=480000 AND sample_rate=16000
              AND audio_physical_id IS NOT NULL AND length(audio_physical_id)=68 AND substr(audio_physical_id,1,4)='seg-' AND substr(audio_physical_id,5) NOT GLOB '*[^a-f0-9]*' AND audio_entry_sha256 IS NOT NULL AND length(audio_entry_sha256)=64 AND audio_entry_sha256 NOT GLOB '*[^a-f0-9]*')),
          CHECK(clip_sha256 IS NOT NULL OR pcm_sha256 IS NOT NULL),CHECK(visual_physical_id IS NULL OR audio_physical_id IS NULL OR visual_physical_id!=audio_physical_id),
          PRIMARY KEY(publication_id,id),UNIQUE(publication_id,ordinal))
        """);
          execute(
              """
        CREATE TRIGGER video_av_publications_identity BEFORE INSERT ON video_av_publications
        WHEN NOT EXISTS(SELECT 1 FROM documents d WHERE d.id=NEW.document_id AND d.workspace_id=NEW.workspace_id
          AND d.active_revision_id=NEW.source_revision_id AND d.source_sha256=NEW.source_sha256
          AND d.filename=NEW.filename AND d.mime_type=NEW.media_type AND d.size_bytes=NEW.size_bytes AND d.document_type='video'
          AND d.mime_type IN ('video/mp4','video/quicktime','video/webm','video/x-matroska')
          AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
          AND (EXISTS(SELECT 1 FROM video_av_originals o WHERE o.document_id=d.id AND o.source_revision_id=NEW.source_revision_id
            AND o.source_sha256=NEW.source_sha256 AND length(o.original_blob)=NEW.size_bytes)
            OR EXISTS(SELECT 1 FROM corpus_documents c JOIN corpus_revisions r ON r.id=c.initial_revision_id AND r.document_id=c.document_id
              WHERE c.document_id=d.id AND c.initial_revision_id=NEW.source_revision_id AND r.source_sha256=NEW.source_sha256 AND length(c.original_blob)=NEW.size_bytes)))
          OR NEW.duration_tick/NEW.ticks_per_second>600 OR (NEW.duration_tick/NEW.ticks_per_second=600 AND NEW.duration_tick%NEW.ticks_per_second!=0)
          OR NEW.window_count!=(SELECT COUNT(*) FROM video_av_windows WHERE publication_id=NEW.id)
          OR NEW.visual_entry_count!=(SELECT COUNT(*) FROM video_av_windows WHERE publication_id=NEW.id AND clip_sha256 IS NOT NULL)
          OR NEW.audio_entry_count!=(SELECT COUNT(*) FROM video_av_windows WHERE publication_id=NEW.id AND pcm_sha256 IS NOT NULL)
          OR NEW.has_audio!=(NEW.audio_entry_count>0)
          OR NOT EXISTS(SELECT 1 FROM video_av_windows WHERE publication_id=NEW.id AND ordinal=0 AND start_tick=0)
          OR NOT EXISTS(SELECT 1 FROM video_av_windows WHERE publication_id=NEW.id AND ordinal=NEW.window_count-1 AND end_tick=NEW.duration_tick)
          OR EXISTS(SELECT 1 FROM video_av_windows w WHERE w.publication_id=NEW.id
            AND (w.ordinal>=NEW.window_count OR w.end_tick-w.start_tick>NEW.chunk_seconds*NEW.ticks_per_second
              OR (w.ordinal>0 AND NOT EXISTS(SELECT 1 FROM video_av_windows previous WHERE previous.publication_id=NEW.id AND previous.ordinal=w.ordinal-1 AND previous.end_tick=w.start_tick))
              OR (w.pcm_sha256 IS NOT NULL AND (w.audio_start_sample!=w.start_tick/(NEW.ticks_per_second/16000) OR w.audio_end_sample>w.end_tick/(NEW.ticks_per_second/16000)
                OR (w.audio_start_sample>0 AND NOT EXISTS(SELECT 1 FROM video_av_windows previous WHERE previous.publication_id=NEW.id AND previous.ordinal=w.ordinal-1 AND previous.audio_end_sample=w.audio_start_sample))))))
        BEGIN SELECT RAISE(ABORT,'incomplete video audiovisual publication'); END
        """);
          execute(
              """
        CREATE TRIGGER video_av_publications_no_replace BEFORE INSERT ON video_av_publications
        WHEN EXISTS(SELECT 1 FROM video_av_publications WHERE id=NEW.id
          OR (document_id=NEW.document_id AND source_revision_id=NEW.source_revision_id AND profile_fingerprint=NEW.profile_fingerprint))
        BEGIN SELECT RAISE(ABORT,'immutable video audiovisual publication'); END
        """);
          execute(
              "CREATE TRIGGER video_av_windows_sealed BEFORE INSERT ON video_av_windows WHEN EXISTS(SELECT 1 FROM video_av_publications WHERE id=NEW.publication_id) BEGIN SELECT RAISE(ABORT,'sealed video audiovisual publication'); END");
          execute(
              "CREATE TRIGGER video_av_windows_no_replace BEFORE INSERT ON video_av_windows WHEN EXISTS(SELECT 1 FROM video_av_windows WHERE (NEW.visual_physical_id IS NOT NULL AND visual_physical_id=NEW.visual_physical_id) OR (NEW.audio_physical_id IS NOT NULL AND (audio_physical_id=NEW.audio_physical_id OR visual_physical_id=NEW.audio_physical_id)) OR (NEW.visual_physical_id IS NOT NULL AND audio_physical_id=NEW.visual_physical_id) OR (publication_id=NEW.publication_id AND (id=NEW.id OR ordinal=NEW.ordinal))) BEGIN SELECT RAISE(ABORT,'immutable video audiovisual window'); END");
          execute(
              """
        CREATE TABLE video_av_traces(
          id TEXT PRIMARY KEY NOT NULL,workspace_id TEXT NOT NULL,actor_id TEXT NOT NULL,
          selection_all INTEGER NOT NULL CHECK(selection_all IN (0,1)),scope_count INTEGER NOT NULL CHECK(scope_count BETWEEN 0 AND 128),
          citation_count INTEGER NOT NULL CHECK(citation_count BETWEEN 0 AND 32),mode TEXT NOT NULL CHECK(mode IN('VISUAL','AUDIO','JOINT')),
          question_sha256 TEXT NOT NULL CHECK(length(question_sha256)=64 AND question_sha256 NOT GLOB '*[^a-f0-9]*'),
          answer_sha256 TEXT CHECK(answer_sha256 IS NULL OR (length(answer_sha256)=64 AND answer_sha256 NOT GLOB '*[^a-f0-9]*')),
          status TEXT NOT NULL CHECK(status IN ('answered','abstained')),reason_code TEXT,model_revision TEXT NOT NULL,
          policy_revision TEXT NOT NULL CHECK(policy_revision='java-video-av-answer-v1'),created_at TEXT NOT NULL,
          CHECK((status='answered' AND answer_sha256 IS NOT NULL AND reason_code IS NULL AND citation_count>0)
            OR (status='abstained' AND answer_sha256 IS NULL AND reason_code IS NOT NULL AND length(reason_code)>0 AND citation_count=0)))
        """);
          execute(
              """
        CREATE TABLE video_av_trace_documents(
          trace_id TEXT NOT NULL REFERENCES video_av_traces(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
          ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 127),publication_id TEXT NOT NULL REFERENCES video_av_publications(id) ON DELETE RESTRICT,
          PRIMARY KEY(trace_id,ordinal),UNIQUE(trace_id,publication_id))
        """);
          execute(
              """
        CREATE TABLE video_av_trace_evidence(
          trace_id TEXT NOT NULL REFERENCES video_av_traces(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
          ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 1 AND 32),publication_id TEXT NOT NULL,window_id TEXT NOT NULL,
          facts_json TEXT NOT NULL CHECK(json_valid(facts_json) AND json_type(facts_json)='array' AND json_array_length(facts_json) BETWEEN 1 AND 16 AND length(CAST(facts_json AS BLOB))<=65536),
          facts_sha256 TEXT NOT NULL CHECK(length(facts_sha256)=64 AND facts_sha256 NOT GLOB '*[^a-f0-9]*'),
          proof_sha256 TEXT NOT NULL CHECK(length(proof_sha256)=64 AND proof_sha256 NOT GLOB '*[^a-f0-9]*'),
          PRIMARY KEY(trace_id,ordinal),FOREIGN KEY(publication_id,window_id) REFERENCES video_av_windows(publication_id,id) ON DELETE RESTRICT)
        """);
          execute(
              """
        CREATE TRIGGER video_av_traces_complete BEFORE INSERT ON video_av_traces
        WHEN NEW.scope_count!=(SELECT COUNT(*) FROM video_av_trace_documents WHERE trace_id=NEW.id)
          OR (NEW.selection_all=1 AND NEW.scope_count!=(SELECT COUNT(*) FROM documents d
            JOIN document_acl a ON a.document_id=d.id WHERE d.workspace_id=NEW.workspace_id
              AND a.principal_id=NEW.actor_id AND a.role IN ('owner','editor','reader') AND d.document_type='video'
              AND (EXISTS(SELECT 1 FROM video_av_originals s WHERE s.document_id=d.id)
                OR EXISTS(SELECT 1 FROM corpus_documents c WHERE c.document_id=d.id))
              AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)))
          OR (NEW.scope_count>0 AND ((SELECT MIN(ordinal) FROM video_av_trace_documents WHERE trace_id=NEW.id)!=0
            OR (SELECT MAX(ordinal) FROM video_av_trace_documents WHERE trace_id=NEW.id)!=NEW.scope_count-1))
          OR NEW.citation_count!=(SELECT COUNT(*) FROM video_av_trace_evidence WHERE trace_id=NEW.id)
          OR (NEW.citation_count>0 AND ((SELECT MIN(ordinal) FROM video_av_trace_evidence WHERE trace_id=NEW.id)!=1
            OR (SELECT MAX(ordinal) FROM video_av_trace_evidence WHERE trace_id=NEW.id)!=NEW.citation_count))
          OR EXISTS(SELECT 1 FROM video_av_trace_documents e JOIN video_av_publications p ON p.id=e.publication_id
            WHERE e.trace_id=NEW.id AND (p.workspace_id!=NEW.workspace_id OR p.analysis_model_revision!=NEW.model_revision
              OR NOT EXISTS(SELECT 1 FROM documents d JOIN document_acl a ON a.document_id=d.id
                WHERE d.id=p.document_id AND d.workspace_id=NEW.workspace_id AND a.principal_id=NEW.actor_id AND a.role IN ('owner','editor','reader')
                  AND d.active_revision_id=p.source_revision_id AND d.source_sha256=p.source_sha256
                  AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id))))
          OR EXISTS(SELECT 1 FROM video_av_trace_evidence e WHERE e.trace_id=NEW.id AND NOT EXISTS(
            SELECT 1 FROM video_av_trace_documents d WHERE d.trace_id=NEW.id AND d.publication_id=e.publication_id))
        BEGIN SELECT RAISE(ABORT,'incomplete video audiovisual trace'); END
        """);
          execute(
              "CREATE TRIGGER video_av_traces_no_replace BEFORE INSERT ON video_av_traces WHEN EXISTS(SELECT 1 FROM video_av_traces WHERE id=NEW.id) BEGIN SELECT RAISE(ABORT,'immutable video audiovisual trace'); END");
          for (String table : List.of("video_av_trace_documents", "video_av_trace_evidence")) {
            execute(
                "CREATE TRIGGER "
                    + table
                    + "_sealed BEFORE INSERT ON "
                    + table
                    + " WHEN EXISTS(SELECT 1 FROM video_av_traces WHERE id=NEW.trace_id) BEGIN SELECT RAISE(ABORT,'sealed video audiovisual trace'); END");
            execute(
                "CREATE TRIGGER "
                    + table
                    + "_no_replace BEFORE INSERT ON "
                    + table
                    + " WHEN EXISTS(SELECT 1 FROM "
                    + table
                    + " WHERE trace_id=NEW.trace_id AND ordinal=NEW.ordinal) BEGIN SELECT RAISE(ABORT,'immutable video audiovisual trace child'); END");
          }
          for (String table :
              List.of(
                  "video_av_publications",
                  "video_av_windows",
                  "video_av_traces",
                  "video_av_trace_documents",
                  "video_av_trace_evidence")) {
            for (String operation : List.of("UPDATE", "DELETE")) {
              execute(
                  "CREATE TRIGGER "
                      + table
                      + "_no_"
                      + operation.toLowerCase(java.util.Locale.ROOT)
                      + " BEFORE "
                      + operation
                      + " ON "
                      + table
                      + " BEGIN SELECT RAISE(ABORT,'immutable video audiovisual authority'); END");
            }
          }
          verifyVideoAvFormat();
          if (count("SELECT COUNT(*) FROM pragma_foreign_key_check") != 0) {
            throw new IllegalStateException("Video audiovisual migration changed foreign keys");
          }
          execute("UPDATE format_info SET version=20 WHERE format=?", FORMAT);
          execute("PRAGMA user_version=20");
          return null;
        });
  }

  private void verifyVideoAvFormat() {
    String[][] columns = {
      {
        "video_av_originals",
        "document_id",
        "source_revision_id",
        "source_sha256",
        "filename",
        "media_type",
        "size_bytes",
        "original_blob",
        "created_at"
      },
      {
        "video_av_publications",
        "id",
        "workspace_id",
        "document_id",
        "source_revision_id",
        "source_sha256",
        "filename",
        "media_type",
        "size_bytes",
        "source_first_pts",
        "source_time_base_num",
        "source_time_base_den",
        "ticks_per_second",
        "duration_tick",
        "has_audio",
        "decoder_revision",
        "analysis_model_revision",
        "profile_fingerprint",
        "visual_embedding_identity",
        "visual_projection_identity",
        "visual_model_revision",
        "visual_dimensions",
        "audio_embedding_identity",
        "audio_projection_identity",
        "audio_model_revision",
        "audio_dimensions",
        "chunk_seconds",
        "window_count",
        "window_manifest_sha256",
        "visual_entry_count",
        "visual_manifest_sha256",
        "audio_entry_count",
        "audio_manifest_sha256",
        "created_at_ms"
      },
      {
        "video_av_windows",
        "publication_id",
        "id",
        "ordinal",
        "start_tick",
        "end_tick",
        "clip_sha256",
        "frame_count",
        "frames_manifest_sha256",
        "first_local_tick",
        "end_local_tick",
        "pcm_sha256",
        "wav_sha256",
        "audio_start_sample",
        "audio_end_sample",
        "sample_rate",
        "visual_physical_id",
        "visual_entry_sha256",
        "audio_physical_id",
        "audio_entry_sha256"
      },
      {
        "video_av_traces",
        "id",
        "workspace_id",
        "actor_id",
        "selection_all",
        "scope_count",
        "citation_count",
        "mode",
        "question_sha256",
        "answer_sha256",
        "status",
        "reason_code",
        "model_revision",
        "policy_revision",
        "created_at"
      },
      {"video_av_trace_documents", "trace_id", "ordinal", "publication_id"},
      {
        "video_av_trace_evidence",
        "trace_id",
        "ordinal",
        "publication_id",
        "window_id",
        "facts_json",
        "facts_sha256",
        "proof_sha256"
      }
    };
    for (String[] table : columns) {
      var wanted =
          new java.util.HashSet<String>(java.util.Arrays.asList(table).subList(1, table.length));
      var actual =
          store.rows("PRAGMA table_info('" + table[0] + "')").stream()
              .map(row -> (String) row.get("name"))
              .collect(java.util.stream.Collectors.toSet());
      if (!wanted.equals(actual)) {
        throw new IllegalStateException("Unsupported Java video audiovisual schema");
      }
      for (String op : List.of("no_update", "no_delete", "no_replace")) {
        if (count(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name=?",
                table[0] + "_" + op)
            != 1) {
          throw new IllegalStateException("Unsupported Java video audiovisual guards");
        }
      }
    }
    for (String guard :
        List.of(
            "video_av_originals_identity",
            "video_av_publications_identity",
            "video_av_windows_sealed",
            "video_av_traces_complete",
            "video_av_trace_documents_sealed",
            "video_av_trace_evidence_sealed")) {
      if (count("SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name=?", guard) != 1) {
        throw new IllegalStateException("Unsupported Java video audiovisual guards");
      }
    }
  }

  /** Adds independent raw-sound authority without changing speech or historical trace semantics. */
  void migrateVersionNineteen() {
    transaction(
        () -> {
          execute(
              """
        CREATE TABLE sound_originals(
          document_id TEXT PRIMARY KEY NOT NULL REFERENCES documents(id) ON DELETE RESTRICT,
          source_revision_id TEXT NOT NULL UNIQUE,
          source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64 AND source_sha256 NOT GLOB '*[^a-f0-9]*'),
          filename TEXT NOT NULL,media_type TEXT NOT NULL,
          size_bytes INTEGER NOT NULL CHECK(size_bytes BETWEEN 1 AND 20971520),
          original_blob BLOB NOT NULL CHECK(length(original_blob)=0 OR length(original_blob)=size_bytes),
          created_at TEXT NOT NULL)
        """);
          execute(
              """
        CREATE TRIGGER sound_originals_identity BEFORE INSERT ON sound_originals
        WHEN length(NEW.original_blob)!=NEW.size_bytes OR NOT EXISTS(SELECT 1 FROM documents d
          WHERE d.id=NEW.document_id AND d.active_revision_id=NEW.source_revision_id
            AND d.source_sha256=NEW.source_sha256 AND d.filename=NEW.filename
            AND d.document_type='audio' AND d.mime_type=NEW.media_type AND d.size_bytes=NEW.size_bytes
            AND d.mime_type IN ('audio/wav','audio/mpeg','audio/flac','audio/ogg','audio/mp4','audio/webm')
            AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id))
        BEGIN SELECT RAISE(ABORT,'invalid sound original identity'); END
        """);
          execute(
              """
        CREATE TRIGGER sound_originals_no_replace BEFORE INSERT ON sound_originals
        WHEN EXISTS(SELECT 1 FROM sound_originals WHERE document_id=NEW.document_id OR source_revision_id=NEW.source_revision_id)
        BEGIN SELECT RAISE(ABORT,'immutable sound original'); END
        """);
          execute(
              """
        CREATE TRIGGER sound_originals_no_update BEFORE UPDATE ON sound_originals
        WHEN NEW.document_id IS NOT OLD.document_id OR NEW.source_revision_id IS NOT OLD.source_revision_id
          OR NEW.source_sha256 IS NOT OLD.source_sha256 OR NEW.filename IS NOT OLD.filename
          OR NEW.media_type IS NOT OLD.media_type OR NEW.size_bytes IS NOT OLD.size_bytes
          OR NEW.created_at IS NOT OLD.created_at
          OR (NEW.original_blob IS NOT OLD.original_blob AND NOT(length(NEW.original_blob)=0
            AND EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=OLD.document_id)))
        BEGIN SELECT RAISE(ABORT,'immutable sound original'); END
        """);
          execute(
              "CREATE TRIGGER sound_originals_no_delete BEFORE DELETE ON sound_originals BEGIN SELECT RAISE(ABORT,'immutable sound original'); END");
          execute(
              """
        CREATE TABLE sound_publications(
          id TEXT PRIMARY KEY NOT NULL CHECK(length(id) BETWEEN 1 AND 128),workspace_id TEXT NOT NULL,
          document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE RESTRICT,
          source_revision_id TEXT NOT NULL,source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64 AND source_sha256 NOT GLOB '*[^a-f0-9]*'),
          filename TEXT NOT NULL,media_type TEXT NOT NULL,size_bytes INTEGER NOT NULL CHECK(size_bytes BETWEEN 1 AND 20971520),
          generation_id TEXT NOT NULL UNIQUE CHECK(length(generation_id)=36),
          embedding_identity TEXT NOT NULL,projection_identity TEXT NOT NULL CHECK(length(projection_identity)=64 AND projection_identity NOT GLOB '*[^a-f0-9]*'),
          embedding_model_revision TEXT NOT NULL,dimensions INTEGER NOT NULL CHECK(dimensions BETWEEN 2 AND 3072),
          sound_model_revision TEXT NOT NULL,decoder_revision TEXT NOT NULL,chunk_seconds INTEGER NOT NULL CHECK(chunk_seconds BETWEEN 1 AND 30),
          sample_count INTEGER NOT NULL CHECK(sample_count BETWEEN 1 AND 9600000),span_count INTEGER NOT NULL CHECK(span_count BETWEEN 1 AND 600),
          manifest_sha256 TEXT NOT NULL CHECK(length(manifest_sha256)=64 AND manifest_sha256 NOT GLOB '*[^a-f0-9]*'),
          profile_fingerprint TEXT NOT NULL CHECK(length(profile_fingerprint)=64 AND profile_fingerprint NOT GLOB '*[^a-f0-9]*'),created_at TEXT NOT NULL,
          UNIQUE(document_id,source_revision_id,profile_fingerprint))
        """);
          execute(
              """
        CREATE TABLE sound_spans(
          publication_id TEXT NOT NULL REFERENCES sound_publications(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
          id TEXT NOT NULL CHECK(length(id)=70 AND substr(id,1,6)='sound-' AND substr(id,7) NOT GLOB '*[^a-f0-9]*'),
          ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 599),start_sample INTEGER NOT NULL CHECK(start_sample>=0),
          end_sample INTEGER NOT NULL CHECK(end_sample>start_sample AND end_sample<=9600000 AND end_sample-start_sample<=480000),
          pcm_sha256 TEXT NOT NULL CHECK(length(pcm_sha256)=64 AND pcm_sha256 NOT GLOB '*[^a-f0-9]*'),
          recall_text TEXT NOT NULL CHECK(length(CAST(recall_text AS BLOB))<=8192),
          physical_segment_id TEXT NOT NULL UNIQUE CHECK(length(physical_segment_id)=68 AND substr(physical_segment_id,1,4)='seg-' AND substr(physical_segment_id,5) NOT GLOB '*[^a-f0-9]*'),
          entry_sha256 TEXT NOT NULL CHECK(length(entry_sha256)=64 AND entry_sha256 NOT GLOB '*[^a-f0-9]*'),
          PRIMARY KEY(publication_id,id),UNIQUE(publication_id,ordinal))
        """);
          execute(
              """
        CREATE TRIGGER sound_publications_identity BEFORE INSERT ON sound_publications
        WHEN NOT EXISTS(SELECT 1 FROM documents d WHERE d.id=NEW.document_id AND d.workspace_id=NEW.workspace_id
          AND d.active_revision_id=NEW.source_revision_id AND d.source_sha256=NEW.source_sha256
          AND d.filename=NEW.filename AND d.mime_type=NEW.media_type AND d.size_bytes=NEW.size_bytes AND d.document_type='audio'
          AND d.mime_type IN ('audio/wav','audio/mpeg','audio/flac','audio/ogg','audio/mp4','audio/webm')
          AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
          AND (EXISTS(SELECT 1 FROM sound_originals o WHERE o.document_id=d.id AND o.source_revision_id=NEW.source_revision_id
            AND o.source_sha256=NEW.source_sha256 AND length(o.original_blob)=NEW.size_bytes)
            OR EXISTS(SELECT 1 FROM corpus_documents c JOIN corpus_revisions r ON r.id=c.initial_revision_id AND r.document_id=c.document_id
              WHERE c.document_id=d.id AND c.initial_revision_id=NEW.source_revision_id AND r.source_sha256=NEW.source_sha256
                AND length(c.original_blob)=NEW.size_bytes)))
          OR NEW.span_count!=(SELECT COUNT(*) FROM sound_spans WHERE publication_id=NEW.id)
          OR NOT EXISTS(SELECT 1 FROM sound_spans WHERE publication_id=NEW.id AND ordinal=0 AND start_sample=0)
          OR NOT EXISTS(SELECT 1 FROM sound_spans WHERE publication_id=NEW.id AND ordinal=NEW.span_count-1 AND end_sample=NEW.sample_count)
          OR EXISTS(SELECT 1 FROM sound_spans s WHERE s.publication_id=NEW.id
            AND (s.ordinal>=NEW.span_count OR s.end_sample-s.start_sample>NEW.chunk_seconds*16000
              OR (s.ordinal<NEW.span_count-1 AND s.end_sample-s.start_sample!=NEW.chunk_seconds*16000)
              OR (s.ordinal>0 AND NOT EXISTS(SELECT 1 FROM sound_spans previous WHERE previous.publication_id=NEW.id
                AND previous.ordinal=s.ordinal-1 AND previous.end_sample=s.start_sample))))
        BEGIN SELECT RAISE(ABORT,'incomplete sound publication'); END
        """);
          execute(
              """
        CREATE TRIGGER sound_publications_no_replace BEFORE INSERT ON sound_publications
        WHEN EXISTS(SELECT 1 FROM sound_publications WHERE id=NEW.id OR generation_id=NEW.generation_id
          OR (document_id=NEW.document_id AND source_revision_id=NEW.source_revision_id AND profile_fingerprint=NEW.profile_fingerprint))
        BEGIN SELECT RAISE(ABORT,'immutable sound publication'); END
        """);
          execute(
              "CREATE TRIGGER sound_spans_sealed BEFORE INSERT ON sound_spans WHEN EXISTS(SELECT 1 FROM sound_publications WHERE id=NEW.publication_id) BEGIN SELECT RAISE(ABORT,'sealed sound publication'); END");
          execute(
              "CREATE TRIGGER sound_spans_no_replace BEFORE INSERT ON sound_spans WHEN EXISTS(SELECT 1 FROM sound_spans WHERE physical_segment_id=NEW.physical_segment_id OR (publication_id=NEW.publication_id AND (id=NEW.id OR ordinal=NEW.ordinal))) BEGIN SELECT RAISE(ABORT,'immutable sound span'); END");
          execute(
              """
        CREATE TABLE sound_traces(
          id TEXT PRIMARY KEY NOT NULL,workspace_id TEXT NOT NULL,actor_id TEXT NOT NULL,
          selection_all INTEGER NOT NULL CHECK(selection_all IN (0,1)),scope_count INTEGER NOT NULL CHECK(scope_count BETWEEN 0 AND 128),
          citation_count INTEGER NOT NULL CHECK(citation_count BETWEEN 0 AND 32),
          question_sha256 TEXT NOT NULL CHECK(length(question_sha256)=64 AND question_sha256 NOT GLOB '*[^a-f0-9]*'),
          answer_sha256 TEXT CHECK(answer_sha256 IS NULL OR (length(answer_sha256)=64 AND answer_sha256 NOT GLOB '*[^a-f0-9]*')),
          status TEXT NOT NULL CHECK(status IN ('answered','abstained')),reason_code TEXT,model_revision TEXT NOT NULL,
          policy_revision TEXT NOT NULL CHECK(policy_revision='java-sound-answer-v1'),created_at TEXT NOT NULL,
          CHECK((status='answered' AND answer_sha256 IS NOT NULL AND reason_code IS NULL AND citation_count>0)
            OR (status='abstained' AND answer_sha256 IS NULL AND reason_code IS NOT NULL AND length(reason_code)>0 AND citation_count=0)))
        """);
          execute(
              """
        CREATE TABLE sound_trace_documents(
          trace_id TEXT NOT NULL REFERENCES sound_traces(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
          ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 127),publication_id TEXT NOT NULL REFERENCES sound_publications(id) ON DELETE RESTRICT,
          PRIMARY KEY(trace_id,ordinal),UNIQUE(trace_id,publication_id))
        """);
          execute(
              """
        CREATE TABLE sound_trace_evidence(
          trace_id TEXT NOT NULL REFERENCES sound_traces(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
          ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 1 AND 32),publication_id TEXT NOT NULL,span_id TEXT NOT NULL,
          facts_json TEXT NOT NULL CHECK(json_valid(facts_json) AND json_type(facts_json)='array' AND json_array_length(facts_json) BETWEEN 1 AND 16 AND length(CAST(facts_json AS BLOB))<=32768),
          facts_sha256 TEXT NOT NULL CHECK(length(facts_sha256)=64 AND facts_sha256 NOT GLOB '*[^a-f0-9]*'),
          proof_sha256 TEXT NOT NULL CHECK(length(proof_sha256)=64 AND proof_sha256 NOT GLOB '*[^a-f0-9]*'),
          PRIMARY KEY(trace_id,ordinal),FOREIGN KEY(publication_id,span_id) REFERENCES sound_spans(publication_id,id) ON DELETE RESTRICT)
        """);
          execute(
              """
        CREATE TRIGGER sound_traces_complete BEFORE INSERT ON sound_traces
        WHEN NEW.scope_count!=(SELECT COUNT(*) FROM sound_trace_documents WHERE trace_id=NEW.id)
          OR (NEW.selection_all=1 AND NEW.scope_count!=(SELECT COUNT(*) FROM documents d
            JOIN document_acl a ON a.document_id=d.id WHERE d.workspace_id=NEW.workspace_id
              AND a.principal_id=NEW.actor_id AND a.role IN ('owner','editor','reader') AND d.document_type='audio'
              AND (EXISTS(SELECT 1 FROM sound_originals s WHERE s.document_id=d.id)
                OR EXISTS(SELECT 1 FROM corpus_documents c WHERE c.document_id=d.id))
              AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)))
          OR (NEW.scope_count>0 AND ((SELECT MIN(ordinal) FROM sound_trace_documents WHERE trace_id=NEW.id)!=0
            OR (SELECT MAX(ordinal) FROM sound_trace_documents WHERE trace_id=NEW.id)!=NEW.scope_count-1))
          OR NEW.citation_count!=(SELECT COUNT(*) FROM sound_trace_evidence WHERE trace_id=NEW.id)
          OR (NEW.citation_count>0 AND ((SELECT MIN(ordinal) FROM sound_trace_evidence WHERE trace_id=NEW.id)!=1
            OR (SELECT MAX(ordinal) FROM sound_trace_evidence WHERE trace_id=NEW.id)!=NEW.citation_count))
          OR EXISTS(SELECT 1 FROM sound_trace_documents e JOIN sound_publications p ON p.id=e.publication_id
            WHERE e.trace_id=NEW.id AND (p.workspace_id!=NEW.workspace_id OR p.sound_model_revision!=NEW.model_revision
              OR NOT EXISTS(SELECT 1 FROM documents d JOIN document_acl a ON a.document_id=d.id
                WHERE d.id=p.document_id AND d.workspace_id=NEW.workspace_id AND a.principal_id=NEW.actor_id AND a.role IN ('owner','editor','reader')
                  AND d.active_revision_id=p.source_revision_id AND d.source_sha256=p.source_sha256
                  AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id))))
          OR EXISTS(SELECT 1 FROM sound_trace_evidence e WHERE e.trace_id=NEW.id AND NOT EXISTS(
            SELECT 1 FROM sound_trace_documents d WHERE d.trace_id=NEW.id AND d.publication_id=e.publication_id))
        BEGIN SELECT RAISE(ABORT,'incomplete sound trace'); END
        """);
          execute(
              "CREATE TRIGGER sound_traces_no_replace BEFORE INSERT ON sound_traces WHEN EXISTS(SELECT 1 FROM sound_traces WHERE id=NEW.id) BEGIN SELECT RAISE(ABORT,'immutable sound trace'); END");
          for (String table : List.of("sound_trace_documents", "sound_trace_evidence")) {
            execute(
                "CREATE TRIGGER "
                    + table
                    + "_sealed BEFORE INSERT ON "
                    + table
                    + " WHEN EXISTS(SELECT 1 FROM sound_traces WHERE id=NEW.trace_id) BEGIN SELECT RAISE(ABORT,'sealed sound trace'); END");
            execute(
                "CREATE TRIGGER "
                    + table
                    + "_no_replace BEFORE INSERT ON "
                    + table
                    + " WHEN EXISTS(SELECT 1 FROM "
                    + table
                    + " WHERE trace_id=NEW.trace_id AND ordinal=NEW.ordinal) BEGIN SELECT RAISE(ABORT,'immutable sound trace child'); END");
          }
          for (String table :
              List.of(
                  "sound_publications",
                  "sound_spans",
                  "sound_traces",
                  "sound_trace_documents",
                  "sound_trace_evidence")) {
            for (String operation : List.of("UPDATE", "DELETE")) {
              execute(
                  "CREATE TRIGGER "
                      + table
                      + "_no_"
                      + operation.toLowerCase(java.util.Locale.ROOT)
                      + " BEFORE "
                      + operation
                      + " ON "
                      + table
                      + " BEGIN SELECT RAISE(ABORT,'immutable sound authority'); END");
            }
          }
          verifySoundFormat();
          if (count("SELECT COUNT(*) FROM pragma_foreign_key_check") != 0) {
            throw new IllegalStateException("Sound migration changed foreign keys");
          }
          execute("UPDATE format_info SET version=19 WHERE format=?", FORMAT);
          execute("PRAGMA user_version=19");
          return null;
        });
  }

  private void verifySoundFormat() {
    String[][] columns = {
      {
        "sound_originals",
        "document_id",
        "source_revision_id",
        "source_sha256",
        "filename",
        "media_type",
        "size_bytes",
        "original_blob",
        "created_at"
      },
      {
        "sound_publications",
        "id",
        "workspace_id",
        "document_id",
        "source_revision_id",
        "source_sha256",
        "filename",
        "media_type",
        "size_bytes",
        "generation_id",
        "embedding_identity",
        "projection_identity",
        "embedding_model_revision",
        "dimensions",
        "sound_model_revision",
        "decoder_revision",
        "chunk_seconds",
        "sample_count",
        "span_count",
        "manifest_sha256",
        "profile_fingerprint",
        "created_at"
      },
      {
        "sound_spans",
        "publication_id",
        "id",
        "ordinal",
        "start_sample",
        "end_sample",
        "pcm_sha256",
        "recall_text",
        "physical_segment_id",
        "entry_sha256"
      },
      {
        "sound_traces",
        "id",
        "workspace_id",
        "actor_id",
        "selection_all",
        "scope_count",
        "citation_count",
        "question_sha256",
        "answer_sha256",
        "status",
        "reason_code",
        "model_revision",
        "policy_revision",
        "created_at"
      },
      {"sound_trace_documents", "trace_id", "ordinal", "publication_id"},
      {
        "sound_trace_evidence",
        "trace_id",
        "ordinal",
        "publication_id",
        "span_id",
        "facts_json",
        "facts_sha256",
        "proof_sha256"
      }
    };
    for (String[] table : columns) {
      var wanted =
          new java.util.HashSet<String>(java.util.Arrays.asList(table).subList(1, table.length));
      var actual =
          store.rows("PRAGMA table_info('" + table[0] + "')").stream()
              .map(row -> (String) row.get("name"))
              .collect(java.util.stream.Collectors.toSet());
      if (!wanted.equals(actual)) {
        throw new IllegalStateException("Unsupported Java sound schema");
      }
    }
    List<String> guards =
        List.of(
            "sound_originals_identity",
            "sound_originals_no_replace",
            "sound_originals_no_update",
            "sound_originals_no_delete",
            "sound_publications_identity",
            "sound_publications_no_replace",
            "sound_publications_no_update",
            "sound_publications_no_delete",
            "sound_spans_sealed",
            "sound_spans_no_replace",
            "sound_spans_no_update",
            "sound_spans_no_delete",
            "sound_traces_complete",
            "sound_traces_no_replace",
            "sound_traces_no_update",
            "sound_traces_no_delete",
            "sound_trace_documents_sealed",
            "sound_trace_documents_no_replace",
            "sound_trace_documents_no_update",
            "sound_trace_documents_no_delete",
            "sound_trace_evidence_sealed",
            "sound_trace_evidence_no_replace",
            "sound_trace_evidence_no_update",
            "sound_trace_evidence_no_delete");
    for (String guard : guards) {
      if (count("SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name=?", guard) != 1) {
        throw new IllegalStateException("Unsupported Java sound guards");
      }
    }
  }

  /**
   * Appends complete immutable audio-vector receipts; no PCM, transcript or coordinates persist.
   */
  void migrateVersionEighteen() {
    transaction(
        () -> {
          execute(
              """
        CREATE TABLE audio_vector_publications(
          id TEXT PRIMARY KEY NOT NULL CHECK(length(id) BETWEEN 1 AND 128),
          publication_id TEXT NOT NULL,
          document_id TEXT NOT NULL,
          source_revision_id TEXT NOT NULL,
          source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64 AND source_sha256 NOT GLOB '*[^a-f0-9]*'),
          vector_generation_id TEXT NOT NULL CHECK(length(vector_generation_id)=36),
          embedding_identity TEXT NOT NULL CHECK(length(embedding_identity) BETWEEN 1 AND 128),
          projection_identity TEXT NOT NULL CHECK(length(projection_identity)=64 AND projection_identity NOT GLOB '*[^a-f0-9]*'),
          model_revision TEXT NOT NULL CHECK(length(model_revision) BETWEEN 1 AND 160),
          dimensions INTEGER NOT NULL CHECK(dimensions BETWEEN 2 AND 3072),
          decoder_revision TEXT NOT NULL CHECK(length(decoder_revision) BETWEEN 1 AND 200),
          manifest_sha256 TEXT NOT NULL CHECK(length(manifest_sha256)=64 AND manifest_sha256 NOT GLOB '*[^a-f0-9]*'),
          segment_count INTEGER NOT NULL CHECK(segment_count BETWEEN 1 AND 600),
          created_at TEXT NOT NULL,
          FOREIGN KEY(publication_id,document_id,source_revision_id) REFERENCES index_publications(id,document_id,revision_id) ON DELETE RESTRICT,
          UNIQUE(publication_id,embedding_identity,projection_identity,model_revision,dimensions,decoder_revision))
        """);
          execute(
              """
        CREATE TABLE audio_vector_entries(
          audio_vector_publication_id TEXT NOT NULL REFERENCES audio_vector_publications(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
          audio_evidence_id TEXT NOT NULL REFERENCES audio_spans(id) ON DELETE RESTRICT,
          base_physical_segment_id TEXT NOT NULL CHECK(length(base_physical_segment_id) BETWEEN 1 AND 128),
          vector_physical_segment_id TEXT NOT NULL UNIQUE CHECK(length(vector_physical_segment_id) BETWEEN 1 AND 128),
          ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 599),
          start_sample INTEGER NOT NULL CHECK(start_sample>=0),
          end_sample INTEGER NOT NULL CHECK(end_sample>start_sample AND end_sample<=9600000 AND end_sample-start_sample<=480000),
          pcm_sha256 TEXT NOT NULL CHECK(length(pcm_sha256)=64 AND pcm_sha256 NOT GLOB '*[^a-f0-9]*'),
          entry_sha256 TEXT NOT NULL CHECK(length(entry_sha256)=64 AND entry_sha256 NOT GLOB '*[^a-f0-9]*'),
          CHECK(base_physical_segment_id!=vector_physical_segment_id),
          PRIMARY KEY(audio_vector_publication_id,audio_evidence_id),
          UNIQUE(audio_vector_publication_id,ordinal),UNIQUE(audio_vector_publication_id,base_physical_segment_id))
        """);
          execute(
              """
        CREATE TRIGGER audio_vector_publications_identity BEFORE INSERT ON audio_vector_publications
        WHEN NOT EXISTS(SELECT 1 FROM index_publications p
          JOIN active_corpus_publications a ON a.publication_id=p.id AND a.document_id=p.document_id AND a.revision_id=p.revision_id
          JOIN audio_compilations h ON h.revision_id=p.revision_id AND h.source_sha256=p.source_sha256 AND h.compiler_revision=p.parser_revision
          JOIN corpus_documents c ON c.document_id=p.document_id AND c.initial_revision_id=p.revision_id AND c.parsed_revision_id=p.revision_id
          JOIN documents d ON d.id=p.document_id AND d.source_sha256=p.source_sha256
          WHERE p.id=NEW.publication_id AND p.document_id=NEW.document_id AND p.revision_id=NEW.source_revision_id
            AND p.source_sha256=NEW.source_sha256 AND p.projection_generation_id!=NEW.vector_generation_id
            AND h.decoder_revision=NEW.decoder_revision AND h.projection_count=NEW.segment_count AND p.segment_count=NEW.segment_count
            AND d.document_type='audio' AND d.mime_type IN ('audio/wav','audio/mpeg','audio/flac','audio/ogg','audio/mp4','audio/webm')
            AND length(c.original_blob) BETWEEN 1 AND 20971520
            AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
            AND NEW.segment_count=(SELECT COUNT(*) FROM audio_publication_entries e WHERE e.publication_id=p.id)
            AND NEW.segment_count=(SELECT COUNT(*) FROM audio_vector_entries v WHERE v.audio_vector_publication_id=NEW.id)
            AND NEW.segment_count=(SELECT COUNT(*) FROM audio_vector_entries v
              JOIN audio_spans s ON s.id=v.audio_evidence_id AND s.revision_id=p.revision_id AND s.index_ordinal IS NOT NULL
              JOIN audio_publication_entries e ON e.publication_id=p.id AND e.audio_span_id=s.id AND e.physical_segment_id=v.base_physical_segment_id
              WHERE v.audio_vector_publication_id=NEW.id AND v.ordinal=s.ordinal AND v.start_sample=s.start_ms*16
                AND ((s.ordinal<h.span_count-1 AND v.end_sample=s.end_ms*16)
                  OR (s.ordinal=h.span_count-1 AND v.end_sample>(s.end_ms-1)*16 AND v.end_sample<=s.end_ms*16))))
        BEGIN SELECT RAISE(ABORT,'incomplete audio vector identity'); END
        """);
          execute(
              """
        CREATE TRIGGER audio_vector_publications_no_replace BEFORE INSERT ON audio_vector_publications
        WHEN EXISTS(SELECT 1 FROM audio_vector_publications WHERE id=NEW.id OR vector_generation_id=NEW.vector_generation_id
          OR (publication_id=NEW.publication_id AND embedding_identity=NEW.embedding_identity AND projection_identity=NEW.projection_identity AND model_revision=NEW.model_revision AND dimensions=NEW.dimensions AND decoder_revision=NEW.decoder_revision))
        BEGIN SELECT RAISE(ABORT,'immutable audio vector publication'); END
        """);
          execute(
              """
        CREATE TRIGGER audio_vector_entries_sealed BEFORE INSERT ON audio_vector_entries
        WHEN EXISTS(SELECT 1 FROM audio_vector_publications WHERE id=NEW.audio_vector_publication_id)
        BEGIN SELECT RAISE(ABORT,'sealed audio vector publication'); END
        """);
          execute(
              """
        CREATE TRIGGER audio_vector_entries_no_replace BEFORE INSERT ON audio_vector_entries
        WHEN EXISTS(SELECT 1 FROM audio_vector_entries WHERE vector_physical_segment_id=NEW.vector_physical_segment_id
          OR (audio_vector_publication_id=NEW.audio_vector_publication_id AND (audio_evidence_id=NEW.audio_evidence_id OR ordinal=NEW.ordinal OR base_physical_segment_id=NEW.base_physical_segment_id)))
        BEGIN SELECT RAISE(ABORT,'immutable audio vector entry'); END
        """);
          for (String table : List.of("audio_vector_publications", "audio_vector_entries")) {
            for (String operation : List.of("UPDATE", "DELETE")) {
              execute(
                  "CREATE TRIGGER "
                      + table
                      + "_no_"
                      + operation.toLowerCase(java.util.Locale.ROOT)
                      + " BEFORE "
                      + operation
                      + " ON "
                      + table
                      + " BEGIN SELECT RAISE(ABORT,'immutable audio vector publication'); END");
            }
          }
          verifyAudioVectorFormat();
          if (count("SELECT COUNT(*) FROM pragma_foreign_key_check") != 0) {
            throw new IllegalStateException("Audio vector migration changed foreign keys");
          }
          execute("UPDATE format_info SET version=18 WHERE format=?", FORMAT);
          execute("PRAGMA user_version=18");
          return null;
        });
  }

  private void verifyAudioVectorFormat() {
    if (count(
                "SELECT COUNT(*) FROM pragma_table_info('audio_vector_publications') WHERE name IN ('id','publication_id','document_id','source_revision_id','source_sha256','vector_generation_id','embedding_identity','projection_identity','model_revision','dimensions','decoder_revision','manifest_sha256','segment_count','created_at')")
            != 14
        || count(
                "SELECT COUNT(*) FROM pragma_table_info('audio_vector_entries') WHERE name IN ('audio_vector_publication_id','audio_evidence_id','base_physical_segment_id','vector_physical_segment_id','ordinal','start_sample','end_sample','pcm_sha256','entry_sha256')")
            != 9
        || count(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN ('audio_vector_publications_identity','audio_vector_publications_no_replace','audio_vector_publications_no_update','audio_vector_publications_no_delete','audio_vector_entries_sealed','audio_vector_entries_no_replace','audio_vector_entries_no_update','audio_vector_entries_no_delete')")
            != 8) {
      throw new IllegalStateException("Unsupported Java audio vector publication schema");
    }
  }

  /** Appends independent image-vector receipts without rewriting base publications or sources. */
  void migrateVersionSeventeen() {
    transaction(
        () -> {
          execute(
              """
        CREATE TABLE image_vector_publications(
          id TEXT PRIMARY KEY NOT NULL CHECK(length(id) BETWEEN 1 AND 128),
          publication_id TEXT NOT NULL,
          document_id TEXT NOT NULL,
          source_revision_id TEXT NOT NULL,
          source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64 AND source_sha256 NOT GLOB '*[^a-f0-9]*'),
          image_evidence_id TEXT NOT NULL,
          base_physical_segment_id TEXT NOT NULL CHECK(length(base_physical_segment_id) BETWEEN 1 AND 128),
          vector_generation_id TEXT NOT NULL CHECK(length(vector_generation_id)=36),
          vector_physical_segment_id TEXT NOT NULL UNIQUE CHECK(length(vector_physical_segment_id) BETWEEN 1 AND 128),
          embedding_identity TEXT NOT NULL CHECK(length(embedding_identity) BETWEEN 1 AND 128),
          projection_identity TEXT NOT NULL CHECK(length(projection_identity)=64 AND projection_identity NOT GLOB '*[^a-f0-9]*'),
          model_revision TEXT NOT NULL CHECK(length(model_revision) BETWEEN 1 AND 160),
          dimensions INTEGER NOT NULL CHECK(dimensions BETWEEN 2 AND 8192),
          entry_sha256 TEXT NOT NULL CHECK(length(entry_sha256)=64 AND entry_sha256 NOT GLOB '*[^a-f0-9]*'),
          manifest_sha256 TEXT NOT NULL CHECK(length(manifest_sha256)=64 AND manifest_sha256 NOT GLOB '*[^a-f0-9]*'),
          created_at TEXT NOT NULL,
          FOREIGN KEY(publication_id,document_id,source_revision_id) REFERENCES index_publications(id,document_id,revision_id) ON DELETE RESTRICT,
          FOREIGN KEY(publication_id,image_evidence_id) REFERENCES image_publication_entries(publication_id,image_evidence_id) ON DELETE RESTRICT,
          UNIQUE(publication_id,embedding_identity,projection_identity,model_revision,dimensions))
        """);
          execute(
              """
        CREATE TRIGGER image_vector_publications_identity BEFORE INSERT ON image_vector_publications
        WHEN NOT EXISTS(SELECT 1 FROM index_publications p
          JOIN image_publication_entries e ON e.publication_id=p.id
          JOIN image_evidence i ON i.id=e.image_evidence_id AND i.revision_id=p.revision_id
          JOIN active_corpus_publications a ON a.publication_id=p.id AND a.document_id=p.document_id AND a.revision_id=p.revision_id
          JOIN corpus_documents c ON c.document_id=p.document_id AND c.initial_revision_id=p.revision_id AND c.parsed_revision_id=p.revision_id
          JOIN documents d ON d.id=p.document_id AND d.source_sha256=p.source_sha256
          WHERE p.id=NEW.publication_id AND p.document_id=NEW.document_id AND p.revision_id=NEW.source_revision_id
            AND p.source_sha256=NEW.source_sha256 AND e.image_evidence_id=NEW.image_evidence_id
            AND e.physical_segment_id=NEW.base_physical_segment_id AND d.document_type='image'
            AND d.mime_type IN ('image/png','image/jpeg') AND length(c.original_blob) BETWEEN 1 AND 10485760
            AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id))
        BEGIN SELECT RAISE(ABORT,'invalid image vector identity'); END
        """);
          execute(
              """
        CREATE TRIGGER image_vector_publications_no_replace BEFORE INSERT ON image_vector_publications
        WHEN EXISTS(SELECT 1 FROM image_vector_publications WHERE id=NEW.id OR vector_physical_segment_id=NEW.vector_physical_segment_id
          OR (publication_id=NEW.publication_id AND embedding_identity=NEW.embedding_identity AND projection_identity=NEW.projection_identity AND model_revision=NEW.model_revision AND dimensions=NEW.dimensions))
        BEGIN SELECT RAISE(ABORT,'immutable image vector publication'); END
        """);
          for (String operation : List.of("UPDATE", "DELETE")) {
            execute(
                "CREATE TRIGGER image_vector_publications_no_"
                    + operation.toLowerCase(java.util.Locale.ROOT)
                    + " BEFORE "
                    + operation
                    + " ON image_vector_publications BEGIN SELECT RAISE(ABORT,'immutable image vector publication'); END");
          }
          verifyImageVectorFormat();
          if (count("SELECT COUNT(*) FROM pragma_foreign_key_check") != 0) {
            throw new IllegalStateException("Image vector migration changed foreign keys");
          }
          execute("UPDATE format_info SET version=17 WHERE format=?", FORMAT);
          execute("PRAGMA user_version=17");
          return null;
        });
  }

  private void verifyImageVectorFormat() {
    if (count(
                "SELECT COUNT(*) FROM pragma_table_info('image_vector_publications') WHERE name IN ('id','publication_id','document_id','source_revision_id','source_sha256','image_evidence_id','base_physical_segment_id','vector_generation_id','vector_physical_segment_id','embedding_identity','projection_identity','model_revision','dimensions','entry_sha256','manifest_sha256','created_at')")
            != 16
        || count(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN ('image_vector_publications_identity','image_vector_publications_no_replace','image_vector_publications_no_update','image_vector_publications_no_delete')")
            != 4) {
      throw new IllegalStateException("Unsupported Java image vector publication schema");
    }
  }

  /** Appends request-lifetime preparation identities, never query media or library evidence. */
  void migrateVersionSixteen() {
    transaction(
        () -> {
          execute(
              """
              CREATE TABLE query_trace_preparations(
                trace_id TEXT PRIMARY KEY NOT NULL REFERENCES query_traces(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
                question_sha256 TEXT NOT NULL CHECK(length(question_sha256)=64 AND question_sha256 NOT GLOB '*[^a-f0-9]*'),
                preparation_revision TEXT NOT NULL CHECK(length(preparation_revision) BETWEEN 1 AND 200),
                ranking_revision TEXT CHECK(ranking_revision IS NULL OR length(ranking_revision) BETWEEN 1 AND 200),
                status TEXT NOT NULL CHECK(status IN ('prepared','failed')),
                reason_code TEXT,
                manifest_sha256 TEXT CHECK(manifest_sha256 IS NULL OR (length(manifest_sha256)=64 AND manifest_sha256 NOT GLOB '*[^a-f0-9]*')),
                attachment_count INTEGER NOT NULL CHECK(attachment_count BETWEEN 1 AND 3),
                CHECK((status='prepared' AND reason_code IS NULL AND manifest_sha256 IS NOT NULL)
                  OR (status='failed' AND manifest_sha256 IS NULL AND reason_code IS NOT NULL
                    AND length(reason_code) BETWEEN 1 AND 64 AND reason_code GLOB '[a-z]*' AND reason_code NOT GLOB '*[^a-z0-9_]*')))
              """);
          execute(
              """
              CREATE TABLE query_trace_attachments(
                trace_id TEXT NOT NULL REFERENCES query_trace_preparations(trace_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
                ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 2),
                source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64 AND source_sha256 NOT GLOB '*[^a-f0-9]*'),
                media_kind TEXT NOT NULL CHECK(media_kind IN ('IMAGE','AUDIO','VIDEO')),
                compiler_revision TEXT CHECK(compiler_revision IS NULL OR length(compiler_revision) BETWEEN 1 AND 200),
                content_sha256 TEXT CHECK(content_sha256 IS NULL OR (length(content_sha256)=64 AND content_sha256 NOT GLOB '*[^a-f0-9]*')),
                text_code_points INTEGER CHECK(text_code_points IS NULL OR text_code_points BETWEEN 0 AND 8192),
                visual_count INTEGER CHECK(visual_count IS NULL OR visual_count BETWEEN 0 AND 128),
                selected_image_count INTEGER NOT NULL CHECK(selected_image_count BETWEEN 0 AND 3),
                visual_sampled INTEGER CHECK(visual_sampled IS NULL OR visual_sampled IN (0,1)),
                PRIMARY KEY(trace_id,ordinal),
                CHECK((compiler_revision IS NULL AND content_sha256 IS NULL AND text_code_points IS NULL
                    AND visual_count IS NULL AND visual_sampled IS NULL AND selected_image_count=0)
                  OR (compiler_revision IS NOT NULL AND content_sha256 IS NOT NULL AND text_code_points IS NOT NULL
                    AND visual_count IS NOT NULL AND visual_sampled IS NOT NULL AND selected_image_count<=visual_count
                    AND ((media_kind='IMAGE' AND visual_count=1) OR (media_kind='AUDIO' AND visual_count=0)
                      OR (media_kind='VIDEO' AND visual_count>0))
                    AND (visual_sampled=0 OR selected_image_count<visual_count)
                    AND (visual_sampled=1 OR visual_count=0 OR selected_image_count>0))))
              """);
          execute(
              """
              CREATE TABLE query_trace_attachment_images(
                trace_id TEXT NOT NULL,
                attachment_ordinal INTEGER NOT NULL CHECK(attachment_ordinal BETWEEN 0 AND 2),
                ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 2),
                image_sha256 TEXT NOT NULL CHECK(length(image_sha256)=64 AND image_sha256 NOT GLOB '*[^a-f0-9]*'),
                PRIMARY KEY(trace_id,attachment_ordinal,ordinal),UNIQUE(trace_id,attachment_ordinal,image_sha256),
                FOREIGN KEY(trace_id,attachment_ordinal) REFERENCES query_trace_attachments(trace_id,ordinal) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED)
              """);
          execute(
              """
              CREATE TRIGGER query_traces_query_preparation_complete BEFORE INSERT ON query_traces
              WHEN EXISTS(SELECT 1 FROM query_trace_preparations p WHERE p.trace_id=NEW.id AND (
                p.question_sha256!=NEW.question_sha256 OR (p.status='failed' AND NEW.outcome!='abstained')
                OR p.attachment_count!=(SELECT COUNT(*) FROM query_trace_attachments a WHERE a.trace_id=NEW.id)
                OR (SELECT MIN(ordinal) FROM query_trace_attachments WHERE trace_id=NEW.id)!=0
                OR (SELECT MAX(ordinal) FROM query_trace_attachments WHERE trace_id=NEW.id)!=p.attachment_count-1
                OR EXISTS(SELECT 1 FROM query_trace_attachments a WHERE a.trace_id=NEW.id AND (
                  (p.status='prepared' AND a.compiler_revision IS NULL) OR (p.status='failed' AND a.compiler_revision IS NOT NULL)
                  OR a.selected_image_count!=(SELECT COUNT(*) FROM query_trace_attachment_images i WHERE i.trace_id=a.trace_id AND i.attachment_ordinal=a.ordinal)
                  OR (a.selected_image_count>0 AND ((SELECT MIN(ordinal) FROM query_trace_attachment_images i WHERE i.trace_id=a.trace_id AND i.attachment_ordinal=a.ordinal)!=0
                    OR (SELECT MAX(ordinal) FROM query_trace_attachment_images i WHERE i.trace_id=a.trace_id AND i.attachment_ordinal=a.ordinal)!=a.selected_image_count-1))))
                OR (SELECT COUNT(DISTINCT image_sha256) FROM query_trace_attachment_images WHERE trace_id=NEW.id)>3))
              BEGIN SELECT RAISE(ABORT,'incomplete query preparation trace'); END
              """);
          for (String table :
              List.of(
                  "query_trace_preparations",
                  "query_trace_attachments",
                  "query_trace_attachment_images")) {
            String identity =
                switch (table) {
                  case "query_trace_preparations" -> "trace_id=NEW.trace_id";
                  case "query_trace_attachments" -> "trace_id=NEW.trace_id AND ordinal=NEW.ordinal";
                  default ->
                      "trace_id=NEW.trace_id AND attachment_ordinal=NEW.attachment_ordinal AND (ordinal=NEW.ordinal OR image_sha256=NEW.image_sha256)";
                };
            execute(
                "CREATE TRIGGER "
                    + table
                    + "_sealed BEFORE INSERT ON "
                    + table
                    + " WHEN EXISTS(SELECT 1 FROM query_traces WHERE id=NEW.trace_id) BEGIN SELECT RAISE(ABORT,'sealed query preparation trace'); END");
            execute(
                "CREATE TRIGGER "
                    + table
                    + "_no_replace BEFORE INSERT ON "
                    + table
                    + " WHEN EXISTS(SELECT 1 FROM "
                    + table
                    + " WHERE "
                    + identity
                    + ") BEGIN SELECT RAISE(ABORT,'immutable query preparation trace'); END");
            for (String operation : List.of("UPDATE", "DELETE")) {
              execute(
                  "CREATE TRIGGER "
                      + table
                      + "_no_"
                      + operation.toLowerCase(java.util.Locale.ROOT)
                      + " BEFORE "
                      + operation
                      + " ON "
                      + table
                      + " BEGIN SELECT RAISE(ABORT,'immutable query preparation trace'); END");
            }
          }
          verifyQueryTraceFormat();
          if (count("SELECT COUNT(*) FROM pragma_foreign_key_check") != 0) {
            throw new IllegalStateException("Query trace migration changed foreign keys");
          }
          execute("UPDATE format_info SET version=16 WHERE format=?", FORMAT);
          execute("PRAGMA user_version=16");
          return null;
        });
  }

  private void verifyQueryTraceFormat() {
    for (String table :
        List.of(
            "query_trace_preparations",
            "query_trace_attachments",
            "query_trace_attachment_images")) {
      if (count("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", table) != 1
          || count(
                  "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN (?,?,?,?)",
                  table + "_sealed",
                  table + "_no_replace",
                  table + "_no_update",
                  table + "_no_delete")
              != 4) {
        throw new IllegalStateException("Unsupported Java query preparation trace schema");
      }
    }
    if (count(
                "SELECT COUNT(*) FROM pragma_table_info('query_trace_preparations') WHERE name IN ('trace_id','question_sha256','preparation_revision','ranking_revision','status','reason_code','manifest_sha256','attachment_count')")
            != 8
        || count(
                "SELECT COUNT(*) FROM pragma_table_info('query_trace_attachments') WHERE name IN ('trace_id','ordinal','source_sha256','media_kind','compiler_revision','content_sha256','text_code_points','visual_count','selected_image_count','visual_sampled')")
            != 10
        || count(
                "SELECT COUNT(*) FROM pragma_table_info('query_trace_attachment_images') WHERE name IN ('trace_id','attachment_ordinal','ordinal','image_sha256')")
            != 4
        || count(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name='query_traces_query_preparation_complete'")
            != 1) {
      throw new IllegalStateException("Unsupported Java query preparation trace shape");
    }
  }

  private void verifySynopsisHierarchyFormat() {
    if (count(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='synopsis_tasks' AND sql LIKE '%input_count BETWEEN 0 AND 4096%'")
            != 1
        || count(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='synopsis_input_evidence' AND sql LIKE '%ordinal BETWEEN 0 AND 4095%'")
            != 1) {
      throw new IllegalStateException("Unsupported Java complete synopsis input schema");
    }
  }

  /** Adds independent subtitle authority without changing any released migration body. */
  void migrateVersionFifteen() {
    transaction(
        () -> {
          videoSubtitleAuthoritySchema();
          replaceTriggerFragment(
              "video_compilations_identity",
              "GLOB 'java-video-compiler-v[12]:*'",
              "GLOB 'java-video-compiler-v[123]:*'");
          for (String table :
              List.of("corpus_pages", "corpus_segments", "image_evidence", "audio_compilations")) {
            replaceTriggerFragment(
                table + "_no_video_identity",
                "GLOB 'java-video-compiler-v[12]:*'",
                "GLOB 'java-video-compiler-v[123]:*'");
          }
          replaceTriggerFragment(
              "video_ocr_compilations_identity",
              "LIKE 'java-video-compiler-v2:%'",
              "GLOB 'java-video-compiler-v[23]:*'");
          execute(
              "CREATE TRIGGER corpus_revision_video_subtitle_complete BEFORE UPDATE OF parsed_at ON corpus_revisions"
                  + " WHEN NEW.parser_revision LIKE 'java-video-compiler-v3:%' AND NEW.parsed_at IS NOT NULL AND NOT ("
                  + videoPreparationComplete("NEW")
                  + " AND "
                  + videoSubtitleComplete("NEW")
                  + ") BEGIN SELECT RAISE(ABORT,'incomplete video subtitle preparation'); END");
          videoSubtitlePublicationSchema();
          videoSubtitleTraceSchema();
          videoSubtitleSynopsisSchema();
          for (String table :
              List.of(
                  "video_subtitle_compilations",
                  "video_subtitle_tracks",
                  "video_subtitle_cues",
                  "video_subtitle_publication_entries",
                  "video_subtitle_trace_evidence")) {
            String match =
                switch (table) {
                  case "video_subtitle_compilations" -> "revision_id=NEW.revision_id";
                  case "video_subtitle_tracks" ->
                      "id=NEW.id OR (revision_id=NEW.revision_id AND stream_index=NEW.stream_index)";
                  case "video_subtitle_cues" ->
                      "id=NEW.id OR (track_id=NEW.track_id AND ordinal=NEW.ordinal) OR (revision_id=NEW.revision_id AND index_ordinal=NEW.index_ordinal)";
                  case "video_subtitle_publication_entries" ->
                      "publication_id=NEW.publication_id AND video_subtitle_cue_id=NEW.video_subtitle_cue_id OR physical_segment_id=NEW.physical_segment_id";
                  default -> "trace_id=NEW.trace_id AND citation_ordinal=NEW.citation_ordinal";
                };
            execute(
                "CREATE TRIGGER "
                    + table
                    + "_no_replace BEFORE INSERT ON "
                    + table
                    + " WHEN EXISTS(SELECT 1 FROM "
                    + table
                    + " WHERE "
                    + match
                    + ") BEGIN SELECT RAISE(ABORT,'immutable video subtitle evidence'); END");
            for (String operation : List.of("UPDATE", "DELETE")) {
              execute(
                  "CREATE TRIGGER "
                      + table
                      + "_no_"
                      + operation.toLowerCase(java.util.Locale.ROOT)
                      + " BEFORE "
                      + operation
                      + " ON "
                      + table
                      + " BEGIN SELECT RAISE(ABORT,'immutable video subtitle evidence'); END");
            }
          }
          if (count("SELECT COUNT(*) FROM pragma_foreign_key_check") != 0) {
            throw new IllegalStateException("Subtitle migration changed foreign keys");
          }
          verifyVideoSubtitleFormat();
          verifySynopsisFormat();
          verifySynopsisHierarchyFormat();
          execute("UPDATE format_info SET version=15 WHERE format=?", FORMAT);
          execute("PRAGMA user_version=15");
          return null;
        });
  }

  private void verifyVideoSubtitleFormat() {
    for (String table :
        List.of(
            "video_subtitle_compilations",
            "video_subtitle_tracks",
            "video_subtitle_cues",
            "video_subtitle_publication_entries",
            "video_subtitle_trace_evidence")) {
      if (count("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", table) != 1
          || count(
                  "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN (?,?,?)",
                  table + "_no_replace",
                  table + "_no_update",
                  table + "_no_delete")
              != 3) {
        throw new IllegalStateException("Unsupported Java video subtitle schema");
      }
    }
    if (count(
                "SELECT COUNT(*) FROM pragma_table_info('video_subtitle_compilations') WHERE name IN ('revision_id','video_epoch_pts','video_time_base_numerator','video_time_base_denominator','track_count','cue_count','projection_count','base_manifest_sha256','native_manifest_sha256','manifest_sha256','ocr_expected','ocr_manifest_sha256','created_at')")
            != 13
        || count(
                "SELECT COUNT(*) FROM pragma_table_info('video_subtitle_trace_evidence') WHERE name IN ('trace_id','citation_ordinal','publication_id','cue_id','track_id','physical_segment_id','start_code_point','end_code_point','source_sha256','subtitle_manifest_sha256','native_manifest_sha256','track_text_sha256','payload_sha256','quote_sha256','retrieval_score','rerank_score','fact_sha256')")
            != 17
        || count(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN ('video_subtitle_compilations_identity','video_subtitle_tracks_identity','video_subtitle_cues_identity','corpus_revision_video_subtitle_complete','index_publication_video_subtitle_complete','video_subtitle_publication_entries_identity','video_subtitle_trace_evidence_identity','video_subtitle_trace_evidence_sealed')")
            != 8
        || count(
                "SELECT COUNT(*) FROM pragma_table_info('synopsis_input_evidence') WHERE name='video_subtitle_cue_id'")
            != 1
        || count(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='synopsis_input_evidence' AND sql LIKE '%VIDEO_SUBTITLE%'")
            != 1) {
      throw new IllegalStateException("Unsupported Java subtitle evidence identity schema");
    }
  }

  private void videoSubtitleAuthoritySchema() {
    execute(
        """
        CREATE TABLE video_subtitle_compilations(
          revision_id TEXT PRIMARY KEY NOT NULL REFERENCES video_compilations(revision_id) ON DELETE RESTRICT,
          video_epoch_pts INTEGER NOT NULL,video_time_base_numerator INTEGER NOT NULL CHECK(video_time_base_numerator>0),
          video_time_base_denominator INTEGER NOT NULL CHECK(video_time_base_denominator>0),
          track_count INTEGER NOT NULL CHECK(track_count BETWEEN 0 AND 4),
          cue_count INTEGER NOT NULL CHECK(cue_count BETWEEN 0 AND 2048),
          projection_count INTEGER NOT NULL CHECK(projection_count BETWEEN 0 AND cue_count),
          base_manifest_sha256 TEXT NOT NULL CHECK(length(base_manifest_sha256)=64 AND base_manifest_sha256 NOT GLOB '*[^a-f0-9]*'),
          native_manifest_sha256 TEXT NOT NULL CHECK(length(native_manifest_sha256)=64 AND native_manifest_sha256 NOT GLOB '*[^a-f0-9]*'),
          manifest_sha256 TEXT NOT NULL CHECK(length(manifest_sha256)=64 AND manifest_sha256 NOT GLOB '*[^a-f0-9]*'),
          ocr_expected INTEGER NOT NULL CHECK(ocr_expected IN (0,1)),
          ocr_manifest_sha256 TEXT CHECK(ocr_manifest_sha256 IS NULL OR (length(ocr_manifest_sha256)=64 AND ocr_manifest_sha256 NOT GLOB '*[^a-f0-9]*')),
          created_at TEXT NOT NULL,CHECK(track_count>0 OR cue_count=0),
          CHECK((ocr_expected=0 AND ocr_manifest_sha256 IS NULL) OR (ocr_expected=1 AND ocr_manifest_sha256 IS NOT NULL)))
        """);
    execute(
        """
        CREATE TRIGGER video_subtitle_compilations_identity BEFORE INSERT ON video_subtitle_compilations
        WHEN NOT EXISTS(SELECT 1 FROM video_compilations h JOIN corpus_revisions r ON r.id=h.revision_id
          WHERE h.revision_id=NEW.revision_id AND h.compiler_revision LIKE 'java-video-compiler-v3:%'
            AND h.manifest_sha256=NEW.base_manifest_sha256 AND r.parsed_at IS NULL
            AND h.projection_count+NEW.projection_count<=4096)
        BEGIN SELECT RAISE(ABORT,'invalid video subtitle preparation'); END
        """);
    execute(
        """
        CREATE TABLE video_subtitle_tracks(
          id TEXT PRIMARY KEY NOT NULL,revision_id TEXT NOT NULL REFERENCES video_subtitle_compilations(revision_id) ON DELETE RESTRICT,
          stream_index INTEGER NOT NULL CHECK(stream_index BETWEEN 0 AND 2147483647),
          codec TEXT NOT NULL CHECK(codec IN ('mov_text','subrip','webvtt')),
          time_base_numerator INTEGER NOT NULL CHECK(time_base_numerator>0),time_base_denominator INTEGER NOT NULL CHECK(time_base_denominator>0),
          language TEXT CHECK(language IS NULL OR (length(language) BETWEEN 1 AND 63 AND instr(language,char(0))=0)),
          cue_count INTEGER NOT NULL CHECK(cue_count BETWEEN 0 AND 2048),
          text TEXT NOT NULL CHECK(length(text)<=502047 AND instr(text,char(0))=0),
          text_sha256 TEXT NOT NULL CHECK(length(text_sha256)=64 AND text_sha256 NOT GLOB '*[^a-f0-9]*'),
          UNIQUE(revision_id,id),UNIQUE(revision_id,stream_index),CHECK(cue_count>0 OR length(text)=0))
        """);
    execute(
        """
        CREATE TRIGGER video_subtitle_tracks_identity BEFORE INSERT ON video_subtitle_tracks
        WHEN NOT EXISTS(SELECT 1 FROM video_subtitle_compilations h WHERE h.revision_id=NEW.revision_id
          AND (SELECT COUNT(*) FROM video_subtitle_tracks WHERE revision_id=NEW.revision_id)<h.track_count
          AND NEW.cue_count+(SELECT COALESCE(SUM(cue_count),0) FROM video_subtitle_tracks WHERE revision_id=NEW.revision_id)<=h.cue_count)
          OR NEW.stream_index<=COALESCE((SELECT MAX(stream_index) FROM video_subtitle_tracks WHERE revision_id=NEW.revision_id),-1)
        BEGIN SELECT RAISE(ABORT,'invalid video subtitle track'); END
        """);
    execute(
        """
        CREATE TABLE video_subtitle_cues(
          id TEXT PRIMARY KEY NOT NULL,revision_id TEXT NOT NULL,track_id TEXT NOT NULL,
          stream_index INTEGER NOT NULL CHECK(stream_index BETWEEN 0 AND 2147483647),ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 2047),
          pts INTEGER NOT NULL,duration INTEGER NOT NULL CHECK(duration>=0),
          text TEXT NOT NULL CHECK(length(text)<=4096 AND length(CAST(text AS BLOB))<=16384 AND instr(text,char(0))=0),
          text_sha256 TEXT NOT NULL CHECK(length(text_sha256)=64 AND text_sha256 NOT GLOB '*[^a-f0-9]*'),
          payload_sha256 TEXT NOT NULL CHECK(length(payload_sha256)=64 AND payload_sha256 NOT GLOB '*[^a-f0-9]*'),
          start_offset INTEGER NOT NULL CHECK(start_offset>=0),end_offset INTEGER NOT NULL CHECK(end_offset>=start_offset AND end_offset-start_offset=length(text)),
          start_us INTEGER,end_us INTEGER,index_ordinal INTEGER CHECK(index_ordinal BETWEEN 0 AND 2047),
          UNIQUE(revision_id,id),UNIQUE(track_id,ordinal),UNIQUE(revision_id,index_ordinal),
          FOREIGN KEY(revision_id,track_id) REFERENCES video_subtitle_tracks(revision_id,id) ON DELETE RESTRICT,
          CHECK((index_ordinal IS NULL AND start_us IS NULL AND end_us IS NULL AND length(trim(text,char(9,10,13,32,5760,8192,8193,8194,8195,8196,8197,8198,8200,8201,8202,8232,8233,8287,12288)))=0)
            OR (index_ordinal IS NOT NULL AND start_us IS NOT NULL AND end_us IS NOT NULL AND start_us>=0 AND end_us>start_us AND end_us<=600000000 AND duration>0 AND length(trim(text,char(9,10,13,32,5760,8192,8193,8194,8195,8196,8197,8198,8200,8201,8202,8232,8233,8287,12288)))>0)))
        """);
    execute(
        """
        CREATE TRIGGER video_subtitle_cues_identity BEFORE INSERT ON video_subtitle_cues
        WHEN NOT EXISTS(SELECT 1 FROM video_subtitle_tracks t JOIN video_compilations h ON h.revision_id=t.revision_id
          WHERE t.id=NEW.track_id AND t.revision_id=NEW.revision_id AND t.stream_index=NEW.stream_index
            AND NEW.ordinal<t.cue_count AND NEW.end_offset<=length(t.text)
            AND substr(t.text,NEW.start_offset+1,NEW.end_offset-NEW.start_offset)=NEW.text
            AND (NEW.end_us IS NULL OR NEW.end_us<=h.duration_us))
          OR NEW.ordinal!=(SELECT COUNT(*) FROM video_subtitle_cues WHERE track_id=NEW.track_id)
          OR NEW.start_offset!=COALESCE((SELECT MAX(end_offset)+1 FROM video_subtitle_cues WHERE track_id=NEW.track_id),0)
          OR (NEW.ordinal>0 AND NOT EXISTS(SELECT 1 FROM video_subtitle_tracks WHERE id=NEW.track_id AND substr(text,NEW.start_offset,1)=char(10)))
          OR (NEW.index_ordinal IS NOT NULL AND NEW.index_ordinal!=(SELECT COUNT(*) FROM video_subtitle_cues WHERE revision_id=NEW.revision_id AND index_ordinal IS NOT NULL))
          OR length(NEW.text)+(SELECT COALESCE(SUM(length(text)),0) FROM video_subtitle_cues WHERE revision_id=NEW.revision_id)>500000
        BEGIN SELECT RAISE(ABORT,'invalid video subtitle cue'); END
        """);
    for (String table :
        List.of("video_subtitle_compilations", "video_subtitle_tracks", "video_subtitle_cues")) {
      execute(
          "CREATE TRIGGER "
              + table
              + "_frozen BEFORE INSERT ON "
              + table
              + " WHEN EXISTS(SELECT 1 FROM corpus_revisions WHERE id=NEW.revision_id AND parsed_at IS NOT NULL)"
              + " BEGIN SELECT RAISE(ABORT,'immutable video subtitle preparation'); END");
    }
  }

  private String videoSubtitleComplete(String revision) {
    return ("""
        EXISTS(SELECT 1 FROM video_subtitle_compilations s JOIN video_compilations h ON h.revision_id=s.revision_id
          WHERE s.revision_id=%1$s.id AND s.base_manifest_sha256=h.manifest_sha256
            AND s.track_count=(SELECT COUNT(*) FROM video_subtitle_tracks WHERE revision_id=s.revision_id)
            AND s.cue_count=(SELECT COUNT(*) FROM video_subtitle_cues WHERE revision_id=s.revision_id)
            AND s.cue_count=(SELECT COALESCE(SUM(cue_count),0) FROM video_subtitle_tracks WHERE revision_id=s.revision_id)
            AND s.projection_count=(SELECT COUNT(*) FROM video_subtitle_cues WHERE revision_id=s.revision_id AND index_ordinal IS NOT NULL)
            AND h.projection_count+s.projection_count+COALESCE((SELECT projection_count FROM video_ocr_compilations WHERE revision_id=s.revision_id),0)<=4096
            AND NOT EXISTS(SELECT 1 FROM video_subtitle_tracks t WHERE t.revision_id=s.revision_id AND (
              t.cue_count!=(SELECT COUNT(*) FROM video_subtitle_cues WHERE track_id=t.id)
              OR length(t.text)!=(SELECT COALESCE(SUM(length(text)),0) FROM video_subtitle_cues WHERE track_id=t.id)+max(t.cue_count-1,0)
              OR (t.cue_count>0 AND (t.cue_count-1!=(SELECT MAX(ordinal) FROM video_subtitle_cues WHERE track_id=t.id)
                OR length(t.text)!=(SELECT MAX(end_offset) FROM video_subtitle_cues WHERE track_id=t.id)))))
            AND NOT EXISTS(SELECT 1 FROM video_subtitle_cues c WHERE c.revision_id=s.revision_id AND c.index_ordinal IS NOT NULL
              AND c.index_ordinal!=(SELECT COUNT(*) FROM video_subtitle_cues previous WHERE previous.revision_id=c.revision_id AND previous.index_ordinal IS NOT NULL
                AND (previous.stream_index<c.stream_index OR (previous.stream_index=c.stream_index AND previous.ordinal<c.ordinal))))
            AND (SELECT COALESCE(SUM(length(text)),0) FROM video_subtitle_cues WHERE revision_id=s.revision_id)<=500000
            AND (SELECT COALESCE(SUM(length(recall_text)),0) FROM video_frames WHERE revision_id=s.revision_id)
              +(SELECT COALESCE(SUM(length(text)),0) FROM video_transcript_spans WHERE revision_id=s.revision_id)
              +(SELECT COALESCE(SUM(length(text)),0) FROM video_ocr_segments WHERE revision_id=s.revision_id)
              +(SELECT COALESCE(SUM(length(text)),0) FROM video_subtitle_cues WHERE revision_id=s.revision_id AND index_ordinal IS NOT NULL)<=1500000
            AND ((s.ocr_expected=0 AND NOT EXISTS(SELECT 1 FROM video_ocr_compilations WHERE revision_id=s.revision_id))
              OR (s.ocr_expected=1 AND EXISTS(SELECT 1 FROM video_ocr_compilations WHERE revision_id=s.revision_id AND manifest_sha256=s.ocr_manifest_sha256) AND %2$s)))
        """)
        .formatted(revision, videoOcrComplete(revision));
  }

  private void videoSubtitlePublicationSchema() {
    execute(
        """
        CREATE TABLE video_subtitle_publication_entries(
          publication_id TEXT NOT NULL REFERENCES index_publications(id) ON DELETE RESTRICT,
          video_subtitle_cue_id TEXT NOT NULL REFERENCES video_subtitle_cues(id) ON DELETE RESTRICT,
          physical_segment_id TEXT NOT NULL UNIQUE CHECK(length(physical_segment_id) BETWEEN 1 AND 128),
          entry_sha256 TEXT NOT NULL CHECK(length(entry_sha256)=64 AND entry_sha256 NOT GLOB '*[^a-f0-9]*'),
          PRIMARY KEY(publication_id,video_subtitle_cue_id))
        """);
    String collisions = "";
    for (String table :
        List.of(
            "index_publication_entries",
            "image_publication_entries",
            "audio_publication_entries",
            "video_frame_publication_entries",
            "video_transcript_publication_entries",
            "video_ocr_publication_entries")) {
      collisions +=
          " OR EXISTS(SELECT 1 FROM "
              + table
              + " WHERE physical_segment_id=NEW.physical_segment_id)";
      execute(
          "CREATE TRIGGER "
              + table
              + "_no_subtitle_collision BEFORE INSERT ON "
              + table
              + " WHEN EXISTS(SELECT 1 FROM video_subtitle_publication_entries WHERE physical_segment_id=NEW.physical_segment_id)"
              + " BEGIN SELECT RAISE(ABORT,'projection identity collision'); END");
    }
    execute(
        "CREATE TRIGGER video_subtitle_publication_entries_identity BEFORE INSERT ON video_subtitle_publication_entries"
            + " WHEN NOT EXISTS(SELECT 1 FROM index_publications p JOIN indexing_jobs j ON j.id=p.job_id AND j.state='processing' AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id"
            + " JOIN video_subtitle_cues c ON c.revision_id=p.revision_id AND c.id=NEW.video_subtitle_cue_id AND c.index_ordinal IS NOT NULL WHERE p.id=NEW.publication_id)"
            + collisions
            + " BEGIN SELECT RAISE(ABORT,'invalid video subtitle publication'); END");
    replaceTriggerFragment(
        "index_publication_identity",
        "+(SELECT COUNT(*) FROM video_ocr_segments WHERE revision_id=r.id)",
        "+(SELECT COUNT(*) FROM video_ocr_segments WHERE revision_id=r.id)+(SELECT COUNT(*) FROM video_subtitle_cues WHERE revision_id=r.id AND index_ordinal IS NOT NULL)");
    for (String trigger :
        List.of("active_corpus_publication_complete", "indexing_published_state")) {
      replaceTriggerFragment(
          trigger,
          "+(SELECT COUNT(*) FROM video_ocr_publication_entries WHERE publication_id=p.id)",
          "+(SELECT COUNT(*) FROM video_ocr_publication_entries WHERE publication_id=p.id)+(SELECT COUNT(*) FROM video_subtitle_publication_entries WHERE publication_id=p.id)");
    }
    for (String trigger :
        List.of(
            "index_publication_identity",
            "active_corpus_publication_complete",
            "indexing_published_state")) {
      replaceTriggerFragment(
          trigger, "GLOB 'java-video-compiler-v[12]:*'", "GLOB 'java-video-compiler-v[123]:*'");
    }
    execute(
        "CREATE TRIGGER index_publication_video_subtitle_complete BEFORE INSERT ON index_publications"
            + " WHEN NEW.parser_revision LIKE 'java-video-compiler-v3:%' AND NOT EXISTS(SELECT 1 FROM corpus_revisions r WHERE r.id=NEW.revision_id AND r.parsed_at IS NOT NULL AND "
            + videoSubtitleComplete("r")
            + ") BEGIN SELECT RAISE(ABORT,'incomplete video subtitle publication'); END");
  }

  private void videoSubtitleTraceSchema() {
    execute(
        """
        CREATE TABLE video_subtitle_trace_evidence(
          trace_id TEXT NOT NULL,citation_ordinal INTEGER NOT NULL CHECK(citation_ordinal BETWEEN 1 AND 32),
          publication_id TEXT NOT NULL,cue_id TEXT NOT NULL,track_id TEXT NOT NULL REFERENCES video_subtitle_tracks(id) ON DELETE RESTRICT,
          physical_segment_id TEXT NOT NULL CHECK(length(physical_segment_id) BETWEEN 1 AND 128),
          start_code_point INTEGER NOT NULL CHECK(start_code_point>=0),end_code_point INTEGER NOT NULL CHECK(end_code_point>start_code_point AND end_code_point-start_code_point<=1200),
          source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64 AND source_sha256 NOT GLOB '*[^a-f0-9]*'),
          subtitle_manifest_sha256 TEXT NOT NULL CHECK(length(subtitle_manifest_sha256)=64 AND subtitle_manifest_sha256 NOT GLOB '*[^a-f0-9]*'),
          native_manifest_sha256 TEXT NOT NULL CHECK(length(native_manifest_sha256)=64 AND native_manifest_sha256 NOT GLOB '*[^a-f0-9]*'),
          track_text_sha256 TEXT NOT NULL CHECK(length(track_text_sha256)=64 AND track_text_sha256 NOT GLOB '*[^a-f0-9]*'),
          payload_sha256 TEXT NOT NULL CHECK(length(payload_sha256)=64 AND payload_sha256 NOT GLOB '*[^a-f0-9]*'),
          quote_sha256 TEXT NOT NULL CHECK(length(quote_sha256)=64 AND quote_sha256 NOT GLOB '*[^a-f0-9]*'),
          retrieval_score REAL NOT NULL CHECK(retrieval_score BETWEEN -1.7976931348623157e308 AND 1.7976931348623157e308),
          rerank_score REAL NOT NULL CHECK(rerank_score BETWEEN -1.7976931348623157e308 AND 1.7976931348623157e308),
          fact_sha256 TEXT NOT NULL CHECK(length(fact_sha256) BETWEEN 64 AND 519 AND fact_sha256 NOT GLOB '*[^a-f0-9,]*'),
          PRIMARY KEY(trace_id,citation_ordinal),
          FOREIGN KEY(trace_id,publication_id) REFERENCES query_trace_documents(trace_id,publication_id) ON DELETE RESTRICT,
          FOREIGN KEY(publication_id,cue_id) REFERENCES video_subtitle_publication_entries(publication_id,video_subtitle_cue_id) ON DELETE RESTRICT)
        """);
    execute(
        """
        CREATE TRIGGER video_subtitle_trace_evidence_identity BEFORE INSERT ON video_subtitle_trace_evidence
        WHEN NOT EXISTS(SELECT 1 FROM index_publications p JOIN video_subtitle_publication_entries e ON e.publication_id=p.id
          JOIN video_subtitle_cues c ON c.id=e.video_subtitle_cue_id AND c.revision_id=p.revision_id AND c.index_ordinal IS NOT NULL
          JOIN video_subtitle_tracks t ON t.id=c.track_id AND t.revision_id=p.revision_id
          JOIN video_subtitle_compilations s ON s.revision_id=p.revision_id
          WHERE p.id=NEW.publication_id AND c.id=NEW.cue_id AND t.id=NEW.track_id AND e.physical_segment_id=NEW.physical_segment_id
            AND p.source_sha256=NEW.source_sha256 AND s.manifest_sha256=NEW.subtitle_manifest_sha256
            AND s.native_manifest_sha256=NEW.native_manifest_sha256 AND t.text_sha256=NEW.track_text_sha256
            AND c.payload_sha256=NEW.payload_sha256 AND NEW.start_code_point>=c.start_offset AND NEW.end_code_point<=c.end_offset)
        BEGIN SELECT RAISE(ABORT,'invalid video subtitle citation'); END
        """);
    execute(
        "CREATE TRIGGER video_subtitle_trace_evidence_sealed BEFORE INSERT ON video_subtitle_trace_evidence WHEN EXISTS(SELECT 1 FROM query_traces WHERE id=NEW.trace_id) BEGIN SELECT RAISE(ABORT,'sealed video subtitle trace'); END");
    replaceTriggerFragment(
        "query_traces_complete",
        "+(SELECT COUNT(*) FROM video_ocr_trace_evidence WHERE trace_id=NEW.id)",
        "+(SELECT COUNT(*) FROM video_ocr_trace_evidence WHERE trace_id=NEW.id)+(SELECT COUNT(*) FROM video_subtitle_trace_evidence WHERE trace_id=NEW.id)");
    replaceTriggerFragment(
        "query_traces_complete",
        "UNION ALL SELECT citation_ordinal FROM video_ocr_trace_evidence WHERE trace_id=NEW.id",
        "UNION ALL SELECT citation_ordinal FROM video_ocr_trace_evidence WHERE trace_id=NEW.id UNION ALL SELECT citation_ordinal FROM video_subtitle_trace_evidence WHERE trace_id=NEW.id");
  }

  /** Rebuilds the connected synopsis tables while preserving all old rows and their seals. */
  private void videoSubtitleSynopsisSchema() {
    var tables =
        List.of(
            "synopsis_tasks", "synopsis_input_evidence", "synopsis_entries", "synopsis_references");
    var definitions = new LinkedHashMap<String, String>();
    var triggers = new LinkedHashMap<String, String>();
    var indexes = new ArrayList<String>();
    for (var row :
        store.rows(
            "SELECT type,name,sql FROM sqlite_master WHERE tbl_name IN ('synopsis_tasks','synopsis_input_evidence','synopsis_entries','synopsis_references') AND sql IS NOT NULL ORDER BY name")) {
      String name = AuthorityRows.text(row, "name");
      String sql = AuthorityRows.text(row, "sql");
      switch (AuthorityRows.text(row, "type")) {
        case "table" -> definitions.put(name, sql);
        case "trigger" -> triggers.put(name, sql);
        case "index" -> indexes.add(sql);
        default -> throw new IllegalStateException("Unsupported synopsis schema object");
      }
    }
    for (String table : tables) {
      String sql = definitions.get(table);
      if (sql == null) {
        throw new IllegalStateException("Incomplete synopsis schema");
      }
      String columns =
          store.rows("SELECT name FROM pragma_table_info(?) ORDER BY cid", table).stream()
              .map(row -> AuthorityRows.text(row, "name"))
              .collect(java.util.stream.Collectors.joining(","));
      if (table.equals("synopsis_input_evidence")) {
        if (!sql.contains("'VIDEO_OCR')")
            || !sql.contains("video_ocr_segment_id TEXT")
            || !sql.contains("(video_ocr_segment_id IS NOT NULL)=1")) {
          throw new IllegalStateException("Unsupported synopsis evidence kinds");
        }
        sql =
            sql.replace("'VIDEO_OCR')", "'VIDEO_OCR','VIDEO_SUBTITLE')")
                .replace(
                    "video_ocr_segment_id TEXT",
                    "video_ocr_segment_id TEXT,video_subtitle_cue_id TEXT")
                .replace(
                    "(video_ocr_segment_id IS NOT NULL)=1",
                    "(video_ocr_segment_id IS NOT NULL)+(video_subtitle_cue_id IS NOT NULL)=1")
                .replace(
                    "CHECK((kind IN ('TEXT'",
                    "FOREIGN KEY(publication_id,video_subtitle_cue_id) REFERENCES video_subtitle_publication_entries(publication_id,video_subtitle_cue_id) ON DELETE RESTRICT, CHECK((kind IN ('TEXT'");
      }
      for (String related : tables) {
        sql = sql.replace(related, related + "_v15");
      }
      execute(sql);
      execute(
          "INSERT INTO " + table + "_v15(" + columns + ") SELECT " + columns + " FROM " + table);
      if (count("SELECT COUNT(*) FROM " + table) != count("SELECT COUNT(*) FROM " + table + "_v15")
          || count(
                  "SELECT COUNT(*) FROM (SELECT "
                      + columns
                      + " FROM "
                      + table
                      + " EXCEPT SELECT "
                      + columns
                      + " FROM "
                      + table
                      + "_v15)")
              != 0
          || count(
                  "SELECT COUNT(*) FROM (SELECT "
                      + columns
                      + " FROM "
                      + table
                      + "_v15 EXCEPT SELECT "
                      + columns
                      + " FROM "
                      + table
                      + ")")
              != 0) {
        throw new IllegalStateException("Subtitle migration changed synopsis history");
      }
    }
    for (String name : triggers.keySet()) {
      execute("DROP TRIGGER " + name);
    }
    for (String table : tables.reversed()) {
      execute("DROP TABLE " + table);
    }
    for (String table : tables) {
      execute("ALTER TABLE " + table + "_v15 RENAME TO " + table);
    }
    for (String sql : indexes) {
      execute(sql);
    }
    for (var entry : triggers.entrySet()) {
      String sql = entry.getValue();
      if (entry.getKey().equals("synopsis_input_evidence_identity")) {
        String previous = "AND f.presentation_us+f.duration_us=NEW.end_us)))";
        if (!sql.contains(previous)) {
          throw new IllegalStateException("Unsupported synopsis identity trigger");
        }
        sql =
            sql.replace(
                previous,
                "AND f.presentation_us+f.duration_us=NEW.end_us))"
                    + " OR (NEW.kind='VIDEO_SUBTITLE' AND EXISTS(SELECT 1 FROM video_subtitle_publication_entries e JOIN video_subtitle_cues c ON c.id=e.video_subtitle_cue_id"
                    + " WHERE e.publication_id=NEW.publication_id AND e.video_subtitle_cue_id=NEW.video_subtitle_cue_id AND e.physical_segment_id=NEW.physical_segment_id AND c.text_sha256=NEW.content_sha256 AND c.start_us=NEW.start_us AND c.end_us=NEW.end_us)))");
      }
      execute(sql);
    }
  }

  /** Expands only complete input capacity, preserving every existing row and sealed relation. */
  void migrateVersionFourteen() {
    transaction(
        () -> {
          var tables =
              List.of(
                  "synopsis_tasks",
                  "synopsis_input_evidence",
                  "synopsis_entries",
                  "synopsis_references");
          var definitions = new LinkedHashMap<String, String>();
          var triggers = new LinkedHashMap<String, String>();
          var indexes = new ArrayList<String>();
          for (var row :
              store.rows(
                  "SELECT type,name,sql FROM sqlite_master WHERE tbl_name IN ('synopsis_tasks','synopsis_input_evidence','synopsis_entries','synopsis_references') AND sql IS NOT NULL ORDER BY name")) {
            String name = (String) row.get("name");
            String sql = (String) row.get("sql");
            switch ((String) row.get("type")) {
              case "table" -> definitions.put(name, sql);
              case "trigger" -> triggers.put(name, sql);
              case "index" -> indexes.add(sql);
              default -> throw new IllegalStateException("Unsupported synopsis schema object");
            }
          }
          for (String table : tables) {
            String sql = definitions.get(table);
            if (sql == null) {
              throw new IllegalStateException("Incomplete synopsis schema");
            }
            if (table.equals("synopsis_tasks")) {
              if (!sql.contains("input_count BETWEEN 0 AND 64")) {
                throw new IllegalStateException("Unsupported synopsis input capacity");
              }
              sql = sql.replace("input_count BETWEEN 0 AND 64", "input_count BETWEEN 0 AND 4096");
            } else if (table.equals("synopsis_input_evidence")) {
              if (!sql.contains("ordinal BETWEEN 0 AND 63")) {
                throw new IllegalStateException("Unsupported synopsis input ordinals");
              }
              sql = sql.replace("ordinal BETWEEN 0 AND 63", "ordinal BETWEEN 0 AND 4095");
            }
            // All four replacement tables point to one another, never to a table being removed.
            for (String related : tables) {
              sql = sql.replace(related, related + "_v14");
            }
            execute(sql);
            execute("INSERT INTO " + table + "_v14 SELECT * FROM " + table);
            if (count("SELECT COUNT(*) FROM " + table)
                    != count("SELECT COUNT(*) FROM " + table + "_v14")
                || count(
                        "SELECT COUNT(*) FROM (SELECT * FROM "
                            + table
                            + " EXCEPT SELECT * FROM "
                            + table
                            + "_v14)")
                    != 0) {
              throw new IllegalStateException("Synopsis migration changed historical rows");
            }
          }
          // DDL remains in this same transaction; any failed copy, rename or seal rolls it all
          // back.
          for (String name : triggers.keySet()) {
            execute("DROP TRIGGER " + name);
          }
          for (String table : tables.reversed()) {
            execute("DROP TABLE " + table);
          }
          for (String table : tables) {
            execute("ALTER TABLE " + table + "_v14 RENAME TO " + table);
          }
          for (String sql : indexes) {
            execute(sql);
          }
          for (String sql : triggers.values()) {
            execute(sql);
          }
          if (count("SELECT COUNT(*) FROM pragma_foreign_key_check") != 0) {
            throw new IllegalStateException("Synopsis migration changed foreign keys");
          }
          verifySynopsisFormat();
          verifySynopsisHierarchyFormat();
          execute("UPDATE format_info SET version=14 WHERE format=?", FORMAT);
          execute("PRAGMA user_version=14");
          return null;
        });
  }

  private void verifySynopsisFormat() {
    for (String table :
        List.of(
            "synopsis_tasks",
            "synopsis_input_evidence",
            "synopsis_entries",
            "synopsis_references")) {
      if (count("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", table) != 1
          || count(
                  "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN (?,?)",
                  table + "_no_replace",
                  table + "_no_delete")
              != 2) {
        throw new IllegalStateException("Unsupported Java synopsis schema");
      }
    }
    if (count(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN ('synopsis_tasks_identity','synopsis_tasks_transition','synopsis_tasks_seal','synopsis_input_evidence_identity','synopsis_entries_identity','synopsis_references_identity','synopsis_input_evidence_no_update','synopsis_entries_no_update','synopsis_references_no_update')")
            != 9
        || count(
                "SELECT COUNT(*) FROM pragma_table_info('synopsis_tasks') WHERE name IN ('id','publication_id','claim_token_sha256','input_fingerprint','input_count','entry_count')")
            != 6) {
      throw new IllegalStateException("Unsupported Java synopsis seal schema");
    }
  }

  void migrateVersionThirteen() {
    transaction(
        () -> {
          execute(
              """
          CREATE TABLE synopsis_tasks(
            id TEXT PRIMARY KEY NOT NULL CHECK(length(id) BETWEEN 1 AND 128),
            workspace_id TEXT NOT NULL,document_id TEXT NOT NULL,revision_id TEXT NOT NULL,publication_id TEXT NOT NULL,
            model_revision TEXT NOT NULL CHECK(length(model_revision) BETWEEN 1 AND 200),
            policy_revision TEXT NOT NULL CHECK(length(policy_revision) BETWEEN 1 AND 200),
            state TEXT NOT NULL CHECK(state IN ('queued','processing','available','unavailable','cancelled')),
            claim_token_sha256 TEXT CHECK(claim_token_sha256 IS NULL OR (length(claim_token_sha256)=64 AND claim_token_sha256 NOT GLOB '*[^a-f0-9]*')),
            input_fingerprint TEXT CHECK(input_fingerprint IS NULL OR (length(input_fingerprint)=64 AND input_fingerprint NOT GLOB '*[^a-f0-9]*')),
            input_count INTEGER NOT NULL DEFAULT 0 CHECK(input_count BETWEEN 0 AND 64),
            entry_count INTEGER NOT NULL DEFAULT 0 CHECK(entry_count BETWEEN 0 AND 32),
            created_by TEXT NOT NULL,error_code TEXT CHECK(error_code IS NULL OR (length(error_code) BETWEEN 1 AND 80 AND error_code NOT GLOB '*[^a-z0-9_]*')),
            created_at TEXT NOT NULL,updated_at TEXT NOT NULL,UNIQUE(id,publication_id),
            FOREIGN KEY(publication_id,document_id,revision_id) REFERENCES index_publications(id,document_id,revision_id) ON DELETE RESTRICT,
            CHECK((state='processing' AND claim_token_sha256 IS NOT NULL) OR (state!='processing' AND claim_token_sha256 IS NULL)),
            CHECK((state IN ('unavailable','cancelled') AND error_code IS NOT NULL) OR (state NOT IN ('unavailable','cancelled') AND error_code IS NULL)),
            CHECK((state='available' AND entry_count BETWEEN 3 AND 32) OR (state!='available' AND entry_count=0)),
            CHECK((input_count=0 AND input_fingerprint IS NULL) OR (input_count>0 AND input_fingerprint IS NOT NULL)))
          """);
          execute(
              "CREATE UNIQUE INDEX synopsis_one_pending ON synopsis_tasks(document_id) WHERE state IN ('queued','processing')");
          execute(
              "CREATE UNIQUE INDEX synopsis_one_processing ON synopsis_tasks(workspace_id) WHERE state='processing'");
          execute(
              "CREATE INDEX synopsis_queue ON synopsis_tasks(workspace_id,state,created_at,id)");
          execute(
              """
          CREATE TABLE synopsis_input_evidence(
            task_id TEXT NOT NULL,ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 63),publication_id TEXT NOT NULL,
            physical_segment_id TEXT NOT NULL,kind TEXT NOT NULL CHECK(kind IN ('TEXT','IMAGE_OCR','IMAGE','AUDIO_TRANSCRIPT','VIDEO_FRAME','VIDEO_TRANSCRIPT','VIDEO_OCR')),
            content_sha256 TEXT NOT NULL CHECK(length(content_sha256)=64 AND content_sha256 NOT GLOB '*[^a-f0-9]*'),
            start_us INTEGER,end_us INTEGER,
            source_segment_id TEXT,image_evidence_id TEXT,audio_span_id TEXT,video_frame_id TEXT,video_transcript_span_id TEXT,video_ocr_segment_id TEXT,
            PRIMARY KEY(task_id,ordinal),UNIQUE(task_id,physical_segment_id),
            FOREIGN KEY(task_id,publication_id) REFERENCES synopsis_tasks(id,publication_id) ON DELETE RESTRICT,
            FOREIGN KEY(publication_id,source_segment_id) REFERENCES index_publication_entries(publication_id,source_segment_id) ON DELETE RESTRICT,
            FOREIGN KEY(publication_id,image_evidence_id) REFERENCES image_publication_entries(publication_id,image_evidence_id) ON DELETE RESTRICT,
            FOREIGN KEY(publication_id,audio_span_id) REFERENCES audio_publication_entries(publication_id,audio_span_id) ON DELETE RESTRICT,
            FOREIGN KEY(publication_id,video_frame_id) REFERENCES video_frame_publication_entries(publication_id,video_frame_id) ON DELETE RESTRICT,
            FOREIGN KEY(publication_id,video_transcript_span_id) REFERENCES video_transcript_publication_entries(publication_id,video_transcript_span_id) ON DELETE RESTRICT,
            FOREIGN KEY(publication_id,video_ocr_segment_id) REFERENCES video_ocr_publication_entries(publication_id,video_ocr_segment_id) ON DELETE RESTRICT,
            CHECK((kind IN ('TEXT','IMAGE_OCR','IMAGE') AND start_us IS NULL AND end_us IS NULL)
              OR (kind IN ('AUDIO_TRANSCRIPT','VIDEO_FRAME','VIDEO_TRANSCRIPT','VIDEO_OCR') AND start_us IS NOT NULL AND end_us IS NOT NULL AND start_us>=0 AND end_us>start_us)),
            CHECK((source_segment_id IS NOT NULL)+(image_evidence_id IS NOT NULL)+(audio_span_id IS NOT NULL)+(video_frame_id IS NOT NULL)+(video_transcript_span_id IS NOT NULL)+(video_ocr_segment_id IS NOT NULL)=1))
          """);
          execute(
              """
          CREATE TABLE synopsis_entries(
            task_id TEXT NOT NULL REFERENCES synopsis_tasks(id) ON DELETE RESTRICT,
            ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 31),
            section TEXT NOT NULL CHECK(section IN ('OVERVIEW','TOPIC','TERM','TIMELINE')),
            text TEXT NOT NULL CHECK(length(text) BETWEEN 1 AND 1024 AND instr(text,char(0))=0),
            start_us INTEGER,end_us INTEGER,reference_count INTEGER NOT NULL CHECK(reference_count BETWEEN 1 AND 8),
            PRIMARY KEY(task_id,ordinal),
            CHECK((section='TIMELINE' AND start_us IS NOT NULL AND end_us IS NOT NULL AND start_us>=0 AND end_us>start_us)
              OR (section!='TIMELINE' AND start_us IS NULL AND end_us IS NULL)))
          """);
          execute(
              """
          CREATE TABLE synopsis_references(
            task_id TEXT NOT NULL,entry_ordinal INTEGER NOT NULL,source_ordinal INTEGER NOT NULL CHECK(source_ordinal BETWEEN 0 AND 7),
            physical_segment_id TEXT NOT NULL,PRIMARY KEY(task_id,entry_ordinal,source_ordinal),UNIQUE(task_id,entry_ordinal,physical_segment_id),
            FOREIGN KEY(task_id,entry_ordinal) REFERENCES synopsis_entries(task_id,ordinal) ON DELETE RESTRICT,
            FOREIGN KEY(task_id,physical_segment_id) REFERENCES synopsis_input_evidence(task_id,physical_segment_id) ON DELETE RESTRICT)
          """);
          execute(
              """
          CREATE TRIGGER synopsis_tasks_create BEFORE INSERT ON synopsis_tasks
          WHEN NEW.state!='queued' OR NEW.input_count!=0 OR NEW.entry_count!=0
            OR NOT EXISTS(SELECT 1 FROM documents d JOIN active_corpus_publications a ON a.document_id=d.id
              WHERE d.id=NEW.document_id AND d.workspace_id=NEW.workspace_id AND a.publication_id=NEW.publication_id)
          BEGIN SELECT RAISE(ABORT,'invalid synopsis task'); END
          """);
          execute(
              """
          CREATE TRIGGER synopsis_tasks_identity BEFORE UPDATE OF id,workspace_id,document_id,revision_id,publication_id,model_revision,policy_revision,created_by,created_at ON synopsis_tasks
          BEGIN SELECT RAISE(ABORT,'immutable synopsis identity'); END
          """);
          execute(
              """
          CREATE TRIGGER synopsis_tasks_transition BEFORE UPDATE ON synopsis_tasks
          WHEN NOT ((OLD.state='queued' AND NEW.state IN ('processing','unavailable','cancelled')) OR (OLD.state='processing' AND NEW.state IN ('available','unavailable','cancelled')))
            OR (OLD.input_fingerprint IS NOT NULL AND (NEW.input_fingerprint IS NOT OLD.input_fingerprint OR NEW.input_count!=OLD.input_count))
          BEGIN SELECT RAISE(ABORT,'invalid synopsis transition'); END
          """);
          execute(
              """
          CREATE TRIGGER synopsis_tasks_seal BEFORE UPDATE ON synopsis_tasks
          WHEN (NEW.state='processing' AND (NEW.input_count!=(SELECT segment_count FROM index_publications WHERE id=NEW.publication_id)
              OR NEW.input_count!=(SELECT COUNT(*) FROM synopsis_input_evidence WHERE task_id=NEW.id)))
            OR (NEW.state IN ('unavailable','cancelled') AND EXISTS(SELECT 1 FROM synopsis_entries WHERE task_id=NEW.id))
            OR (NEW.state='available' AND (
              NEW.entry_count!=(SELECT COUNT(*) FROM synopsis_entries WHERE task_id=NEW.id)
              OR (SELECT COUNT(*) FROM synopsis_entries WHERE task_id=NEW.id AND section='OVERVIEW')!=1
              OR NOT EXISTS(SELECT 1 FROM synopsis_entries WHERE task_id=NEW.id AND section='TOPIC')
              OR NOT EXISTS(SELECT 1 FROM synopsis_entries WHERE task_id=NEW.id AND section='TERM')
              OR EXISTS(SELECT 1 FROM synopsis_entries e WHERE e.task_id=NEW.id AND e.reference_count!=(SELECT COUNT(*) FROM synopsis_references r WHERE r.task_id=e.task_id AND r.entry_ordinal=e.ordinal))
              OR EXISTS(SELECT 1 FROM synopsis_input_evidence WHERE task_id=NEW.id AND start_us IS NOT NULL)!=EXISTS(SELECT 1 FROM synopsis_entries WHERE task_id=NEW.id AND section='TIMELINE')
              OR EXISTS(SELECT 1 FROM synopsis_entries e WHERE e.task_id=NEW.id AND e.section='TIMELINE' AND (
                e.start_us IS NOT (SELECT MIN(i.start_us) FROM synopsis_references r JOIN synopsis_input_evidence i ON i.task_id=r.task_id AND i.physical_segment_id=r.physical_segment_id WHERE r.task_id=e.task_id AND r.entry_ordinal=e.ordinal)
                OR e.end_us IS NOT (SELECT MAX(i.end_us) FROM synopsis_references r JOIN synopsis_input_evidence i ON i.task_id=r.task_id AND i.physical_segment_id=r.physical_segment_id WHERE r.task_id=e.task_id AND r.entry_ordinal=e.ordinal)
                OR EXISTS(SELECT 1 FROM synopsis_references r JOIN synopsis_input_evidence i ON i.task_id=r.task_id AND i.physical_segment_id=r.physical_segment_id WHERE r.task_id=e.task_id AND r.entry_ordinal=e.ordinal AND i.start_us IS NULL)))))
          BEGIN SELECT RAISE(ABORT,'incomplete synopsis result'); END
          """);
          synopsisInputIdentity();
          execute(
              """
          CREATE TRIGGER synopsis_entries_identity BEFORE INSERT ON synopsis_entries
          WHEN NOT EXISTS(SELECT 1 FROM synopsis_tasks WHERE id=NEW.task_id AND state='processing')
            OR NEW.ordinal!=(SELECT COUNT(*) FROM synopsis_entries WHERE task_id=NEW.task_id)
          BEGIN SELECT RAISE(ABORT,'sealed synopsis entries'); END
          """);
          execute(
              """
          CREATE TRIGGER synopsis_references_identity BEFORE INSERT ON synopsis_references
          WHEN NOT EXISTS(SELECT 1 FROM synopsis_tasks WHERE id=NEW.task_id AND state='processing')
            OR NEW.source_ordinal!=(SELECT COUNT(*) FROM synopsis_references WHERE task_id=NEW.task_id AND entry_ordinal=NEW.entry_ordinal)
          BEGIN SELECT RAISE(ABORT,'sealed synopsis references'); END
          """);
          for (String table :
              List.of(
                  "synopsis_tasks",
                  "synopsis_input_evidence",
                  "synopsis_entries",
                  "synopsis_references")) {
            execute(
                "CREATE TRIGGER "
                    + table
                    + "_no_replace BEFORE INSERT ON "
                    + table
                    + " WHEN EXISTS(SELECT 1 FROM "
                    + table
                    + " WHERE "
                    + (table.equals("synopsis_tasks")
                        ? "id=NEW.id OR (document_id=NEW.document_id AND state IN ('queued','processing'))"
                        : table.equals("synopsis_references")
                            ? "task_id=NEW.task_id AND entry_ordinal=NEW.entry_ordinal AND (source_ordinal=NEW.source_ordinal OR physical_segment_id=NEW.physical_segment_id)"
                            : table.equals("synopsis_input_evidence")
                                ? "task_id=NEW.task_id AND (ordinal=NEW.ordinal OR physical_segment_id=NEW.physical_segment_id)"
                                : "task_id=NEW.task_id AND ordinal=NEW.ordinal")
                    + ") BEGIN SELECT RAISE(ABORT,'immutable synopsis history'); END");
            execute(
                "CREATE TRIGGER "
                    + table
                    + "_no_delete BEFORE DELETE ON "
                    + table
                    + " BEGIN SELECT RAISE(ABORT,'immutable synopsis history'); END");
            if (!table.equals("synopsis_tasks")) {
              execute(
                  "CREATE TRIGGER "
                      + table
                      + "_no_update BEFORE UPDATE ON "
                      + table
                      + " BEGIN SELECT RAISE(ABORT,'immutable synopsis history'); END");
            }
          }
          execute("UPDATE format_info SET version=13 WHERE format=?", FORMAT);
          execute("PRAGMA user_version=13");
          return null;
        });
  }

  private void synopsisInputIdentity() {
    execute(
        """
        CREATE TRIGGER synopsis_input_evidence_identity BEFORE INSERT ON synopsis_input_evidence
        WHEN NOT EXISTS(SELECT 1 FROM synopsis_tasks WHERE id=NEW.task_id AND state='queued')
          OR NEW.ordinal!=(SELECT COUNT(*) FROM synopsis_input_evidence WHERE task_id=NEW.task_id)
          OR NOT (
            (NEW.kind IN ('TEXT','IMAGE_OCR') AND EXISTS(SELECT 1 FROM index_publication_entries e JOIN corpus_segments s ON s.id=e.source_segment_id JOIN index_publications p ON p.id=e.publication_id JOIN documents d ON d.id=p.document_id
              WHERE e.publication_id=NEW.publication_id AND e.source_segment_id=NEW.source_segment_id AND e.physical_segment_id=NEW.physical_segment_id AND s.text_sha256=NEW.content_sha256 AND NEW.kind=CASE WHEN d.document_type='image' THEN 'IMAGE_OCR' ELSE 'TEXT' END))
            OR (NEW.kind='IMAGE' AND EXISTS(SELECT 1 FROM image_publication_entries e JOIN index_publications p ON p.id=e.publication_id
              WHERE e.publication_id=NEW.publication_id AND e.image_evidence_id=NEW.image_evidence_id AND e.physical_segment_id=NEW.physical_segment_id AND p.source_sha256=NEW.content_sha256))
            OR (NEW.kind='AUDIO_TRANSCRIPT' AND EXISTS(SELECT 1 FROM audio_publication_entries e JOIN audio_spans s ON s.id=e.audio_span_id
              WHERE e.publication_id=NEW.publication_id AND e.audio_span_id=NEW.audio_span_id AND e.physical_segment_id=NEW.physical_segment_id AND s.text_sha256=NEW.content_sha256 AND s.start_ms*1000=NEW.start_us AND s.end_ms*1000=NEW.end_us))
            OR (NEW.kind='VIDEO_FRAME' AND EXISTS(SELECT 1 FROM video_frame_publication_entries e JOIN video_frames f ON f.id=e.video_frame_id
              WHERE e.publication_id=NEW.publication_id AND e.video_frame_id=NEW.video_frame_id AND e.physical_segment_id=NEW.physical_segment_id AND f.frame_sha256=NEW.content_sha256 AND f.presentation_us=NEW.start_us AND f.presentation_us+f.duration_us=NEW.end_us))
            OR (NEW.kind='VIDEO_TRANSCRIPT' AND EXISTS(SELECT 1 FROM video_transcript_publication_entries e JOIN video_transcript_spans s ON s.id=e.video_transcript_span_id
              WHERE e.publication_id=NEW.publication_id AND e.video_transcript_span_id=NEW.video_transcript_span_id AND e.physical_segment_id=NEW.physical_segment_id AND s.text_sha256=NEW.content_sha256 AND s.start_ms*1000=NEW.start_us AND s.end_ms*1000=NEW.end_us))
            OR (NEW.kind='VIDEO_OCR' AND EXISTS(SELECT 1 FROM video_ocr_publication_entries e JOIN video_ocr_segments s ON s.id=e.video_ocr_segment_id JOIN video_frames f ON f.id=s.frame_id
              WHERE e.publication_id=NEW.publication_id AND e.video_ocr_segment_id=NEW.video_ocr_segment_id AND e.physical_segment_id=NEW.physical_segment_id AND s.text_sha256=NEW.content_sha256 AND f.presentation_us=NEW.start_us AND f.presentation_us+f.duration_us=NEW.end_us)))
        BEGIN SELECT RAISE(ABORT,'invalid synopsis source identity'); END
        """);
  }

  private void verifyVideoOcrFormat() {
    for (String table :
        List.of(
            "video_ocr_compilations",
            "video_frame_ocr",
            "video_ocr_segments",
            "video_ocr_regions",
            "video_ocr_publication_entries",
            "video_ocr_trace_evidence")) {
      if (count("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", table) != 1
          || count(
                  "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN (?,?,?)",
                  table + "_no_replace",
                  table + "_no_update",
                  table + "_no_delete")
              != 3) {
        throw new IllegalStateException("Unsupported Java video OCR schema");
      }
    }
    if (count(
                "SELECT COUNT(*) FROM pragma_table_info('video_ocr_trace_evidence') WHERE name IN ('trace_id','citation_ordinal','publication_id','segment_id','frame_id','physical_segment_id','start_code_point','end_code_point','source_sha256','frame_sha256','ocr_manifest_sha256','frame_text_sha256','quote_sha256','retrieval_score','rerank_score','fact_sha256')")
            != 16
        || count(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN ('corpus_revision_video_ocr_complete','video_ocr_trace_evidence_identity','video_ocr_trace_evidence_sealed')")
            != 3) {
      throw new IllegalStateException("Unsupported Java video OCR trace schema");
    }
  }

  void migrateVersionTwelve() {
    transaction(
        () -> {
          execute(
              """
          CREATE TABLE video_ocr_compilations(
            revision_id TEXT PRIMARY KEY NOT NULL REFERENCES video_compilations(revision_id) ON DELETE RESTRICT,
            ocr_revision TEXT NOT NULL CHECK(length(ocr_revision) BETWEEN 1 AND 200),
            frame_count INTEGER NOT NULL CHECK(frame_count BETWEEN 1 AND 128),
            projection_count INTEGER NOT NULL CHECK(projection_count BETWEEN 0 AND 4096),
            base_manifest_sha256 TEXT NOT NULL CHECK(length(base_manifest_sha256)=64 AND base_manifest_sha256 NOT GLOB '*[^a-f0-9]*'),
            manifest_sha256 TEXT NOT NULL CHECK(length(manifest_sha256)=64 AND manifest_sha256 NOT GLOB '*[^a-f0-9]*'),created_at TEXT NOT NULL)
          """);
          execute(
              """
          CREATE TRIGGER video_ocr_compilations_identity BEFORE INSERT ON video_ocr_compilations
          WHEN NOT EXISTS(SELECT 1 FROM video_compilations h JOIN corpus_revisions r ON r.id=h.revision_id
            WHERE h.revision_id=NEW.revision_id AND h.compiler_revision LIKE 'java-video-compiler-v2:%'
              AND h.frame_count=NEW.frame_count AND h.manifest_sha256=NEW.base_manifest_sha256
              AND h.projection_count+NEW.projection_count<=4096 AND r.parsed_at IS NULL)
          BEGIN SELECT RAISE(ABORT,'invalid video OCR preparation'); END
          """);
          execute(
              """
          CREATE TABLE video_frame_ocr(
            revision_id TEXT NOT NULL REFERENCES video_ocr_compilations(revision_id) ON DELETE RESTRICT,
            frame_id TEXT PRIMARY KEY NOT NULL,ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 127),
            frame_sha256 TEXT NOT NULL CHECK(length(frame_sha256)=64 AND frame_sha256 NOT GLOB '*[^a-f0-9]*'),
            width INTEGER NOT NULL CHECK(width>0),height INTEGER NOT NULL CHECK(height>0),
            text TEXT NOT NULL CHECK(length(text)<=1500000 AND instr(text,char(0))=0),
            text_sha256 TEXT NOT NULL CHECK(length(text_sha256)=64 AND text_sha256 NOT GLOB '*[^a-f0-9]*'),
            segment_count INTEGER NOT NULL CHECK(segment_count BETWEEN 0 AND 4096),
            region_count INTEGER NOT NULL CHECK(region_count BETWEEN 0 AND 200000),
            CHECK((length(text)=0 AND segment_count=0 AND region_count=0) OR (length(text)>0 AND segment_count>0 AND region_count>0)),
            UNIQUE(revision_id,frame_id),UNIQUE(revision_id,ordinal),
            FOREIGN KEY(revision_id,frame_id) REFERENCES video_frames(revision_id,id) ON DELETE RESTRICT)
          """);
          execute(
              """
          CREATE TRIGGER video_frame_ocr_identity BEFORE INSERT ON video_frame_ocr
          WHEN NOT EXISTS(SELECT 1 FROM video_frames f WHERE f.id=NEW.frame_id AND f.revision_id=NEW.revision_id
            AND f.ordinal=NEW.ordinal AND f.frame_sha256=NEW.frame_sha256 AND f.width=NEW.width AND f.height=NEW.height)
            OR NEW.ordinal!=(SELECT COUNT(*) FROM video_frame_ocr WHERE revision_id=NEW.revision_id)
          BEGIN SELECT RAISE(ABORT,'invalid video OCR frame'); END
          """);
          execute(
              """
          CREATE TABLE video_ocr_segments(
            id TEXT PRIMARY KEY NOT NULL,revision_id TEXT NOT NULL,frame_id TEXT NOT NULL,
            ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 4095),
            start_offset INTEGER NOT NULL CHECK(start_offset>=0),end_offset INTEGER NOT NULL,
            text TEXT NOT NULL CHECK(length(text) BETWEEN 1 AND 4096 AND length(CAST(text AS BLOB))<=16384 AND instr(text,char(0))=0),
            text_sha256 TEXT NOT NULL CHECK(length(text_sha256)=64 AND text_sha256 NOT GLOB '*[^a-f0-9]*'),
            CHECK(end_offset>start_offset AND end_offset-start_offset=length(text)),
            UNIQUE(frame_id,ordinal),UNIQUE(revision_id,id),
            FOREIGN KEY(revision_id,frame_id) REFERENCES video_frame_ocr(revision_id,frame_id) ON DELETE RESTRICT)
          """);
          execute(
              """
          CREATE TRIGGER video_ocr_segments_identity BEFORE INSERT ON video_ocr_segments
          WHEN NOT EXISTS(SELECT 1 FROM video_frame_ocr f WHERE f.revision_id=NEW.revision_id AND f.frame_id=NEW.frame_id
            AND NEW.ordinal<f.segment_count AND NEW.end_offset<=length(f.text)
            AND substr(f.text,NEW.start_offset+1,NEW.end_offset-NEW.start_offset)=NEW.text)
            OR NEW.ordinal!=(SELECT COUNT(*) FROM video_ocr_segments WHERE frame_id=NEW.frame_id)
          BEGIN SELECT RAISE(ABORT,'invalid video OCR segment'); END
          """);
          execute(
              """
          CREATE TABLE video_ocr_regions(
            revision_id TEXT NOT NULL,frame_id TEXT NOT NULL,ordinal INTEGER NOT NULL CHECK(ordinal>=0),
            start_offset INTEGER NOT NULL CHECK(start_offset>=0),end_offset INTEGER NOT NULL CHECK(end_offset>start_offset),
            left_pixel INTEGER NOT NULL CHECK(left_pixel>=0),top_pixel INTEGER NOT NULL CHECK(top_pixel>=0),
            right_pixel INTEGER NOT NULL CHECK(right_pixel>left_pixel),bottom_pixel INTEGER NOT NULL CHECK(bottom_pixel>top_pixel),
            PRIMARY KEY(frame_id,ordinal),
            FOREIGN KEY(revision_id,frame_id) REFERENCES video_frame_ocr(revision_id,frame_id) ON DELETE RESTRICT)
          """);
          execute(
              """
          CREATE TRIGGER video_ocr_regions_identity BEFORE INSERT ON video_ocr_regions
          WHEN NOT EXISTS(SELECT 1 FROM video_frame_ocr f WHERE f.revision_id=NEW.revision_id AND f.frame_id=NEW.frame_id
            AND NEW.ordinal<f.region_count AND NEW.end_offset<=length(f.text)
            AND NEW.right_pixel<=f.width AND NEW.bottom_pixel<=f.height)
            OR NEW.ordinal!=(SELECT COUNT(*) FROM video_ocr_regions WHERE frame_id=NEW.frame_id)
          BEGIN SELECT RAISE(ABORT,'invalid video OCR region'); END
          """);
          for (String table :
              List.of(
                  "video_ocr_compilations",
                  "video_frame_ocr",
                  "video_ocr_segments",
                  "video_ocr_regions")) {
            execute(
                "CREATE TRIGGER "
                    + table
                    + "_frozen BEFORE INSERT ON "
                    + table
                    + " WHEN EXISTS(SELECT 1 FROM corpus_revisions WHERE id=NEW.revision_id AND parsed_at IS NOT NULL)"
                    + " BEGIN SELECT RAISE(ABORT,'immutable video OCR preparation'); END");
          }
          replaceTriggerFragment(
              "video_compilations_identity",
              "LIKE 'java-video-compiler-v1:%'",
              "GLOB 'java-video-compiler-v[12]:*'");
          for (String table :
              List.of("corpus_pages", "corpus_segments", "image_evidence", "audio_compilations")) {
            replaceTriggerFragment(
                table + "_no_video_identity",
                "LIKE 'java-video-compiler-v1:%'",
                "GLOB 'java-video-compiler-v[12]:*'");
          }
          execute(
              "CREATE TRIGGER corpus_revision_video_ocr_complete BEFORE UPDATE OF parsed_at ON corpus_revisions"
                  + " WHEN NEW.parser_revision LIKE 'java-video-compiler-v2:%' AND NEW.parsed_at IS NOT NULL AND NOT ("
                  + videoPreparationComplete("NEW")
                  + " AND "
                  + videoOcrComplete("NEW")
                  + ") BEGIN SELECT RAISE(ABORT,'incomplete video OCR preparation'); END");
          videoOcrPublicationSchema();
          videoOcrTraceSchema();
          for (String table :
              List.of(
                  "video_ocr_compilations",
                  "video_frame_ocr",
                  "video_ocr_segments",
                  "video_ocr_regions",
                  "video_ocr_publication_entries",
                  "video_ocr_trace_evidence")) {
            String match =
                switch (table) {
                  case "video_ocr_compilations" -> "revision_id=NEW.revision_id";
                  case "video_frame_ocr" ->
                      "frame_id=NEW.frame_id OR (revision_id=NEW.revision_id AND ordinal=NEW.ordinal)";
                  case "video_ocr_segments" ->
                      "id=NEW.id OR (frame_id=NEW.frame_id AND ordinal=NEW.ordinal)";
                  case "video_ocr_regions" -> "frame_id=NEW.frame_id AND ordinal=NEW.ordinal";
                  case "video_ocr_publication_entries" ->
                      "publication_id=NEW.publication_id AND video_ocr_segment_id=NEW.video_ocr_segment_id OR physical_segment_id=NEW.physical_segment_id";
                  default -> "trace_id=NEW.trace_id AND citation_ordinal=NEW.citation_ordinal";
                };
            execute(
                "CREATE TRIGGER "
                    + table
                    + "_no_replace BEFORE INSERT ON "
                    + table
                    + " WHEN EXISTS(SELECT 1 FROM "
                    + table
                    + " WHERE "
                    + match
                    + ") BEGIN SELECT RAISE(ABORT,'immutable video OCR evidence'); END");
            for (String operation : List.of("UPDATE", "DELETE")) {
              execute(
                  "CREATE TRIGGER "
                      + table
                      + "_no_"
                      + operation.toLowerCase(java.util.Locale.ROOT)
                      + " BEFORE "
                      + operation
                      + " ON "
                      + table
                      + " BEGIN SELECT RAISE(ABORT,'immutable video OCR evidence'); END");
            }
          }
          execute("UPDATE format_info SET version=12 WHERE format=?", FORMAT);
          execute("PRAGMA user_version=12");
          return null;
        });
  }

  private String videoOcrComplete(String revision) {
    return """
        EXISTS(SELECT 1 FROM video_ocr_compilations o JOIN video_compilations h ON h.revision_id=o.revision_id
          WHERE o.revision_id=%1$s.id AND o.base_manifest_sha256=h.manifest_sha256 AND o.frame_count=h.frame_count
            AND o.frame_count=(SELECT COUNT(*) FROM video_frame_ocr WHERE revision_id=o.revision_id)
            AND o.projection_count=(SELECT COUNT(*) FROM video_ocr_segments WHERE revision_id=o.revision_id)
            AND NOT EXISTS(SELECT 1 FROM video_frame_ocr f WHERE f.revision_id=o.revision_id
              AND (f.segment_count!=(SELECT COUNT(*) FROM video_ocr_segments s WHERE s.frame_id=f.frame_id)
                OR f.region_count!=(SELECT COUNT(*) FROM video_ocr_regions b WHERE b.frame_id=f.frame_id)))
            AND (SELECT COALESCE(SUM(length(recall_text)),0) FROM video_frames WHERE revision_id=o.revision_id)
              +(SELECT COALESCE(SUM(length(text)),0) FROM video_transcript_spans WHERE revision_id=o.revision_id)
              +(SELECT COALESCE(SUM(length(text)),0) FROM video_ocr_segments WHERE revision_id=o.revision_id)<=1500000)
        """
        .formatted(revision);
  }

  /** Upgrade fixed historic trigger fragments without changing released migration bodies. */
  private void replaceTriggerFragment(String name, String previous, String replacement) {
    var rows = store.rows("SELECT sql FROM sqlite_master WHERE type='trigger' AND name=?", name);
    if (rows.size() != 1) {
      throw new IllegalStateException("Missing migration trigger");
    }
    String sql = AuthorityRows.text(rows.getFirst(), "sql");
    if (!sql.contains(previous)) {
      throw new IllegalStateException("Unsupported migration trigger");
    }
    execute("DROP TRIGGER " + name);
    execute(sql.replace(previous, replacement));
  }

  private void videoOcrPublicationSchema() {
    execute(
        """
        CREATE TABLE video_ocr_publication_entries(
          publication_id TEXT NOT NULL REFERENCES index_publications(id) ON DELETE RESTRICT,
          video_ocr_segment_id TEXT NOT NULL REFERENCES video_ocr_segments(id) ON DELETE RESTRICT,
          physical_segment_id TEXT NOT NULL UNIQUE CHECK(length(physical_segment_id) BETWEEN 1 AND 128),
          entry_sha256 TEXT NOT NULL CHECK(length(entry_sha256)=64 AND entry_sha256 NOT GLOB '*[^a-f0-9]*'),
          PRIMARY KEY(publication_id,video_ocr_segment_id))
        """);
    String otherTables = "";
    for (String table :
        List.of(
            "index_publication_entries",
            "image_publication_entries",
            "audio_publication_entries",
            "video_frame_publication_entries",
            "video_transcript_publication_entries")) {
      otherTables +=
          " OR EXISTS(SELECT 1 FROM "
              + table
              + " WHERE physical_segment_id=NEW.physical_segment_id)";
      execute(
          "CREATE TRIGGER "
              + table
              + "_no_ocr_collision BEFORE INSERT ON "
              + table
              + " WHEN EXISTS(SELECT 1 FROM video_ocr_publication_entries WHERE physical_segment_id=NEW.physical_segment_id) BEGIN SELECT RAISE(ABORT,'projection identity collision'); END");
    }
    execute(
        "CREATE TRIGGER video_ocr_publication_entries_identity BEFORE INSERT ON video_ocr_publication_entries"
            + " WHEN NOT EXISTS(SELECT 1 FROM index_publications p JOIN indexing_jobs j ON j.id=p.job_id AND j.state='processing' AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id"
            + " JOIN video_ocr_segments s ON s.revision_id=p.revision_id AND s.id=NEW.video_ocr_segment_id WHERE p.id=NEW.publication_id)"
            + otherTables
            + " BEGIN SELECT RAISE(ABORT,'invalid video OCR publication'); END");
    replaceTriggerFragment(
        "index_publication_identity",
        "+(SELECT COUNT(*) FROM video_transcript_spans WHERE revision_id=r.id AND index_ordinal IS NOT NULL)",
        "+(SELECT COUNT(*) FROM video_transcript_spans WHERE revision_id=r.id AND index_ordinal IS NOT NULL)+(SELECT COUNT(*) FROM video_ocr_segments WHERE revision_id=r.id)");
    for (String trigger :
        List.of("active_corpus_publication_complete", "indexing_published_state")) {
      replaceTriggerFragment(
          trigger,
          "+(SELECT COUNT(*) FROM video_transcript_publication_entries WHERE publication_id=p.id)",
          "+(SELECT COUNT(*) FROM video_transcript_publication_entries WHERE publication_id=p.id)+(SELECT COUNT(*) FROM video_ocr_publication_entries WHERE publication_id=p.id)");
    }
    for (String trigger :
        List.of(
            "index_publication_identity",
            "active_corpus_publication_complete",
            "indexing_published_state")) {
      replaceTriggerFragment(
          trigger,
          "r.parser_revision NOT LIKE 'java-video-compiler-v1:%'",
          "r.parser_revision NOT GLOB 'java-video-compiler-v[12]:*'");
    }
    execute(
        "CREATE TRIGGER index_publication_video_ocr_complete BEFORE INSERT ON index_publications"
            + " WHEN NEW.parser_revision LIKE 'java-video-compiler-v2:%' AND NOT EXISTS(SELECT 1 FROM corpus_revisions r WHERE r.id=NEW.revision_id AND r.parsed_at IS NOT NULL AND "
            + videoOcrComplete("r")
            + ") BEGIN SELECT RAISE(ABORT,'incomplete video OCR publication'); END");
  }

  private void videoOcrTraceSchema() {
    execute(
        """
        CREATE TABLE video_ocr_trace_evidence(
          trace_id TEXT NOT NULL,citation_ordinal INTEGER NOT NULL CHECK(citation_ordinal BETWEEN 1 AND 32),
          publication_id TEXT NOT NULL,segment_id TEXT NOT NULL,frame_id TEXT NOT NULL REFERENCES video_frame_ocr(frame_id) ON DELETE RESTRICT,
          physical_segment_id TEXT NOT NULL CHECK(length(physical_segment_id) BETWEEN 1 AND 128),
          start_code_point INTEGER NOT NULL CHECK(start_code_point>=0),end_code_point INTEGER NOT NULL CHECK(end_code_point>start_code_point AND end_code_point-start_code_point<=1200),
          source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64 AND source_sha256 NOT GLOB '*[^a-f0-9]*'),
          frame_sha256 TEXT NOT NULL CHECK(length(frame_sha256)=64 AND frame_sha256 NOT GLOB '*[^a-f0-9]*'),
          ocr_manifest_sha256 TEXT NOT NULL CHECK(length(ocr_manifest_sha256)=64 AND ocr_manifest_sha256 NOT GLOB '*[^a-f0-9]*'),
          frame_text_sha256 TEXT NOT NULL CHECK(length(frame_text_sha256)=64 AND frame_text_sha256 NOT GLOB '*[^a-f0-9]*'),
          quote_sha256 TEXT NOT NULL CHECK(length(quote_sha256)=64 AND quote_sha256 NOT GLOB '*[^a-f0-9]*'),
          retrieval_score REAL NOT NULL CHECK(retrieval_score BETWEEN -1.7976931348623157e308 AND 1.7976931348623157e308),
          rerank_score REAL NOT NULL CHECK(rerank_score BETWEEN -1.7976931348623157e308 AND 1.7976931348623157e308),fact_sha256 TEXT NOT NULL CHECK(length(fact_sha256) BETWEEN 64 AND 519),
          PRIMARY KEY(trace_id,citation_ordinal),
          FOREIGN KEY(trace_id,publication_id) REFERENCES query_trace_documents(trace_id,publication_id) ON DELETE RESTRICT,
          FOREIGN KEY(publication_id,segment_id) REFERENCES video_ocr_publication_entries(publication_id,video_ocr_segment_id) ON DELETE RESTRICT)
        """);
    execute(
        """
        CREATE TRIGGER video_ocr_trace_evidence_identity BEFORE INSERT ON video_ocr_trace_evidence
        WHEN NOT EXISTS(SELECT 1 FROM index_publications p JOIN video_ocr_publication_entries e ON e.publication_id=p.id
          JOIN video_ocr_segments s ON s.id=e.video_ocr_segment_id AND s.revision_id=p.revision_id
          JOIN video_frame_ocr f ON f.frame_id=s.frame_id AND f.revision_id=p.revision_id
          JOIN video_ocr_compilations o ON o.revision_id=p.revision_id
          WHERE p.id=NEW.publication_id AND s.id=NEW.segment_id AND f.frame_id=NEW.frame_id
            AND e.physical_segment_id=NEW.physical_segment_id AND p.source_sha256=NEW.source_sha256
            AND f.frame_sha256=NEW.frame_sha256 AND f.text_sha256=NEW.frame_text_sha256 AND o.manifest_sha256=NEW.ocr_manifest_sha256
            AND NEW.start_code_point>=s.start_offset AND NEW.end_code_point<=s.end_offset)
        BEGIN SELECT RAISE(ABORT,'invalid video OCR citation'); END
        """);
    execute(
        "CREATE TRIGGER video_ocr_trace_evidence_sealed BEFORE INSERT ON video_ocr_trace_evidence WHEN EXISTS(SELECT 1 FROM query_traces WHERE id=NEW.trace_id) BEGIN SELECT RAISE(ABORT,'sealed video OCR trace'); END");
    replaceTriggerFragment(
        "query_traces_complete",
        "+(SELECT COUNT(*) FROM video_trace_evidence WHERE trace_id=NEW.id)",
        "+(SELECT COUNT(*) FROM video_trace_evidence WHERE trace_id=NEW.id)+(SELECT COUNT(*) FROM video_ocr_trace_evidence WHERE trace_id=NEW.id)");
    replaceTriggerFragment(
        "query_traces_complete",
        "UNION ALL SELECT citation_ordinal FROM video_trace_evidence WHERE trace_id=NEW.id",
        "UNION ALL SELECT citation_ordinal FROM video_trace_evidence WHERE trace_id=NEW.id UNION ALL SELECT citation_ordinal FROM video_ocr_trace_evidence WHERE trace_id=NEW.id");
  }

  private void verifyVideoTraceFormat() {
    if (count(
                "SELECT COUNT(*) FROM pragma_table_info('video_trace_proofs') WHERE name IN ('trace_id','publication_id','group_id','mode','fact_count','text_model_revision','vision_model_revision','policy_revision')")
            != 8
        || count(
                "SELECT COUNT(*) FROM pragma_table_info('video_trace_facts') WHERE name IN ('trace_id','ordinal','fact_sha256','visual_support','transcript_support')")
            != 5
        || count(
                "SELECT COUNT(*) FROM pragma_table_info('video_trace_evidence') WHERE name IN ('trace_id','citation_ordinal','publication_id','group_id','kind','frame_id','transcript_span_id','physical_segment_id','start_code_point','end_code_point','source_sha256','manifest_sha256','frame_sha256','span_text_sha256','quote_sha256','retrieval_score','rerank_score','fact_sha256')")
            != 18
        || count(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN ('video_trace_proofs_identity','video_trace_evidence_identity')")
            != 2) {
      throw new IllegalStateException("Unsupported Java video trace schema");
    }
    for (String table :
        List.of("video_trace_proofs", "video_trace_facts", "video_trace_evidence")) {
      if (count(
              "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN (?,?,?,?)",
              table + "_sealed",
              table + "_no_replace",
              table + "_no_update",
              table + "_no_delete")
          != 4) {
        throw new IllegalStateException("Unsupported Java video trace immutability schema");
      }
    }
  }

  void migrateVersionEleven() {
    transaction(
        () -> {
          execute(
              """
          CREATE TABLE video_trace_proofs(
            trace_id TEXT PRIMARY KEY NOT NULL REFERENCES query_traces(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
            publication_id TEXT NOT NULL,
            group_id TEXT NOT NULL REFERENCES video_evidence_groups(id) ON DELETE RESTRICT,
            mode TEXT NOT NULL CHECK(mode IN ('VISUAL','TRANSCRIPT','JOINT')),
            fact_count INTEGER NOT NULL CHECK(fact_count BETWEEN 1 AND 8),
            text_model_revision TEXT NOT NULL CHECK(length(text_model_revision) BETWEEN 1 AND 200),
            vision_model_revision TEXT NOT NULL CHECK(length(vision_model_revision) BETWEEN 1 AND 200),
            policy_revision TEXT NOT NULL CHECK(length(policy_revision) BETWEEN 1 AND 200),
            UNIQUE(trace_id,publication_id,group_id),
            FOREIGN KEY(trace_id,publication_id) REFERENCES query_trace_documents(trace_id,publication_id) ON DELETE RESTRICT)
          """);
          execute(
              """
          CREATE TRIGGER video_trace_proofs_identity BEFORE INSERT ON video_trace_proofs
          WHEN NOT EXISTS(SELECT 1 FROM index_publications p JOIN video_evidence_groups g ON g.revision_id=p.revision_id
            JOIN video_compilations h ON h.revision_id=p.revision_id AND h.source_sha256=p.source_sha256 AND h.compiler_revision=p.parser_revision
            WHERE p.id=NEW.publication_id AND g.id=NEW.group_id)
          BEGIN SELECT RAISE(ABORT,'invalid video proof group'); END
          """);
          execute(
              """
          CREATE TABLE video_trace_facts(
            trace_id TEXT NOT NULL REFERENCES video_trace_proofs(trace_id) ON DELETE RESTRICT,
            ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 7),
            fact_sha256 TEXT NOT NULL CHECK(length(fact_sha256)=64 AND fact_sha256 NOT GLOB '*[^a-f0-9]*'),
            visual_support INTEGER NOT NULL CHECK(visual_support IN (0,1)),
            transcript_support INTEGER NOT NULL CHECK(transcript_support IN (0,1)),
            CHECK(visual_support+transcript_support>0),
            PRIMARY KEY(trace_id,ordinal),UNIQUE(trace_id,fact_sha256))
          """);
          execute(
              """
          CREATE TABLE video_trace_evidence(
            trace_id TEXT NOT NULL,
            citation_ordinal INTEGER NOT NULL CHECK(citation_ordinal BETWEEN 1 AND 32),
            publication_id TEXT NOT NULL,group_id TEXT NOT NULL,
            kind TEXT NOT NULL CHECK(kind IN ('VISUAL','TRANSCRIPT')),
            frame_id TEXT,transcript_span_id TEXT,
            physical_segment_id TEXT NOT NULL CHECK(length(physical_segment_id) BETWEEN 1 AND 128),
            start_code_point INTEGER,end_code_point INTEGER,
            source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64 AND source_sha256 NOT GLOB '*[^a-f0-9]*'),
            manifest_sha256 TEXT NOT NULL CHECK(length(manifest_sha256)=64 AND manifest_sha256 NOT GLOB '*[^a-f0-9]*'),
            frame_sha256 TEXT CHECK(frame_sha256 IS NULL OR (length(frame_sha256)=64 AND frame_sha256 NOT GLOB '*[^a-f0-9]*')),
            span_text_sha256 TEXT CHECK(span_text_sha256 IS NULL OR (length(span_text_sha256)=64 AND span_text_sha256 NOT GLOB '*[^a-f0-9]*')),
            quote_sha256 TEXT CHECK(quote_sha256 IS NULL OR (length(quote_sha256)=64 AND quote_sha256 NOT GLOB '*[^a-f0-9]*')),
            retrieval_score REAL NOT NULL CHECK(retrieval_score BETWEEN -1.7976931348623157e308 AND 1.7976931348623157e308),
            rerank_score REAL NOT NULL CHECK(rerank_score BETWEEN -1.7976931348623157e308 AND 1.7976931348623157e308),
            fact_sha256 TEXT NOT NULL CHECK(length(fact_sha256)=64 AND fact_sha256 NOT GLOB '*[^a-f0-9]*'),
            CHECK((kind='VISUAL' AND frame_id IS NOT NULL AND transcript_span_id IS NULL AND start_code_point IS NULL AND end_code_point IS NULL AND frame_sha256 IS NOT NULL AND span_text_sha256 IS NULL AND quote_sha256 IS NULL)
              OR (kind='TRANSCRIPT' AND frame_id IS NULL AND transcript_span_id IS NOT NULL AND start_code_point IS NOT NULL AND start_code_point>=0 AND end_code_point IS NOT NULL AND end_code_point>start_code_point AND end_code_point-start_code_point<=1200 AND frame_sha256 IS NULL AND span_text_sha256 IS NOT NULL AND quote_sha256 IS NOT NULL)),
            PRIMARY KEY(trace_id,citation_ordinal),
            FOREIGN KEY(trace_id,publication_id,group_id) REFERENCES video_trace_proofs(trace_id,publication_id,group_id) ON DELETE RESTRICT,
            FOREIGN KEY(trace_id,fact_sha256) REFERENCES video_trace_facts(trace_id,fact_sha256) ON DELETE RESTRICT,
            FOREIGN KEY(publication_id,frame_id) REFERENCES video_frame_publication_entries(publication_id,video_frame_id) ON DELETE RESTRICT,
            FOREIGN KEY(publication_id,transcript_span_id) REFERENCES video_transcript_publication_entries(publication_id,video_transcript_span_id) ON DELETE RESTRICT)
          """);
          execute(
              """
          CREATE TRIGGER video_trace_evidence_identity BEFORE INSERT ON video_trace_evidence
          WHEN NOT EXISTS(SELECT 1 FROM index_publications p
            JOIN video_compilations h ON h.revision_id=p.revision_id AND h.source_sha256=p.source_sha256 AND h.compiler_revision=p.parser_revision
            JOIN video_evidence_groups g ON g.revision_id=p.revision_id AND g.id=NEW.group_id
            WHERE p.id=NEW.publication_id AND p.source_sha256=NEW.source_sha256 AND h.manifest_sha256=NEW.manifest_sha256
              AND ((NEW.kind='VISUAL' AND g.frame_id=NEW.frame_id AND EXISTS(
                SELECT 1 FROM video_frame_publication_entries e JOIN video_frames f ON f.id=e.video_frame_id AND f.revision_id=p.revision_id
                WHERE e.publication_id=p.id AND e.video_frame_id=NEW.frame_id AND e.physical_segment_id=NEW.physical_segment_id AND f.frame_sha256=NEW.frame_sha256))
              OR (NEW.kind='TRANSCRIPT' AND g.transcript_span_id=NEW.transcript_span_id AND EXISTS(
                SELECT 1 FROM video_transcript_publication_entries e JOIN video_transcript_spans s ON s.id=e.video_transcript_span_id AND s.revision_id=p.revision_id AND s.index_ordinal IS NOT NULL
                WHERE e.publication_id=p.id AND e.video_transcript_span_id=NEW.transcript_span_id AND e.physical_segment_id=NEW.physical_segment_id AND s.text_sha256=NEW.span_text_sha256
                  AND NEW.start_code_point>=(SELECT COALESCE(SUM(length(previous.text)),0) FROM video_transcript_spans previous WHERE previous.revision_id=s.revision_id AND previous.ordinal<s.ordinal)+s.ordinal-(SELECT MIN(initial.ordinal) FROM video_transcript_spans initial WHERE initial.revision_id=s.revision_id AND length(initial.text)>0)
                  AND NEW.end_code_point<=(SELECT COALESCE(SUM(length(previous.text)),0) FROM video_transcript_spans previous WHERE previous.revision_id=s.revision_id AND previous.ordinal<s.ordinal)+s.ordinal-(SELECT MIN(initial.ordinal) FROM video_transcript_spans initial WHERE initial.revision_id=s.revision_id AND length(initial.text)>0)+length(s.text)))))
          BEGIN SELECT RAISE(ABORT,'invalid video citation'); END
          """);
          for (String table :
              List.of("video_trace_proofs", "video_trace_facts", "video_trace_evidence")) {
            execute(
                "CREATE TRIGGER "
                    + table
                    + "_sealed BEFORE INSERT ON "
                    + table
                    + " WHEN EXISTS(SELECT 1 FROM query_traces WHERE id=NEW.trace_id) BEGIN SELECT RAISE(ABORT,'sealed query trace'); END");
            String match =
                table.equals("video_trace_proofs")
                    ? "trace_id=NEW.trace_id"
                    : table.equals("video_trace_facts")
                        ? "trace_id=NEW.trace_id AND (ordinal=NEW.ordinal OR fact_sha256=NEW.fact_sha256)"
                        : "trace_id=NEW.trace_id AND citation_ordinal=NEW.citation_ordinal";
            execute(
                "CREATE TRIGGER "
                    + table
                    + "_no_replace BEFORE INSERT ON "
                    + table
                    + " WHEN EXISTS(SELECT 1 FROM "
                    + table
                    + " WHERE "
                    + match
                    + ") BEGIN SELECT RAISE(ABORT,'immutable video trace'); END");
            for (String operation : List.of("UPDATE", "DELETE")) {
              execute(
                  "CREATE TRIGGER "
                      + table
                      + "_no_"
                      + operation.toLowerCase(java.util.Locale.ROOT)
                      + " BEFORE "
                      + operation
                      + " ON "
                      + table
                      + " BEGIN SELECT RAISE(ABORT,'immutable video trace'); END");
            }
          }
          execute("DROP TRIGGER query_traces_complete");
          execute(
              """
          CREATE TRIGGER query_traces_complete BEFORE INSERT ON query_traces WHEN
            NEW.scope_count!=(SELECT COUNT(*) FROM query_trace_documents WHERE trace_id=NEW.id)
            OR NEW.citation_count!=(SELECT COUNT(*) FROM query_trace_evidence WHERE trace_id=NEW.id)
              +(SELECT COUNT(*) FROM image_trace_evidence WHERE trace_id=NEW.id)
              +(SELECT COUNT(*) FROM audio_trace_evidence WHERE trace_id=NEW.id)
              +(SELECT COUNT(*) FROM video_trace_evidence WHERE trace_id=NEW.id)
            OR (NEW.scope_count>0 AND ((SELECT MIN(ordinal) FROM query_trace_documents WHERE trace_id=NEW.id)!=0
              OR (SELECT MAX(ordinal) FROM query_trace_documents WHERE trace_id=NEW.id)!=NEW.scope_count-1))
            OR (NEW.citation_count>0 AND (SELECT COUNT(DISTINCT citation_ordinal)!=NEW.citation_count
              OR MIN(citation_ordinal)!=1 OR MAX(citation_ordinal)!=NEW.citation_count
              FROM (SELECT citation_ordinal FROM query_trace_evidence WHERE trace_id=NEW.id
                UNION ALL SELECT citation_ordinal FROM image_trace_evidence WHERE trace_id=NEW.id
                UNION ALL SELECT citation_ordinal FROM audio_trace_evidence WHERE trace_id=NEW.id
                UNION ALL SELECT citation_ordinal FROM video_trace_evidence WHERE trace_id=NEW.id)))
            OR EXISTS(SELECT 1 FROM query_trace_documents q JOIN index_publications p ON p.id=q.publication_id
              JOIN documents d ON d.id=p.document_id WHERE q.trace_id=NEW.id AND d.workspace_id!=NEW.workspace_id)
            OR ((SELECT COUNT(*) FROM video_trace_proofs WHERE trace_id=NEW.id)>0)!=(EXISTS(SELECT 1 FROM video_trace_evidence WHERE trace_id=NEW.id))
            OR EXISTS(SELECT 1 FROM video_trace_proofs p WHERE p.trace_id=NEW.id AND
              (NEW.outcome!='answered' OR p.fact_count!=(SELECT COUNT(*) FROM video_trace_facts WHERE trace_id=NEW.id)
                OR (SELECT MIN(ordinal) FROM video_trace_facts WHERE trace_id=NEW.id)!=0
                OR (SELECT MAX(ordinal) FROM video_trace_facts WHERE trace_id=NEW.id)!=p.fact_count-1
                OR (p.mode='JOINT' AND ((SELECT SUM(visual_support) FROM video_trace_facts WHERE trace_id=NEW.id)=0 OR (SELECT SUM(transcript_support) FROM video_trace_facts WHERE trace_id=NEW.id)=0))
                OR (p.mode='VISUAL' AND EXISTS(SELECT 1 FROM video_trace_facts WHERE trace_id=NEW.id AND (visual_support!=1 OR transcript_support!=0)))
                OR (p.mode='TRANSCRIPT' AND EXISTS(SELECT 1 FROM video_trace_facts WHERE trace_id=NEW.id AND (visual_support!=0 OR transcript_support!=1)))))
            OR EXISTS(SELECT 1 FROM video_trace_facts f WHERE f.trace_id=NEW.id AND
              (f.visual_support!=(EXISTS(SELECT 1 FROM video_trace_evidence e WHERE e.trace_id=f.trace_id AND e.fact_sha256=f.fact_sha256 AND e.kind='VISUAL'))
                OR f.transcript_support!=(EXISTS(SELECT 1 FROM video_trace_evidence e WHERE e.trace_id=f.trace_id AND e.fact_sha256=f.fact_sha256 AND e.kind='TRANSCRIPT'))))
          BEGIN SELECT RAISE(ABORT,'incomplete query trace'); END
          """);
          execute("UPDATE format_info SET version=11 WHERE format=?", FORMAT);
          execute("PRAGMA user_version=11");
          return null;
        });
  }

  private void verifyVideoFormat() {
    if (count(
                "SELECT COUNT(*) FROM pragma_table_info('video_compilations') WHERE name IN ('revision_id','source_sha256','decoder_revision','compiler_revision','timeline_origin_us','duration_us','frame_count','span_count','group_count','projection_count','frame_bytes','manifest_sha256','audio_model_revision','audio_transcription_revision','audio_sample_count','created_at')")
            != 16
        || count(
                "SELECT COUNT(*) FROM pragma_table_info('video_frames') WHERE name IN ('id','revision_id','ordinal','presentation_us','duration_us','media_type','frame_blob','frame_sha256','width','height','recall_text','recall_sha256','description_revision')")
            != 13
        || count(
                "SELECT COUNT(*) FROM pragma_table_info('video_transcript_spans') WHERE name IN ('id','revision_id','ordinal','start_ms','end_ms','text','text_sha256','index_ordinal')")
            != 8
        || count(
                "SELECT COUNT(*) FROM pragma_table_info('video_evidence_groups') WHERE name IN ('id','revision_id','ordinal','start_us','end_us','frame_id','transcript_span_id')")
            != 7
        || count(
                "SELECT COUNT(*) FROM pragma_table_info('video_frame_publication_entries') WHERE name IN ('publication_id','video_frame_id','physical_segment_id','entry_sha256')")
            != 4
        || count(
                "SELECT COUNT(*) FROM pragma_table_info('video_transcript_publication_entries') WHERE name IN ('publication_id','video_transcript_span_id','physical_segment_id','entry_sha256')")
            != 4
        || count(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN ('video_compilations_identity','video_frames_identity','video_transcript_spans_identity','video_evidence_groups_identity','video_frame_publication_entries_identity','video_transcript_publication_entries_identity','corpus_revision_video_complete')")
            != 7) {
      throw new IllegalStateException("Unsupported Java video evidence schema");
    }
    for (String table :
        List.of(
            "video_compilations",
            "video_frames",
            "video_transcript_spans",
            "video_evidence_groups",
            "video_frame_publication_entries",
            "video_transcript_publication_entries")) {
      if (count(
              "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN (?,?,?)",
              table + "_no_replace",
              table + "_no_update",
              table + "_no_delete")
          != 3) {
        throw new IllegalStateException("Unsupported Java video immutability schema");
      }
    }
  }

  void migrateVersionTen() {
    transaction(
        () -> {
          execute(
              """
          CREATE TABLE video_compilations(
            revision_id TEXT PRIMARY KEY NOT NULL REFERENCES corpus_revisions(id) ON DELETE RESTRICT,
            source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64 AND source_sha256 NOT GLOB '*[^a-f0-9]*'),
            decoder_revision TEXT NOT NULL CHECK(length(decoder_revision) BETWEEN 1 AND 200),
            compiler_revision TEXT NOT NULL CHECK(length(compiler_revision) BETWEEN 1 AND 200),
            timeline_origin_us INTEGER NOT NULL,duration_us INTEGER NOT NULL CHECK(duration_us BETWEEN 1 AND 600000000),
            frame_count INTEGER NOT NULL CHECK(frame_count BETWEEN 1 AND 128),
            span_count INTEGER NOT NULL CHECK(span_count BETWEEN 0 AND 600),
            group_count INTEGER NOT NULL CHECK(group_count BETWEEN 1 AND 77528),
            projection_count INTEGER NOT NULL CHECK(projection_count BETWEEN frame_count AND frame_count+span_count),
            frame_bytes INTEGER NOT NULL CHECK(frame_bytes BETWEEN 1 AND 33554432),
            manifest_sha256 TEXT NOT NULL CHECK(length(manifest_sha256)=64 AND manifest_sha256 NOT GLOB '*[^a-f0-9]*'),
            audio_model_revision TEXT CHECK(length(audio_model_revision) BETWEEN 1 AND 200),
            audio_transcription_revision TEXT CHECK(length(audio_transcription_revision) BETWEEN 1 AND 200),
            audio_sample_count INTEGER CHECK(audio_sample_count BETWEEN 1 AND 9600000),
            created_at TEXT NOT NULL,
            CHECK((span_count=0 AND audio_model_revision IS NULL AND audio_transcription_revision IS NULL AND audio_sample_count IS NULL)
              OR (span_count>0 AND audio_model_revision IS NOT NULL AND audio_transcription_revision IS NOT NULL AND audio_sample_count IS NOT NULL
                AND (audio_sample_count+15)/16<=(duration_us+999)/1000)))
          """);
          execute(
              """
          CREATE TRIGGER video_compilations_identity BEFORE INSERT ON video_compilations
          WHEN NOT EXISTS(SELECT 1 FROM corpus_revisions r JOIN documents d ON d.id=r.document_id
            JOIN corpus_documents c ON c.document_id=d.id AND c.initial_revision_id=r.id
            JOIN ingestion_jobs j ON j.document_id=d.id AND j.revision_id=r.id AND j.state='processing'
            WHERE r.id=NEW.revision_id AND d.document_type='video'
              AND d.mime_type IN ('video/mp4','video/quicktime','video/webm','video/x-matroska')
              AND r.source_sha256=d.source_sha256 AND r.source_sha256=NEW.source_sha256
              AND r.parser_revision=NEW.compiler_revision AND r.parser_revision LIKE 'java-video-compiler-v1:%'
              AND r.page_count=0 AND r.segment_count=0)
            OR EXISTS(SELECT 1 FROM corpus_pages WHERE revision_id=NEW.revision_id)
            OR EXISTS(SELECT 1 FROM corpus_segments WHERE revision_id=NEW.revision_id)
            OR EXISTS(SELECT 1 FROM image_evidence WHERE revision_id=NEW.revision_id)
            OR EXISTS(SELECT 1 FROM audio_compilations WHERE revision_id=NEW.revision_id)
          BEGIN SELECT RAISE(ABORT,'invalid video preparation identity'); END
          """);
          execute(
              """
          CREATE TABLE video_frames(
            id TEXT PRIMARY KEY NOT NULL,revision_id TEXT NOT NULL REFERENCES video_compilations(revision_id) ON DELETE RESTRICT,
            ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 127),
            presentation_us INTEGER NOT NULL CHECK(presentation_us>=0),duration_us INTEGER NOT NULL CHECK(duration_us BETWEEN 1 AND 600000000),
            media_type TEXT NOT NULL CHECK(media_type IN ('image/png','image/jpeg')),
            frame_blob BLOB NOT NULL CHECK(length(frame_blob) BETWEEN 1 AND 10485760),
            frame_sha256 TEXT NOT NULL CHECK(length(frame_sha256)=64 AND frame_sha256 NOT GLOB '*[^a-f0-9]*'),
            width INTEGER NOT NULL CHECK(width>0),height INTEGER NOT NULL CHECK(height>0 AND width*height<=12000000),
            recall_text TEXT NOT NULL CHECK(length(recall_text) BETWEEN 1 AND 4096 AND length(CAST(recall_text AS BLOB))<=16384),
            recall_sha256 TEXT NOT NULL CHECK(length(recall_sha256)=64 AND recall_sha256 NOT GLOB '*[^a-f0-9]*'),
            description_revision TEXT NOT NULL CHECK(length(description_revision) BETWEEN 1 AND 128),
            UNIQUE(revision_id,id),UNIQUE(revision_id,ordinal))
          """);
          execute(
              """
          CREATE TRIGGER video_frames_identity BEFORE INSERT ON video_frames
          WHEN NOT EXISTS(SELECT 1 FROM video_compilations h WHERE h.revision_id=NEW.revision_id
            AND NEW.ordinal<h.frame_count AND NEW.presentation_us<=h.duration_us-NEW.duration_us)
            OR NEW.ordinal!=(SELECT COUNT(*) FROM video_frames WHERE revision_id=NEW.revision_id)
            OR (NEW.ordinal=0 AND NEW.presentation_us!=0)
            OR (NEW.ordinal>0 AND NEW.presentation_us<=(SELECT MAX(presentation_us) FROM video_frames WHERE revision_id=NEW.revision_id))
            OR length(NEW.frame_blob)+(SELECT COALESCE(SUM(length(frame_blob)),0) FROM video_frames WHERE revision_id=NEW.revision_id)>33554432
          BEGIN SELECT RAISE(ABORT,'invalid video frame identity'); END
          """);
          execute(
              """
          CREATE TABLE video_transcript_spans(
            id TEXT PRIMARY KEY NOT NULL,revision_id TEXT NOT NULL REFERENCES video_compilations(revision_id) ON DELETE RESTRICT,
            ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 599),
            start_ms INTEGER NOT NULL CHECK(start_ms>=0),end_ms INTEGER NOT NULL CHECK(end_ms>start_ms AND end_ms<=600000 AND end_ms-start_ms<=30000),
            text TEXT NOT NULL CHECK(length(text)<=4096 AND length(CAST(text AS BLOB))<=16384),
            text_sha256 TEXT NOT NULL CHECK(length(text_sha256)=64 AND text_sha256 NOT GLOB '*[^a-f0-9]*'),
            index_ordinal INTEGER CHECK(index_ordinal BETWEEN 0 AND 599),
            UNIQUE(revision_id,id),UNIQUE(revision_id,ordinal),UNIQUE(revision_id,index_ordinal),
            CHECK((index_ordinal IS NULL AND length(trim(text,char(9,10,13,32,5760,8192,8193,8194,8195,8196,8197,8198,8200,8201,8202,8232,8233,8287,12288)))=0)
              OR (index_ordinal IS NOT NULL AND length(trim(text,char(9,10,13,32,5760,8192,8193,8194,8195,8196,8197,8198,8200,8201,8202,8232,8233,8287,12288)))>0)))
          """);
          execute(
              """
          CREATE TRIGGER video_transcript_spans_identity BEFORE INSERT ON video_transcript_spans
          WHEN NOT EXISTS(SELECT 1 FROM video_compilations h WHERE h.revision_id=NEW.revision_id AND h.audio_sample_count IS NOT NULL
            AND NEW.ordinal<h.span_count AND NEW.end_ms<=(h.audio_sample_count+15)/16
            AND (NEW.index_ordinal IS NULL OR NEW.index_ordinal<h.projection_count-h.frame_count))
            OR NEW.ordinal!=(SELECT COUNT(*) FROM video_transcript_spans WHERE revision_id=NEW.revision_id)
            OR NEW.start_ms!=COALESCE((SELECT MAX(end_ms) FROM video_transcript_spans WHERE revision_id=NEW.revision_id),0)
            OR (NEW.index_ordinal IS NOT NULL AND NEW.index_ordinal!=(SELECT COUNT(*) FROM video_transcript_spans WHERE revision_id=NEW.revision_id AND index_ordinal IS NOT NULL))
          BEGIN SELECT RAISE(ABORT,'invalid video transcript identity'); END
          """);
          execute(
              """
          CREATE TABLE video_evidence_groups(
            id TEXT PRIMARY KEY NOT NULL,revision_id TEXT NOT NULL REFERENCES video_compilations(revision_id) ON DELETE RESTRICT,
            ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 77527),
            start_us INTEGER NOT NULL CHECK(start_us>=0),end_us INTEGER NOT NULL CHECK(end_us>start_us AND end_us<=600000000),
            frame_id TEXT,transcript_span_id TEXT,CHECK(frame_id IS NOT NULL OR transcript_span_id IS NOT NULL),
            UNIQUE(revision_id,id),UNIQUE(revision_id,ordinal),UNIQUE(revision_id,frame_id,transcript_span_id),
            FOREIGN KEY(revision_id,frame_id) REFERENCES video_frames(revision_id,id) ON DELETE RESTRICT,
            FOREIGN KEY(revision_id,transcript_span_id) REFERENCES video_transcript_spans(revision_id,id) ON DELETE RESTRICT)
          """);
          execute(
              """
          CREATE TRIGGER video_evidence_groups_identity BEFORE INSERT ON video_evidence_groups
          WHEN NOT EXISTS(SELECT 1 FROM video_compilations h WHERE h.revision_id=NEW.revision_id AND NEW.ordinal<h.group_count
            AND NEW.end_us<=((h.duration_us+999)/1000)*1000
            AND h.frame_count=(SELECT COUNT(*) FROM video_frames WHERE revision_id=h.revision_id)
            AND h.span_count=(SELECT COUNT(*) FROM video_transcript_spans WHERE revision_id=h.revision_id))
            OR NEW.ordinal!=(SELECT COUNT(*) FROM video_evidence_groups WHERE revision_id=NEW.revision_id)
            OR EXISTS(SELECT 1 FROM video_evidence_groups WHERE revision_id=NEW.revision_id
              AND frame_id IS NEW.frame_id AND transcript_span_id IS NEW.transcript_span_id)
            OR NOT (
              (NEW.frame_id IS NOT NULL AND NEW.transcript_span_id IS NOT NULL AND EXISTS(
                SELECT 1 FROM video_frames f JOIN video_transcript_spans s ON s.revision_id=f.revision_id
                WHERE f.revision_id=NEW.revision_id AND f.id=NEW.frame_id AND s.id=NEW.transcript_span_id
                  AND NEW.start_us=max(f.presentation_us,s.start_ms*1000)
                  AND NEW.end_us=min(f.presentation_us+f.duration_us,s.end_ms*1000)))
              OR (NEW.frame_id IS NOT NULL AND NEW.transcript_span_id IS NULL AND EXISTS(
                SELECT 1 FROM video_frames f WHERE f.revision_id=NEW.revision_id AND f.id=NEW.frame_id
                  AND NEW.start_us=f.presentation_us AND NEW.end_us=f.presentation_us+f.duration_us
                  AND NOT EXISTS(SELECT 1 FROM video_transcript_spans s WHERE s.revision_id=f.revision_id
                    AND f.presentation_us<s.end_ms*1000 AND s.start_ms*1000<f.presentation_us+f.duration_us)))
              OR (NEW.frame_id IS NULL AND NEW.transcript_span_id IS NOT NULL AND EXISTS(
                SELECT 1 FROM video_transcript_spans s WHERE s.revision_id=NEW.revision_id AND s.id=NEW.transcript_span_id
                  AND NEW.start_us=s.start_ms*1000 AND NEW.end_us=s.end_ms*1000
                  AND NOT EXISTS(SELECT 1 FROM video_frames f WHERE f.revision_id=s.revision_id
                    AND f.presentation_us<s.end_ms*1000 AND s.start_ms*1000<f.presentation_us+f.duration_us))))
          BEGIN SELECT RAISE(ABORT,'invalid video evidence group'); END
          """);
          for (String table :
              List.of(
                  "video_compilations",
                  "video_frames",
                  "video_transcript_spans",
                  "video_evidence_groups")) {
            execute(
                "CREATE TRIGGER "
                    + table
                    + "_frozen BEFORE INSERT ON "
                    + table
                    + " WHEN EXISTS(SELECT 1 FROM corpus_revisions WHERE id=NEW.revision_id AND parsed_at IS NOT NULL)"
                    + " BEGIN SELECT RAISE(ABORT,'immutable video preparation'); END");
          }
          execute(
              "CREATE TRIGGER corpus_revision_video_complete BEFORE UPDATE OF parsed_at ON corpus_revisions"
                  + " WHEN NEW.parser_revision LIKE 'java-video-compiler-v1:%' AND NEW.parsed_at IS NOT NULL AND NOT ("
                  + videoPreparationComplete("NEW")
                  + ") BEGIN SELECT RAISE(ABORT,'incomplete video preparation'); END");
          for (String table :
              List.of("corpus_pages", "corpus_segments", "image_evidence", "audio_compilations")) {
            execute(
                "CREATE TRIGGER "
                    + table
                    + "_no_video_identity BEFORE INSERT ON "
                    + table
                    + " WHEN EXISTS(SELECT 1 FROM corpus_revisions WHERE id=NEW.revision_id AND parser_revision LIKE 'java-video-compiler-v1:%')"
                    + " BEGIN SELECT RAISE(ABORT,'video evidence cannot use another source type'); END");
          }
          videoPublicationTable(
              "video_frame_publication_entries", "video_frame_id", "video_frames", "");
          videoPublicationTable(
              "video_transcript_publication_entries",
              "video_transcript_span_id",
              "video_transcript_spans",
              " AND s.index_ordinal IS NOT NULL");
          for (String table :
              List.of(
                  "index_publication_entries",
                  "image_publication_entries",
                  "audio_publication_entries")) {
            execute(
                "CREATE TRIGGER "
                    + table
                    + "_no_video_collision BEFORE INSERT ON "
                    + table
                    + " WHEN EXISTS(SELECT 1 FROM video_frame_publication_entries WHERE physical_segment_id=NEW.physical_segment_id)"
                    + " OR EXISTS(SELECT 1 FROM video_transcript_publication_entries WHERE physical_segment_id=NEW.physical_segment_id)"
                    + " BEGIN SELECT RAISE(ABORT,'duplicate physical evidence identity'); END");
          }
          for (String table :
              List.of(
                  "video_compilations",
                  "video_frames",
                  "video_transcript_spans",
                  "video_evidence_groups",
                  "video_frame_publication_entries",
                  "video_transcript_publication_entries")) {
            String match =
                table.equals("video_compilations")
                    ? "revision_id=NEW.revision_id"
                    : table.equals("video_frame_publication_entries")
                        ? "publication_id=NEW.publication_id AND video_frame_id=NEW.video_frame_id OR physical_segment_id=NEW.physical_segment_id"
                        : table.equals("video_transcript_publication_entries")
                            ? "publication_id=NEW.publication_id AND video_transcript_span_id=NEW.video_transcript_span_id OR physical_segment_id=NEW.physical_segment_id"
                            : "id=NEW.id OR (revision_id=NEW.revision_id AND ordinal=NEW.ordinal)";
            execute(
                "CREATE TRIGGER "
                    + table
                    + "_no_replace BEFORE INSERT ON "
                    + table
                    + " WHEN EXISTS(SELECT 1 FROM "
                    + table
                    + " WHERE "
                    + match
                    + ") BEGIN SELECT RAISE(ABORT,'immutable video evidence'); END");
            for (String operation : List.of("UPDATE", "DELETE")) {
              execute(
                  "CREATE TRIGGER "
                      + table
                      + "_no_"
                      + operation.toLowerCase(java.util.Locale.ROOT)
                      + " BEFORE "
                      + operation
                      + " ON "
                      + table
                      + " BEGIN SELECT RAISE(ABORT,'immutable video evidence'); END");
            }
          }
          videoPublicationGates();
          execute("UPDATE format_info SET version=10 WHERE format=?", FORMAT);
          execute("PRAGMA user_version=10");
          return null;
        });
  }

  private String videoPreparationComplete(String revision) {
    return """
        %1$s.page_count=0 AND %1$s.segment_count=0
        AND EXISTS(SELECT 1 FROM video_compilations h WHERE h.revision_id=%1$s.id
          AND h.source_sha256=%1$s.source_sha256 AND h.compiler_revision=%1$s.parser_revision
          AND h.frame_count=(SELECT COUNT(*) FROM video_frames WHERE revision_id=h.revision_id)
          AND h.frame_count-1=(SELECT MAX(ordinal) FROM video_frames WHERE revision_id=h.revision_id)
          AND h.frame_bytes=(SELECT SUM(length(frame_blob)) FROM video_frames WHERE revision_id=h.revision_id)
          AND h.span_count=(SELECT COUNT(*) FROM video_transcript_spans WHERE revision_id=h.revision_id)
          AND h.projection_count=h.frame_count+(SELECT COUNT(*) FROM video_transcript_spans WHERE revision_id=h.revision_id AND index_ordinal IS NOT NULL)
          AND h.group_count=(SELECT COUNT(*) FROM video_evidence_groups WHERE revision_id=h.revision_id)
          AND h.group_count-1=(SELECT MAX(ordinal) FROM video_evidence_groups WHERE revision_id=h.revision_id)
          AND ((h.span_count=0 AND h.audio_sample_count IS NULL) OR (h.span_count>0
            AND h.span_count-1=(SELECT MAX(ordinal) FROM video_transcript_spans WHERE revision_id=h.revision_id)
            AND (h.audio_sample_count+15)/16=(SELECT MAX(end_ms) FROM video_transcript_spans WHERE revision_id=h.revision_id)))
          AND (SELECT COALESCE(SUM(length(recall_text)),0) FROM video_frames WHERE revision_id=h.revision_id)
            +(SELECT COALESCE(SUM(length(text)),0) FROM video_transcript_spans WHERE revision_id=h.revision_id AND index_ordinal IS NOT NULL)<=1500000
          AND NOT EXISTS(SELECT 1 FROM video_frames f WHERE f.revision_id=h.revision_id
            AND NOT EXISTS(SELECT 1 FROM video_evidence_groups g WHERE g.revision_id=h.revision_id AND g.frame_id=f.id))
          AND NOT EXISTS(SELECT 1 FROM video_transcript_spans s WHERE s.revision_id=h.revision_id
            AND NOT EXISTS(SELECT 1 FROM video_evidence_groups g WHERE g.revision_id=h.revision_id AND g.transcript_span_id=s.id))
          AND NOT EXISTS(SELECT 1 FROM video_frames f JOIN video_transcript_spans s ON s.revision_id=f.revision_id
            WHERE f.revision_id=h.revision_id AND f.presentation_us<s.end_ms*1000 AND s.start_ms*1000<f.presentation_us+f.duration_us
              AND NOT EXISTS(SELECT 1 FROM video_evidence_groups g WHERE g.revision_id=h.revision_id AND g.frame_id=f.id AND g.transcript_span_id=s.id)))
        AND NOT EXISTS(SELECT 1 FROM corpus_pages WHERE revision_id=%1$s.id)
        AND NOT EXISTS(SELECT 1 FROM corpus_segments WHERE revision_id=%1$s.id)
        AND NOT EXISTS(SELECT 1 FROM image_evidence WHERE revision_id=%1$s.id)
        AND NOT EXISTS(SELECT 1 FROM audio_compilations WHERE revision_id=%1$s.id)
        """
        .formatted(revision);
  }

  private void videoPublicationTable(String table, String member, String source, String extra) {
    execute(
        "CREATE TABLE "
            + table
            + "(publication_id TEXT NOT NULL REFERENCES index_publications(id) ON DELETE RESTRICT,"
            + member
            + " TEXT NOT NULL REFERENCES "
            + source
            + "(id) ON DELETE RESTRICT,"
            + "physical_segment_id TEXT NOT NULL UNIQUE CHECK(length(physical_segment_id) BETWEEN 1 AND 128),"
            + "entry_sha256 TEXT NOT NULL CHECK(length(entry_sha256)=64 AND entry_sha256 NOT GLOB '*[^a-f0-9]*'),PRIMARY KEY(publication_id,"
            + member
            + "))");
    String other =
        table.equals("video_frame_publication_entries")
            ? "video_transcript_publication_entries"
            : "video_frame_publication_entries";
    execute(
        "CREATE TRIGGER "
            + table
            + "_identity BEFORE INSERT ON "
            + table
            + " WHEN NOT EXISTS(SELECT 1 FROM index_publications p JOIN indexing_jobs j ON j.id=p.job_id AND j.state='processing'"
            + " AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id JOIN "
            + source
            + " s ON s.id=NEW."
            + member
            + " AND s.revision_id=p.revision_id"
            + " WHERE p.id=NEW.publication_id"
            + extra
            + ")"
            + " OR EXISTS(SELECT 1 FROM index_publication_entries WHERE physical_segment_id=NEW.physical_segment_id)"
            + " OR EXISTS(SELECT 1 FROM image_publication_entries WHERE physical_segment_id=NEW.physical_segment_id)"
            + " OR EXISTS(SELECT 1 FROM audio_publication_entries WHERE physical_segment_id=NEW.physical_segment_id)"
            + " OR EXISTS(SELECT 1 FROM "
            + other
            + " WHERE physical_segment_id=NEW.physical_segment_id)"
            + " BEGIN SELECT RAISE(ABORT,'invalid video publication evidence'); END");
  }

  private void videoPublicationGates() {
    String sourceCount =
        "r.segment_count+(SELECT COUNT(*) FROM image_evidence WHERE revision_id=r.id)"
            + "+(SELECT COUNT(*) FROM audio_spans WHERE revision_id=r.id AND index_ordinal IS NOT NULL)"
            + "+(SELECT COUNT(*) FROM video_frames WHERE revision_id=r.id)"
            + "+(SELECT COUNT(*) FROM video_transcript_spans WHERE revision_id=r.id AND index_ordinal IS NOT NULL)";
    String entryCount =
        "(SELECT COUNT(*) FROM index_publication_entries WHERE publication_id=p.id)"
            + "+(SELECT COUNT(*) FROM image_publication_entries WHERE publication_id=p.id)"
            + "+(SELECT COUNT(*) FROM audio_publication_entries WHERE publication_id=p.id)"
            + "+(SELECT COUNT(*) FROM video_frame_publication_entries WHERE publication_id=p.id)"
            + "+(SELECT COUNT(*) FROM video_transcript_publication_entries WHERE publication_id=p.id)";
    String ready =
        """
        AND (r.parser_revision NOT LIKE 'java-audio-compiler-v1:%' OR (r.parsed_at IS NOT NULL
          AND r.page_count=0 AND r.segment_count=0
          AND EXISTS(SELECT 1 FROM audio_compilations h WHERE h.revision_id=r.id
            AND h.source_sha256=r.source_sha256 AND h.compiler_revision=r.parser_revision
            AND h.span_count=(SELECT COUNT(*) FROM audio_spans WHERE revision_id=r.id)
            AND h.projection_count=(SELECT COUNT(*) FROM audio_spans WHERE revision_id=r.id AND index_ordinal IS NOT NULL))
          AND NOT EXISTS(SELECT 1 FROM corpus_pages WHERE revision_id=r.id)
          AND NOT EXISTS(SELECT 1 FROM corpus_segments WHERE revision_id=r.id)
          AND NOT EXISTS(SELECT 1 FROM image_evidence WHERE revision_id=r.id)))
        """
            + " AND (r.parser_revision NOT LIKE 'java-video-compiler-v1:%' OR (r.parsed_at IS NOT NULL AND "
            + videoPreparationComplete("r")
            + "))";
    execute("DROP TRIGGER index_publication_identity");
    execute(
        """
        CREATE TRIGGER index_publication_identity BEFORE INSERT ON index_publications
        WHEN NOT EXISTS(SELECT 1 FROM indexing_jobs j JOIN corpus_revisions r ON r.id=j.revision_id AND r.document_id=j.document_id
          WHERE j.id=NEW.job_id AND j.state='processing' AND j.document_id=NEW.document_id AND j.revision_id=NEW.revision_id
            AND j.attempt=NEW.attempt AND j.projection_generation_id=NEW.projection_generation_id
            AND j.source_sha256=NEW.source_sha256 AND j.parser_revision=NEW.parser_revision
            AND j.embedding_identity=NEW.embedding_identity AND j.projection_identity=NEW.projection_identity
            AND j.model_revision=NEW.model_revision AND j.dimensions=NEW.dimensions
            AND %s=NEW.segment_count %s)
        BEGIN SELECT RAISE(ABORT,'invalid index publication'); END
        """
            .formatted(sourceCount, ready));
    execute("DROP TRIGGER active_corpus_publication_complete");
    execute(
        """
        CREATE TRIGGER active_corpus_publication_complete BEFORE INSERT ON active_corpus_publications
        WHEN NOT EXISTS(SELECT 1 FROM index_publications p JOIN indexing_jobs j ON j.id=p.job_id AND j.state='processing'
          AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id
          JOIN corpus_revisions r ON r.id=p.revision_id AND r.document_id=p.document_id
          WHERE p.id=NEW.publication_id AND p.document_id=NEW.document_id AND p.revision_id=NEW.revision_id
            AND p.segment_count=%s %s)
        BEGIN SELECT RAISE(ABORT,'incomplete index publication'); END
        """
            .formatted(entryCount, ready));
    execute("DROP TRIGGER indexing_published_state");
    execute(
        """
        CREATE TRIGGER indexing_published_state BEFORE UPDATE OF state ON indexing_jobs
        WHEN NEW.state='indexed' AND NOT EXISTS(SELECT 1 FROM index_publications p
          JOIN active_corpus_publications a ON a.publication_id=p.id AND a.document_id=p.document_id AND a.revision_id=p.revision_id
          JOIN corpus_revisions r ON r.id=p.revision_id AND r.document_id=p.document_id
          WHERE p.job_id=NEW.id AND p.attempt=NEW.attempt AND p.projection_generation_id=NEW.projection_generation_id
            AND p.segment_count=%s %s)
        BEGIN SELECT RAISE(ABORT,'missing index publication'); END
        """
            .formatted(entryCount, ready));
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

  void migrateVersionNine() {
    transaction(
        () -> {
          execute(
              """
              CREATE TABLE audio_trace_evidence(
                trace_id TEXT NOT NULL REFERENCES query_traces(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
                citation_ordinal INTEGER NOT NULL CHECK(citation_ordinal BETWEEN 1 AND 32),
                publication_id TEXT NOT NULL,audio_span_id TEXT NOT NULL,physical_segment_id TEXT NOT NULL,
                start_code_point INTEGER NOT NULL CHECK(start_code_point>=0),
                end_code_point INTEGER NOT NULL CHECK(end_code_point>start_code_point AND end_code_point-start_code_point<=1200),
                start_ms INTEGER NOT NULL CHECK(start_ms>=0),
                end_ms INTEGER NOT NULL CHECK(end_ms>start_ms AND end_ms<=600000 AND end_ms-start_ms<=30000),
                source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64 AND source_sha256 NOT GLOB '*[^a-f0-9]*'),
                text_sha256 TEXT NOT NULL CHECK(length(text_sha256)=64 AND text_sha256 NOT GLOB '*[^a-f0-9]*'),
                transcript_sha256 TEXT NOT NULL CHECK(length(transcript_sha256)=64 AND transcript_sha256 NOT GLOB '*[^a-f0-9]*'),
                quote_sha256 TEXT NOT NULL CHECK(length(quote_sha256)=64 AND quote_sha256 NOT GLOB '*[^a-f0-9]*'),
                retrieval_score REAL NOT NULL CHECK(retrieval_score BETWEEN -1.7976931348623157e308 AND 1.7976931348623157e308),
                rerank_score REAL NOT NULL CHECK(rerank_score BETWEEN -1.7976931348623157e308 AND 1.7976931348623157e308),
                fact_sha256 TEXT NOT NULL CHECK(length(fact_sha256) BETWEEN 64 AND 519 AND fact_sha256 NOT GLOB '*[^a-f0-9,]*'),
                PRIMARY KEY(trace_id,citation_ordinal),
                FOREIGN KEY(trace_id,publication_id) REFERENCES query_trace_documents(trace_id,publication_id) ON DELETE RESTRICT,
                FOREIGN KEY(publication_id,audio_span_id) REFERENCES audio_publication_entries(publication_id,audio_span_id) ON DELETE RESTRICT)
              """);
          execute(
              """
              CREATE TRIGGER audio_trace_evidence_identity BEFORE INSERT ON audio_trace_evidence
              WHEN NOT EXISTS(SELECT 1 FROM audio_publication_entries e
                JOIN index_publications p ON p.id=e.publication_id
                JOIN audio_spans s ON s.id=e.audio_span_id AND s.revision_id=p.revision_id AND s.index_ordinal IS NOT NULL
                JOIN audio_compilations h ON h.revision_id=s.revision_id AND h.source_sha256=p.source_sha256 AND h.compiler_revision=p.parser_revision
                WHERE e.publication_id=NEW.publication_id AND e.audio_span_id=NEW.audio_span_id
                  AND e.physical_segment_id=NEW.physical_segment_id AND p.source_sha256=NEW.source_sha256
                  AND s.start_ms=NEW.start_ms AND s.end_ms=NEW.end_ms AND s.text_sha256=NEW.text_sha256
                  AND h.transcript_sha256=NEW.transcript_sha256
                  AND NEW.start_code_point>=(SELECT COALESCE(SUM(length(previous.text)+1),0) FROM audio_spans previous WHERE previous.revision_id=s.revision_id AND previous.ordinal<s.ordinal)
                  AND NEW.end_code_point<=(SELECT COALESCE(SUM(length(previous.text)+1),0) FROM audio_spans previous WHERE previous.revision_id=s.revision_id AND previous.ordinal<s.ordinal)+length(s.text))
              BEGIN SELECT RAISE(ABORT,'invalid audio citation'); END
              """);
          execute(
              """
              CREATE TRIGGER audio_trace_evidence_sealed BEFORE INSERT ON audio_trace_evidence
              WHEN EXISTS(SELECT 1 FROM query_traces WHERE id=NEW.trace_id)
              BEGIN SELECT RAISE(ABORT,'sealed query trace'); END
              """);
          execute(
              """
              CREATE TRIGGER audio_trace_evidence_no_replace BEFORE INSERT ON audio_trace_evidence
              WHEN EXISTS(SELECT 1 FROM audio_trace_evidence WHERE trace_id=NEW.trace_id AND citation_ordinal=NEW.citation_ordinal)
              BEGIN SELECT RAISE(ABORT,'immutable audio citation'); END
              """);
          for (String operation : List.of("UPDATE", "DELETE")) {
            execute(
                "CREATE TRIGGER audio_trace_evidence_no_"
                    + operation.toLowerCase(java.util.Locale.ROOT)
                    + " BEFORE "
                    + operation
                    + " ON audio_trace_evidence"
                    + " BEGIN SELECT RAISE(ABORT,'immutable audio citation'); END");
          }
          execute("DROP TRIGGER query_traces_complete");
          execute(
              """
              CREATE TRIGGER query_traces_complete BEFORE INSERT ON query_traces WHEN
                NEW.scope_count!=(SELECT COUNT(*) FROM query_trace_documents WHERE trace_id=NEW.id)
                OR NEW.citation_count!=(SELECT COUNT(*) FROM query_trace_evidence WHERE trace_id=NEW.id)
                  +(SELECT COUNT(*) FROM image_trace_evidence WHERE trace_id=NEW.id)
                  +(SELECT COUNT(*) FROM audio_trace_evidence WHERE trace_id=NEW.id)
                OR (NEW.scope_count>0 AND ((SELECT MIN(ordinal) FROM query_trace_documents WHERE trace_id=NEW.id)!=0
                  OR (SELECT MAX(ordinal) FROM query_trace_documents WHERE trace_id=NEW.id)!=NEW.scope_count-1))
                OR (NEW.citation_count>0 AND (SELECT COUNT(DISTINCT citation_ordinal)!=NEW.citation_count
                  OR MIN(citation_ordinal)!=1 OR MAX(citation_ordinal)!=NEW.citation_count
                  FROM (SELECT citation_ordinal FROM query_trace_evidence WHERE trace_id=NEW.id
                    UNION ALL SELECT citation_ordinal FROM image_trace_evidence WHERE trace_id=NEW.id
                    UNION ALL SELECT citation_ordinal FROM audio_trace_evidence WHERE trace_id=NEW.id)))
                OR EXISTS(SELECT 1 FROM query_trace_documents q JOIN index_publications p ON p.id=q.publication_id
                  JOIN documents d ON d.id=p.document_id WHERE q.trace_id=NEW.id AND d.workspace_id!=NEW.workspace_id)
              BEGIN SELECT RAISE(ABORT,'incomplete query trace'); END
              """);
          execute("UPDATE format_info SET version=9 WHERE format=?", FORMAT);
          execute("PRAGMA user_version=9");
          return null;
        });
  }

  void migrateVersionEight() {
    transaction(
        () -> {
          execute(
              """
              CREATE TABLE audio_compilations(
                revision_id TEXT PRIMARY KEY NOT NULL REFERENCES corpus_revisions(id) ON DELETE RESTRICT,
                source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64 AND source_sha256 NOT GLOB '*[^a-f0-9]*'),
                decoder_revision TEXT NOT NULL CHECK(length(decoder_revision) BETWEEN 1 AND 200),
                model_revision TEXT NOT NULL CHECK(length(model_revision) BETWEEN 1 AND 200),
                compiler_revision TEXT NOT NULL CHECK(length(compiler_revision) BETWEEN 1 AND 200),
                duration_ms INTEGER NOT NULL CHECK(duration_ms BETWEEN 1 AND 600000),
                span_count INTEGER NOT NULL CHECK(span_count BETWEEN 1 AND 600),
                projection_count INTEGER NOT NULL CHECK(projection_count BETWEEN 1 AND span_count),
                transcript_sha256 TEXT NOT NULL CHECK(length(transcript_sha256)=64 AND transcript_sha256 NOT GLOB '*[^a-f0-9]*'),
                created_at TEXT NOT NULL)
              """);
          execute(
              """
              CREATE TRIGGER audio_compilations_identity BEFORE INSERT ON audio_compilations
              WHEN NOT EXISTS(SELECT 1 FROM corpus_revisions r JOIN documents d ON d.id=r.document_id
                JOIN corpus_documents c ON c.document_id=d.id AND c.initial_revision_id=r.id
                JOIN ingestion_jobs j ON j.document_id=d.id AND j.revision_id=r.id AND j.state='processing'
                WHERE r.id=NEW.revision_id AND d.document_type='audio'
                  AND r.source_sha256=d.source_sha256 AND r.source_sha256=NEW.source_sha256
                  AND r.parser_revision=NEW.compiler_revision AND r.parser_revision LIKE 'java-audio-compiler-v1:%'
                  AND r.page_count=0 AND r.segment_count=0)
                OR EXISTS(SELECT 1 FROM corpus_pages WHERE revision_id=NEW.revision_id)
                OR EXISTS(SELECT 1 FROM corpus_segments WHERE revision_id=NEW.revision_id)
                OR EXISTS(SELECT 1 FROM image_evidence WHERE revision_id=NEW.revision_id)
              BEGIN SELECT RAISE(ABORT,'invalid audio preparation identity'); END
              """);
          execute(
              """
              CREATE TRIGGER audio_compilations_frozen BEFORE INSERT ON audio_compilations
              WHEN EXISTS(SELECT 1 FROM corpus_revisions WHERE id=NEW.revision_id AND parsed_at IS NOT NULL)
              BEGIN SELECT RAISE(ABORT,'immutable audio preparation'); END
              """);
          execute(
              """
              CREATE TRIGGER audio_compilations_no_replace BEFORE INSERT ON audio_compilations
              WHEN EXISTS(SELECT 1 FROM audio_compilations WHERE revision_id=NEW.revision_id)
              BEGIN SELECT RAISE(ABORT,'immutable audio preparation'); END
              """);
          execute(
              """
              CREATE TABLE audio_spans(
                id TEXT PRIMARY KEY NOT NULL,
                revision_id TEXT NOT NULL REFERENCES audio_compilations(revision_id) ON DELETE RESTRICT,
                ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 599),
                start_ms INTEGER NOT NULL CHECK(start_ms>=0),
                end_ms INTEGER NOT NULL CHECK(end_ms>start_ms AND end_ms<=600000 AND end_ms-start_ms<=30000),
                text TEXT NOT NULL CHECK(length(text)<=4096 AND length(CAST(text AS BLOB))<=16384),
                text_sha256 TEXT NOT NULL CHECK(length(text_sha256)=64 AND text_sha256 NOT GLOB '*[^a-f0-9]*'),
                index_ordinal INTEGER CHECK(index_ordinal BETWEEN 0 AND 599),
                UNIQUE(revision_id,ordinal),UNIQUE(revision_id,index_ordinal),
                CHECK((index_ordinal IS NULL AND length(trim(text,char(9,10,13,32,5760,8192,8193,8194,8195,8196,8197,8198,8200,8201,8202,8232,8233,8287,12288)))=0)
                  OR (index_ordinal IS NOT NULL AND length(trim(text,char(9,10,13,32,5760,8192,8193,8194,8195,8196,8197,8198,8200,8201,8202,8232,8233,8287,12288)))>0)))
              """);
          execute(
              """
              CREATE TRIGGER audio_spans_identity BEFORE INSERT ON audio_spans
              WHEN NOT EXISTS(SELECT 1 FROM audio_compilations h WHERE h.revision_id=NEW.revision_id
                AND NEW.ordinal<h.span_count AND NEW.end_ms<=h.duration_ms
                AND (NEW.index_ordinal IS NULL OR NEW.index_ordinal<h.projection_count))
                OR NEW.ordinal!=(SELECT COUNT(*) FROM audio_spans WHERE revision_id=NEW.revision_id)
                OR NEW.start_ms!=COALESCE((SELECT MAX(end_ms) FROM audio_spans WHERE revision_id=NEW.revision_id),0)
                OR (NEW.index_ordinal IS NOT NULL AND NEW.index_ordinal!=(SELECT COUNT(*) FROM audio_spans WHERE revision_id=NEW.revision_id AND index_ordinal IS NOT NULL))
              BEGIN SELECT RAISE(ABORT,'invalid audio span identity'); END
              """);
          execute(
              """
              CREATE TRIGGER audio_spans_frozen BEFORE INSERT ON audio_spans
              WHEN EXISTS(SELECT 1 FROM corpus_revisions WHERE id=NEW.revision_id AND parsed_at IS NOT NULL)
              BEGIN SELECT RAISE(ABORT,'immutable audio span'); END
              """);
          execute(
              """
              CREATE TRIGGER audio_spans_no_replace BEFORE INSERT ON audio_spans
              WHEN EXISTS(SELECT 1 FROM audio_spans WHERE id=NEW.id
                OR (revision_id=NEW.revision_id AND (ordinal=NEW.ordinal OR index_ordinal=NEW.index_ordinal)))
              BEGIN SELECT RAISE(ABORT,'immutable audio span'); END
              """);
          execute(
              """
              CREATE TRIGGER corpus_revision_audio_complete BEFORE UPDATE OF parsed_at ON corpus_revisions
              WHEN NEW.parser_revision LIKE 'java-audio-compiler-v1:%' AND NEW.parsed_at IS NOT NULL AND
                (NEW.page_count!=0 OR NEW.segment_count!=0
                  OR NOT EXISTS(SELECT 1 FROM audio_compilations h WHERE h.revision_id=NEW.id
                    AND h.source_sha256=NEW.source_sha256 AND h.compiler_revision=NEW.parser_revision
                    AND h.span_count=(SELECT COUNT(*) FROM audio_spans WHERE revision_id=NEW.id)
                    AND h.projection_count=(SELECT COUNT(*) FROM audio_spans WHERE revision_id=NEW.id AND index_ordinal IS NOT NULL)
                    AND (SELECT MIN(ordinal) FROM audio_spans WHERE revision_id=NEW.id)=0
                    AND (SELECT MAX(ordinal) FROM audio_spans WHERE revision_id=NEW.id)=h.span_count-1
                    AND (SELECT MIN(start_ms) FROM audio_spans WHERE revision_id=NEW.id)=0
                    AND (SELECT MAX(end_ms) FROM audio_spans WHERE revision_id=NEW.id)=h.duration_ms
                    AND (SELECT MIN(index_ordinal) FROM audio_spans WHERE revision_id=NEW.id)=0
                    AND (SELECT MAX(index_ordinal) FROM audio_spans WHERE revision_id=NEW.id)=h.projection_count-1)
                  OR EXISTS(SELECT 1 FROM audio_spans s WHERE s.revision_id=NEW.id AND s.ordinal>0
                    AND NOT EXISTS(SELECT 1 FROM audio_spans previous WHERE previous.revision_id=NEW.id
                      AND previous.ordinal=s.ordinal-1 AND previous.end_ms=s.start_ms))
                  OR EXISTS(SELECT 1 FROM corpus_pages WHERE revision_id=NEW.id)
                  OR EXISTS(SELECT 1 FROM corpus_segments WHERE revision_id=NEW.id)
                  OR EXISTS(SELECT 1 FROM image_evidence WHERE revision_id=NEW.id))
              BEGIN SELECT RAISE(ABORT,'incomplete audio preparation'); END
              """);
          execute(
              """
              CREATE TABLE audio_publication_entries(
                publication_id TEXT NOT NULL REFERENCES index_publications(id) ON DELETE RESTRICT,
                audio_span_id TEXT NOT NULL REFERENCES audio_spans(id) ON DELETE RESTRICT,
                physical_segment_id TEXT NOT NULL UNIQUE CHECK(length(physical_segment_id) BETWEEN 1 AND 128),
                entry_sha256 TEXT NOT NULL CHECK(length(entry_sha256)=64 AND entry_sha256 NOT GLOB '*[^a-f0-9]*'),
                PRIMARY KEY(publication_id,audio_span_id))
              """);
          execute(
              """
              CREATE TRIGGER audio_publication_entry_identity BEFORE INSERT ON audio_publication_entries
              WHEN NOT EXISTS(SELECT 1 FROM index_publications p
                JOIN indexing_jobs j ON j.id=p.job_id AND j.state='processing'
                  AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id
                JOIN audio_spans s ON s.id=NEW.audio_span_id AND s.revision_id=p.revision_id AND s.index_ordinal IS NOT NULL
                WHERE p.id=NEW.publication_id)
                OR EXISTS(SELECT 1 FROM index_publication_entries WHERE physical_segment_id=NEW.physical_segment_id)
                OR EXISTS(SELECT 1 FROM image_publication_entries WHERE physical_segment_id=NEW.physical_segment_id)
              BEGIN SELECT RAISE(ABORT,'invalid audio publication evidence'); END
              """);
          execute(
              """
              CREATE TRIGGER audio_publication_entries_no_replace BEFORE INSERT ON audio_publication_entries
              WHEN EXISTS(SELECT 1 FROM audio_publication_entries WHERE
                (publication_id=NEW.publication_id AND audio_span_id=NEW.audio_span_id)
                OR physical_segment_id=NEW.physical_segment_id)
              BEGIN SELECT RAISE(ABORT,'immutable audio publication'); END
              """);
          for (String table : List.of("index_publication_entries", "image_publication_entries")) {
            execute(
                "CREATE TRIGGER "
                    + table
                    + "_no_audio_collision BEFORE INSERT ON "
                    + table
                    + " WHEN EXISTS(SELECT 1 FROM audio_publication_entries WHERE physical_segment_id=NEW.physical_segment_id)"
                    + " BEGIN SELECT RAISE(ABORT,'duplicate physical evidence identity'); END");
          }
          // All publication gates check both the frozen header and its actual projection rows.
          String audioPublicationReady =
              """
              AND (r.parser_revision NOT LIKE 'java-audio-compiler-v1:%' OR (r.parsed_at IS NOT NULL
                AND r.page_count=0 AND r.segment_count=0
                AND EXISTS(SELECT 1 FROM audio_compilations h WHERE h.revision_id=r.id
                  AND h.source_sha256=r.source_sha256 AND h.compiler_revision=r.parser_revision
                  AND h.span_count=(SELECT COUNT(*) FROM audio_spans WHERE revision_id=r.id)
                  AND h.projection_count=(SELECT COUNT(*) FROM audio_spans WHERE revision_id=r.id AND index_ordinal IS NOT NULL))
                AND NOT EXISTS(SELECT 1 FROM corpus_pages WHERE revision_id=r.id)
                AND NOT EXISTS(SELECT 1 FROM corpus_segments WHERE revision_id=r.id)
                AND NOT EXISTS(SELECT 1 FROM image_evidence WHERE revision_id=r.id)))
              """;
          execute("DROP TRIGGER index_publication_identity");
          execute(
              """
              CREATE TRIGGER index_publication_identity BEFORE INSERT ON index_publications
              WHEN NOT EXISTS(SELECT 1 FROM indexing_jobs j JOIN corpus_revisions r ON r.id=j.revision_id AND r.document_id=j.document_id
                WHERE j.id=NEW.job_id AND j.state='processing' AND j.document_id=NEW.document_id AND j.revision_id=NEW.revision_id
                  AND j.attempt=NEW.attempt AND j.projection_generation_id=NEW.projection_generation_id
                  AND j.source_sha256=NEW.source_sha256 AND j.parser_revision=NEW.parser_revision
                  AND j.embedding_identity=NEW.embedding_identity AND j.projection_identity=NEW.projection_identity
                  AND j.model_revision=NEW.model_revision AND j.dimensions=NEW.dimensions
                  AND r.segment_count+(SELECT COUNT(*) FROM image_evidence WHERE revision_id=r.id)
                    +(SELECT COUNT(*) FROM audio_spans WHERE revision_id=r.id AND index_ordinal IS NOT NULL)=NEW.segment_count
                  %s)
              BEGIN SELECT RAISE(ABORT,'invalid index publication'); END
              """
                  .formatted(audioPublicationReady));
          execute("DROP TRIGGER active_corpus_publication_complete");
          execute(
              """
              CREATE TRIGGER active_corpus_publication_complete BEFORE INSERT ON active_corpus_publications
              WHEN NOT EXISTS(SELECT 1 FROM index_publications p JOIN indexing_jobs j ON j.id=p.job_id AND j.state='processing'
                AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id
                JOIN corpus_revisions r ON r.id=p.revision_id AND r.document_id=p.document_id
                WHERE p.id=NEW.publication_id AND p.document_id=NEW.document_id AND p.revision_id=NEW.revision_id
                  AND p.segment_count=(SELECT COUNT(*) FROM index_publication_entries WHERE publication_id=p.id)
                    +(SELECT COUNT(*) FROM image_publication_entries WHERE publication_id=p.id)
                    +(SELECT COUNT(*) FROM audio_publication_entries WHERE publication_id=p.id)
                  %s)
              BEGIN SELECT RAISE(ABORT,'incomplete index publication'); END
              """
                  .formatted(audioPublicationReady));
          execute("DROP TRIGGER indexing_published_state");
          execute(
              """
              CREATE TRIGGER indexing_published_state BEFORE UPDATE OF state ON indexing_jobs
              WHEN NEW.state='indexed' AND NOT EXISTS(SELECT 1 FROM index_publications p
                JOIN active_corpus_publications a ON a.publication_id=p.id AND a.document_id=p.document_id AND a.revision_id=p.revision_id
                JOIN corpus_revisions r ON r.id=p.revision_id AND r.document_id=p.document_id
                WHERE p.job_id=NEW.id AND p.attempt=NEW.attempt AND p.projection_generation_id=NEW.projection_generation_id
                  AND p.segment_count=(SELECT COUNT(*) FROM index_publication_entries WHERE publication_id=p.id)
                    +(SELECT COUNT(*) FROM image_publication_entries WHERE publication_id=p.id)
                    +(SELECT COUNT(*) FROM audio_publication_entries WHERE publication_id=p.id)
                  %s)
              BEGIN SELECT RAISE(ABORT,'missing index publication'); END
              """
                  .formatted(audioPublicationReady));
          for (String table :
              List.of("audio_compilations", "audio_spans", "audio_publication_entries")) {
            for (String operation : List.of("UPDATE", "DELETE")) {
              execute(
                  "CREATE TRIGGER "
                      + table
                      + "_no_"
                      + operation.toLowerCase(java.util.Locale.ROOT)
                      + " BEFORE "
                      + operation
                      + " ON "
                      + table
                      + " BEGIN SELECT RAISE(ABORT,'immutable audio evidence'); END");
            }
          }
          execute("UPDATE format_info SET version=8 WHERE format=?", FORMAT);
          execute("PRAGMA user_version=8");
          return null;
        });
  }

  void migrateVersionSeven() {
    transaction(
        () -> {
          execute(
              """
              CREATE TABLE image_evidence(
                id TEXT PRIMARY KEY NOT NULL,
                revision_id TEXT NOT NULL UNIQUE REFERENCES corpus_revisions(id) ON DELETE RESTRICT,
                width INTEGER NOT NULL CHECK(width BETWEEN 1 AND 12000000),
                height INTEGER NOT NULL CHECK(height BETWEEN 1 AND 12000000 AND width*height<=12000000),
                recall_text TEXT NOT NULL CHECK(length(recall_text) BETWEEN 1 AND 4096 AND length(CAST(recall_text AS BLOB))<=16384),
                recall_sha256 TEXT NOT NULL CHECK(length(recall_sha256)=64 AND recall_sha256 NOT GLOB '*[^a-f0-9]*'),
                description_revision TEXT NOT NULL CHECK(length(description_revision) BETWEEN 1 AND 128),
                created_at TEXT NOT NULL)
              """);
          execute(
              """
              CREATE TRIGGER image_evidence_identity BEFORE INSERT ON image_evidence
              WHEN NOT EXISTS(SELECT 1 FROM corpus_revisions r JOIN documents d ON d.id=r.document_id
                JOIN corpus_documents c ON c.document_id=d.id AND c.initial_revision_id=r.id
                JOIN ingestion_jobs j ON j.document_id=d.id AND j.revision_id=r.id AND j.state='processing'
                WHERE r.id=NEW.revision_id AND d.document_type='image' AND d.mime_type IN ('image/png','image/jpeg')
                  AND r.source_sha256=d.source_sha256
                  AND r.parser_revision='java-image-visual-v1:'||NEW.description_revision
                  AND r.page_count=0 AND r.segment_count=0)
                OR EXISTS(SELECT 1 FROM corpus_pages WHERE revision_id=NEW.revision_id)
                OR EXISTS(SELECT 1 FROM corpus_segments WHERE revision_id=NEW.revision_id)
              BEGIN SELECT RAISE(ABORT,'invalid image evidence identity'); END
              """);
          execute(
              """
              CREATE TRIGGER image_evidence_frozen BEFORE INSERT ON image_evidence
              WHEN EXISTS(SELECT 1 FROM corpus_revisions WHERE id=NEW.revision_id AND parsed_at IS NOT NULL)
              BEGIN SELECT RAISE(ABORT,'immutable image evidence'); END
              """);
          execute(
              """
              CREATE TRIGGER image_evidence_no_replace BEFORE INSERT ON image_evidence
              WHEN EXISTS(SELECT 1 FROM image_evidence WHERE id=NEW.id OR revision_id=NEW.revision_id)
              BEGIN SELECT RAISE(ABORT,'immutable image evidence'); END
              """);
          execute(
              """
              CREATE TRIGGER corpus_revision_visual_complete BEFORE UPDATE OF parsed_at ON corpus_revisions
              WHEN NEW.parser_revision LIKE 'java-image-visual-v1:%' AND NEW.parsed_at IS NOT NULL AND
                (NEW.page_count!=0 OR NEW.segment_count!=0
                  OR (SELECT COUNT(*) FROM image_evidence WHERE revision_id=NEW.id)!=1
                  OR EXISTS(SELECT 1 FROM corpus_pages WHERE revision_id=NEW.id)
                  OR EXISTS(SELECT 1 FROM corpus_segments WHERE revision_id=NEW.id))
              BEGIN SELECT RAISE(ABORT,'incomplete visual preparation'); END
              """);
          execute(
              """
              CREATE TABLE image_publication_entries(
                publication_id TEXT NOT NULL REFERENCES index_publications(id) ON DELETE RESTRICT,
                image_evidence_id TEXT NOT NULL REFERENCES image_evidence(id) ON DELETE RESTRICT,
                physical_segment_id TEXT NOT NULL UNIQUE CHECK(length(physical_segment_id) BETWEEN 1 AND 128),
                entry_sha256 TEXT NOT NULL CHECK(length(entry_sha256)=64 AND entry_sha256 NOT GLOB '*[^a-f0-9]*'),
                PRIMARY KEY(publication_id,image_evidence_id))
              """);
          execute(
              """
              CREATE TRIGGER image_publication_entry_identity BEFORE INSERT ON image_publication_entries
              WHEN NOT EXISTS(SELECT 1 FROM index_publications p
                JOIN indexing_jobs j ON j.id=p.job_id AND j.state='processing'
                  AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id
                JOIN image_evidence i ON i.id=NEW.image_evidence_id AND i.revision_id=p.revision_id
                WHERE p.id=NEW.publication_id)
                OR EXISTS(SELECT 1 FROM index_publication_entries WHERE physical_segment_id=NEW.physical_segment_id)
              BEGIN SELECT RAISE(ABORT,'invalid image publication evidence'); END
              """);
          execute(
              """
              CREATE TRIGGER image_publication_entries_no_replace BEFORE INSERT ON image_publication_entries
              WHEN EXISTS(SELECT 1 FROM image_publication_entries WHERE
                (publication_id=NEW.publication_id AND image_evidence_id=NEW.image_evidence_id)
                OR physical_segment_id=NEW.physical_segment_id)
              BEGIN SELECT RAISE(ABORT,'immutable image publication'); END
              """);
          execute(
              """
              CREATE TRIGGER index_publication_entries_no_image_collision BEFORE INSERT ON index_publication_entries
              WHEN EXISTS(SELECT 1 FROM image_publication_entries WHERE physical_segment_id=NEW.physical_segment_id)
              BEGIN SELECT RAISE(ABORT,'duplicate physical evidence identity'); END
              """);
          execute("DROP TRIGGER index_publication_identity");
          execute(
              """
              CREATE TRIGGER index_publication_identity BEFORE INSERT ON index_publications
              WHEN NOT EXISTS(SELECT 1 FROM indexing_jobs j JOIN corpus_revisions r ON r.id=j.revision_id AND r.document_id=j.document_id
                WHERE j.id=NEW.job_id AND j.state='processing' AND j.document_id=NEW.document_id AND j.revision_id=NEW.revision_id
                  AND j.attempt=NEW.attempt AND j.projection_generation_id=NEW.projection_generation_id
                  AND j.source_sha256=NEW.source_sha256 AND j.parser_revision=NEW.parser_revision
                  AND j.embedding_identity=NEW.embedding_identity AND j.projection_identity=NEW.projection_identity
                  AND j.model_revision=NEW.model_revision AND j.dimensions=NEW.dimensions
                  AND r.segment_count+(SELECT COUNT(*) FROM image_evidence WHERE revision_id=r.id)=NEW.segment_count)
              BEGIN SELECT RAISE(ABORT,'invalid index publication'); END
              """);
          execute("DROP TRIGGER active_corpus_publication_complete");
          execute(
              """
              CREATE TRIGGER active_corpus_publication_complete BEFORE INSERT ON active_corpus_publications
              WHEN NOT EXISTS(SELECT 1 FROM index_publications p JOIN indexing_jobs j ON j.id=p.job_id AND j.state='processing'
                AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id
                WHERE p.id=NEW.publication_id AND p.document_id=NEW.document_id AND p.revision_id=NEW.revision_id
                  AND p.segment_count=(SELECT COUNT(*) FROM index_publication_entries WHERE publication_id=p.id)
                    +(SELECT COUNT(*) FROM image_publication_entries WHERE publication_id=p.id))
              BEGIN SELECT RAISE(ABORT,'incomplete index publication'); END
              """);
          execute("DROP TRIGGER indexing_published_state");
          execute(
              """
              CREATE TRIGGER indexing_published_state BEFORE UPDATE OF state ON indexing_jobs
              WHEN NEW.state='indexed' AND NOT EXISTS(SELECT 1 FROM index_publications p
                JOIN active_corpus_publications a ON a.publication_id=p.id AND a.document_id=p.document_id AND a.revision_id=p.revision_id
                WHERE p.job_id=NEW.id AND p.attempt=NEW.attempt AND p.projection_generation_id=NEW.projection_generation_id
                  AND p.segment_count=(SELECT COUNT(*) FROM index_publication_entries WHERE publication_id=p.id)
                    +(SELECT COUNT(*) FROM image_publication_entries WHERE publication_id=p.id))
              BEGIN SELECT RAISE(ABORT,'missing index publication'); END
              """);
          execute(
              """
              CREATE TABLE image_trace_evidence(
                trace_id TEXT NOT NULL REFERENCES query_traces(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
                citation_ordinal INTEGER NOT NULL CHECK(citation_ordinal BETWEEN 1 AND 32),
                publication_id TEXT NOT NULL,image_evidence_id TEXT NOT NULL,physical_segment_id TEXT NOT NULL,
                source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64 AND source_sha256 NOT GLOB '*[^a-f0-9]*'),
                retrieval_score REAL NOT NULL CHECK(retrieval_score BETWEEN -1.7976931348623157e308 AND 1.7976931348623157e308),
                rerank_score REAL NOT NULL CHECK(rerank_score BETWEEN -1.7976931348623157e308 AND 1.7976931348623157e308),
                fact_sha256 TEXT NOT NULL CHECK(length(fact_sha256) BETWEEN 64 AND 519 AND fact_sha256 NOT GLOB '*[^a-f0-9,]*'),
                visual_model_revision TEXT NOT NULL CHECK(length(visual_model_revision) BETWEEN 1 AND 160),
                visual_policy_revision TEXT NOT NULL CHECK(length(visual_policy_revision) BETWEEN 1 AND 160),
                PRIMARY KEY(trace_id,citation_ordinal),
                FOREIGN KEY(trace_id,publication_id) REFERENCES query_trace_documents(trace_id,publication_id) ON DELETE RESTRICT,
                FOREIGN KEY(publication_id,image_evidence_id) REFERENCES image_publication_entries(publication_id,image_evidence_id) ON DELETE RESTRICT)
              """);
          execute(
              """
              CREATE TRIGGER image_trace_evidence_identity BEFORE INSERT ON image_trace_evidence
              WHEN NOT EXISTS(SELECT 1 FROM image_publication_entries e JOIN index_publications p ON p.id=e.publication_id
                JOIN image_evidence i ON i.id=e.image_evidence_id AND i.revision_id=p.revision_id
                WHERE e.publication_id=NEW.publication_id AND e.image_evidence_id=NEW.image_evidence_id
                  AND e.physical_segment_id=NEW.physical_segment_id AND p.source_sha256=NEW.source_sha256)
              BEGIN SELECT RAISE(ABORT,'invalid image citation'); END
              """);
          execute(
              """
              CREATE TRIGGER image_trace_evidence_sealed BEFORE INSERT ON image_trace_evidence
              WHEN EXISTS(SELECT 1 FROM query_traces WHERE id=NEW.trace_id)
              BEGIN SELECT RAISE(ABORT,'sealed query trace'); END
              """);
          execute(
              """
              CREATE TRIGGER image_trace_evidence_no_replace BEFORE INSERT ON image_trace_evidence
              WHEN EXISTS(SELECT 1 FROM image_trace_evidence WHERE trace_id=NEW.trace_id AND citation_ordinal=NEW.citation_ordinal)
              BEGIN SELECT RAISE(ABORT,'immutable image citation'); END
              """);
          execute("DROP TRIGGER query_traces_complete");
          execute(
              """
              CREATE TRIGGER query_traces_complete BEFORE INSERT ON query_traces WHEN
                NEW.scope_count!=(SELECT COUNT(*) FROM query_trace_documents WHERE trace_id=NEW.id)
                OR NEW.citation_count!=(SELECT COUNT(*) FROM query_trace_evidence WHERE trace_id=NEW.id)
                  +(SELECT COUNT(*) FROM image_trace_evidence WHERE trace_id=NEW.id)
                OR (NEW.scope_count>0 AND ((SELECT MIN(ordinal) FROM query_trace_documents WHERE trace_id=NEW.id)!=0
                  OR (SELECT MAX(ordinal) FROM query_trace_documents WHERE trace_id=NEW.id)!=NEW.scope_count-1))
                OR (NEW.citation_count>0 AND (SELECT COUNT(DISTINCT citation_ordinal)!=NEW.citation_count
                  OR MIN(citation_ordinal)!=1 OR MAX(citation_ordinal)!=NEW.citation_count
                  FROM (SELECT citation_ordinal FROM query_trace_evidence WHERE trace_id=NEW.id
                    UNION ALL SELECT citation_ordinal FROM image_trace_evidence WHERE trace_id=NEW.id)))
                OR EXISTS(SELECT 1 FROM query_trace_documents q JOIN index_publications p ON p.id=q.publication_id
                  JOIN documents d ON d.id=p.document_id WHERE q.trace_id=NEW.id AND d.workspace_id!=NEW.workspace_id)
              BEGIN SELECT RAISE(ABORT,'incomplete query trace'); END
              """);
          for (String table :
              List.of("image_evidence", "image_publication_entries", "image_trace_evidence")) {
            for (String operation : List.of("UPDATE", "DELETE")) {
              execute(
                  "CREATE TRIGGER "
                      + table
                      + "_no_"
                      + operation.toLowerCase(java.util.Locale.ROOT)
                      + " BEFORE "
                      + operation
                      + " ON "
                      + table
                      + " BEGIN SELECT RAISE(ABORT,'immutable visual evidence'); END");
            }
          }
          execute("UPDATE format_info SET version=7 WHERE format=?", FORMAT);
          execute("PRAGMA user_version=7");
          return null;
        });
  }

  void migrateVersionSix() {
    transaction(
        () -> {
          execute(
              """
              CREATE TABLE image_text_regions(
                revision_id TEXT NOT NULL,page_number INTEGER NOT NULL CHECK(page_number=1),
                ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 49999),
                start_offset INTEGER NOT NULL CHECK(start_offset>=0),
                end_offset INTEGER NOT NULL CHECK(end_offset>start_offset),
                left_pixel INTEGER NOT NULL CHECK(left_pixel>=0),
                top_pixel INTEGER NOT NULL CHECK(top_pixel>=0),
                right_pixel INTEGER NOT NULL CHECK(right_pixel>left_pixel),
                bottom_pixel INTEGER NOT NULL CHECK(bottom_pixel>top_pixel),
                PRIMARY KEY(revision_id,ordinal),
                FOREIGN KEY(revision_id,page_number) REFERENCES corpus_pages(revision_id,page_number) ON DELETE RESTRICT)
              """);
          execute(
              """
              CREATE TRIGGER image_text_regions_identity BEFORE INSERT ON image_text_regions
              WHEN NOT EXISTS(SELECT 1 FROM corpus_revisions r JOIN documents d ON d.id=r.document_id
                WHERE r.id=NEW.revision_id AND d.document_type='image')
                OR (NEW.ordinal>0 AND NOT EXISTS(SELECT 1 FROM image_text_regions p
                  WHERE p.revision_id=NEW.revision_id AND p.ordinal=NEW.ordinal-1 AND p.end_offset<=NEW.start_offset))
              BEGIN SELECT RAISE(ABORT,'invalid image region identity'); END
              """);
          execute(
              """
              CREATE TRIGGER image_text_regions_frozen BEFORE INSERT ON image_text_regions
              WHEN EXISTS(SELECT 1 FROM corpus_revisions WHERE id=NEW.revision_id AND parsed_at IS NOT NULL)
              BEGIN SELECT RAISE(ABORT,'immutable image regions'); END
              """);
          execute(
              """
              CREATE TRIGGER image_text_regions_no_replace BEFORE INSERT ON image_text_regions
              WHEN EXISTS(SELECT 1 FROM image_text_regions WHERE revision_id=NEW.revision_id AND ordinal=NEW.ordinal)
              BEGIN SELECT RAISE(ABORT,'immutable image regions'); END
              """);
          for (String operation : List.of("UPDATE", "DELETE")) {
            execute(
                "CREATE TRIGGER image_text_regions_no_"
                    + operation.toLowerCase(java.util.Locale.ROOT)
                    + " BEFORE "
                    + operation
                    + " ON image_text_regions"
                    + " BEGIN SELECT RAISE(ABORT,'immutable image regions'); END");
          }
          execute("UPDATE format_info SET version=6 WHERE format=?", FORMAT);
          execute("PRAGMA user_version=6");
          return null;
        });
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
