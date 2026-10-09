package com.evidence.rag.repository;

import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.VectorBindingIdentity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Internal SQL materialization for binding metadata, never an authority or provider substitute. */
final class VectorBindingRows {
  private VectorBindingRows() {}

  static PublicationVersion publication(SqliteAuthorityStore store, String workspace, String id) {
    var rows =
        store.rows(
            "SELECT p.* FROM index_publications p JOIN documents d ON d.id=p.document_id WHERE p.id=? AND d.workspace_id=?",
            id,
            workspace);
    if (rows.size() != 1) {
      throw ModelValues.invalid();
    }
    var row = rows.getFirst();
    return new PublicationVersion(
        AuthorityRows.text(row, "document_id"),
        AuthorityRows.text(row, "id"),
        AuthorityRows.text(row, "revision_id"),
        AuthorityRows.text(row, "projection_generation_id"),
        AuthorityRows.text(row, "source_sha256"),
        AuthorityRows.text(row, "parser_revision"),
        target(row),
        AuthorityRows.text(row, "manifest_sha256"),
        AuthorityRows.integer(row, "segment_count"));
  }

  static IndexTarget target(Map<String, Object> row) {
    return new IndexTarget(
        AuthorityRows.text(row, "embedding_identity"),
        AuthorityRows.text(row, "projection_identity"),
        AuthorityRows.text(row, "model_revision"),
        AuthorityRows.integer(row, "dimensions"));
  }

  static void requireBase(SqliteAuthorityStore store, String workspace, PublicationVersion base) {
    if (!publication(store, workspace, base.publicationId()).equals(base)) {
      throw ModelValues.invalid();
    }
  }

  static String physical(
      SqliteAuthorityStore store, PublicationVersion base, String evidence, boolean image) {
    String table = image ? "image_publication_entries" : "audio_publication_entries";
    String column = image ? "image_evidence_id" : "audio_span_id";
    var rows =
        store.rows(
            "SELECT physical_segment_id FROM "
                + table
                + " WHERE publication_id=? AND "
                + column
                + "=?",
            base.publicationId(),
            evidence);
    if (rows.size() != 1) {
      throw ModelValues.invalid();
    }
    String id = AuthorityRows.text(rows.getFirst(), "physical_segment_id");
    if (!VectorBindingIdentity.physicalSegmentId(base.projectionGenerationId(), evidence)
        .equals(id)) {
      throw ModelValues.invalid();
    }
    return id;
  }

  static void requireProvenance(
      SqliteAuthorityStore store,
      PublicationVersion base,
      String from,
      String originId,
      boolean image) {
    if (from == null) {
      return;
    }
    String origins = image ? "image_vector_publications" : "audio_vector_publications";
    String bindings = image ? "image_vector_bindings" : "audio_vector_bindings";
    if (store.count(
            "SELECT COUNT(*) FROM index_publications p JOIN indexing_jobs j ON j.id=p.job_id WHERE p.id=? AND j.rebuild_sequence>0 AND j.base_vector_set_sha256 IS NOT NULL AND j.base_publication_id=? AND EXISTS(SELECT 1 FROM "
                + origins
                + " v WHERE v.id=? AND (v.publication_id=j.base_publication_id OR EXISTS(SELECT 1 FROM "
                + bindings
                + " b WHERE b.publication_id=j.base_publication_id AND b.origin_vector_publication_id=v.id)))",
            base.publicationId(),
            from,
            originId)
        != 1) {
      throw ModelValues.invalid();
    }
  }

  static void requireModelRebuildProvenance(
      SqliteAuthorityStore store,
      PublicationVersion base,
      String from,
      String originId,
      String modelRebuildId,
      boolean image) {
    if (modelRebuildId == null) {
      return;
    }
    String bindings = image ? "image_vector_bindings" : "audio_vector_bindings";
    if (from == null
        || store.count(
                "SELECT COUNT(*) FROM index_publications p JOIN indexing_jobs j ON j.id=p.job_id JOIN model_rebuilds b ON b.id=? WHERE p.id=? AND j.base_publication_id=? AND ((j.model_rebuild_id=b.id AND p.embedding_identity=b.embedding_identity AND p.projection_identity=b.projection_identity AND p.model_revision=b.model_revision AND p.dimensions=b.dimensions AND EXISTS(SELECT 1 FROM model_rebuild_items i WHERE i.batch_id=b.id AND i.job_id=j.id AND i.document_id=p.document_id AND i.revision_id=p.revision_id AND i.base_publication_id=j.base_publication_id AND i.base_vector_set_sha256=j.base_vector_set_sha256)) OR (j.model_rebuild_id IS NULL AND b.state='completed' AND EXISTS(SELECT 1 FROM "
                    + bindings
                    + " previous JOIN index_publications prior ON prior.id=previous.publication_id WHERE previous.publication_id=j.base_publication_id AND previous.origin_vector_publication_id=? AND previous.model_rebuild_id=b.id AND prior.embedding_identity=p.embedding_identity AND prior.projection_identity=p.projection_identity AND prior.model_revision=p.model_revision AND prior.dimensions=p.dimensions)))",
                modelRebuildId,
                base.publicationId(),
                from,
                originId)
            != 1) {
      throw ModelValues.invalid();
    }
  }

  static List<Map<String, Object>> candidates(
      SqliteAuthorityStore store,
      String table,
      String bindings,
      PublicationVersion base,
      IndexTarget target) {
    var args = new ArrayList<Object>();
    args.add(base.publicationId());
    args.add(base.publicationId());
    String modelColumn =
        store.count(
                    "SELECT COUNT(*) FROM pragma_table_info(?) WHERE name='model_rebuild_id'",
                    bindings)
                == 1
            ? "b.model_rebuild_id"
            : "NULL";
    String sql =
        "SELECT v.*,NULL AS inherited_from_publication_id,NULL AS binding_sha256,NULL AS model_rebuild_id FROM "
            + table
            + " v WHERE v.publication_id=? UNION ALL SELECT v.*,b.inherited_from_publication_id,b.binding_sha256,"
            + modelColumn
            + " AS model_rebuild_id FROM "
            + bindings
            + " b JOIN "
            + table
            + " v ON v.id=b.origin_vector_publication_id WHERE b.publication_id=?";
    if (target != null) {
      sql =
          "SELECT * FROM ("
              + sql
              + ") WHERE embedding_identity=? AND projection_identity=? AND model_revision=? AND dimensions=?";
      args.add(target.embeddingIdentity());
      args.add(target.projectionIdentity());
      args.add(target.modelRevision());
      args.add(target.dimensions());
    }
    return store.rows(sql + " ORDER BY id", args.toArray());
  }
}
