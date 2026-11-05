package com.evidence.rag.repository;

import com.evidence.rag.model.domain.ModelValues;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

/** v27 keeps candidate publications invisible until one authority/configuration selection. */
final class ModelRebuildSchema {
  private final SqliteAuthorityStore store;

  ModelRebuildSchema(SqliteAuthorityStore store) {
    this.store = store;
  }

  void verify() {
    verify(27);
  }

  void verify(int version) {
    if (store.count("PRAGMA user_version") != version
        || store.count("PRAGMA application_id") != 1163280711
        || store.count(
                "SELECT COUNT(*) FROM format_info WHERE version=? AND format='evidence-rag-java-management-v1'",
                version)
            != 1
        || store.count("SELECT COUNT(*) FROM pragma_foreign_key_check") != 0
        || store.count(
                "SELECT COUNT(*) FROM pragma_table_info('indexing_jobs') WHERE name='model_rebuild_id'")
            != 1
        || store.count("SELECT COUNT(*) FROM text_runtime_selection WHERE id=1") != 1) {
      throw new IllegalStateException("Unsupported model rebuild authority format");
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
    for (var row : store.rows("SELECT object_type,name,sql_sha256 FROM cleanup_schema_objects")) {
      expected.put(row.get("object_type") + ":" + row.get("name"), (String) row.get("sql_sha256"));
    }
    if (!actual.equals(expected)) {
      throw new IllegalStateException("Changed model rebuild authority guards");
    }
    if (store.count(
                "SELECT COUNT(*) FROM indexing_jobs j WHERE j.model_rebuild_id IS NULL AND j.replacement_id IS NULL"
                    + (version >= IndexRestartSchema.VERSION ? " AND j.restart_of IS NULL" : "")
                    + " AND ((j.rebuild_sequence=0)!=(j.base_publication_id IS NULL) OR (j.rebuild_sequence>0 AND NOT EXISTS(SELECT 1 FROM index_publications p WHERE p.id=j.base_publication_id AND p.document_id=j.document_id AND p.revision_id=j.revision_id AND p.source_sha256=j.source_sha256 AND p.parser_revision=j.parser_revision AND p.embedding_identity=j.embedding_identity AND p.projection_identity=j.projection_identity AND p.model_revision=j.model_revision AND p.dimensions=j.dimensions)))")
            != 0
        || store.count(
                "SELECT COUNT(*) FROM indexing_jobs j LEFT JOIN model_rebuild_items i ON i.job_id=j.id LEFT JOIN model_rebuilds b ON b.id=j.model_rebuild_id WHERE j.model_rebuild_id IS NOT NULL AND (i.batch_id IS NOT j.model_rebuild_id OR i.document_id IS NOT j.document_id OR i.revision_id IS NOT j.revision_id OR i.base_publication_id IS NOT j.base_publication_id OR b.embedding_identity IS NOT j.embedding_identity OR b.projection_identity IS NOT j.projection_identity OR b.model_revision IS NOT j.model_revision OR b.dimensions IS NOT j.dimensions)")
            != 0) {
      throw new IllegalStateException("Changed model rebuild task identity");
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
}
