package com.evidence.rag.repository;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.IndexTarget;

/** Called in one authority transaction while the true maintenance lease prevents new work. */
public final class TextModelTargetRepository {
  private final SqliteAuthorityStore store;

  public TextModelTargetRepository(SqliteAuthorityStore store) {
    this.store = store;
  }

  public void requireCompatible(String workspaceId, IndexTarget target) {
    long conflicts =
        store.count(
            """
        SELECT COUNT(*) FROM indexing_jobs j JOIN documents d ON d.id=j.document_id
        WHERE d.workspace_id=? AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
        AND (j.embedding_identity!=? OR j.projection_identity!=? OR j.model_revision!=? OR j.dimensions!=?)
        """,
            workspaceId,
            target.embeddingIdentity(),
            target.projectionIdentity(),
            target.modelRevision(),
            target.dimensions());
    conflicts +=
        store.count(
            """
        SELECT COUNT(*) FROM active_corpus_publications a JOIN index_publications p ON p.id=a.publication_id
        JOIN documents d ON d.id=a.document_id WHERE d.workspace_id=?
        AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
        AND (p.embedding_identity!=? OR p.projection_identity!=? OR p.model_revision!=? OR p.dimensions!=?)
        """,
            workspaceId,
            target.embeddingIdentity(),
            target.projectionIdentity(),
            target.modelRevision(),
            target.dimensions());
    conflicts +=
        store.count(
            """
        SELECT COUNT(*) FROM cleanup_projection_attempts p JOIN documents d ON d.id=p.document_id
        WHERE d.workspace_id=? AND p.route='legacy'
        AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
        AND (p.embedding_identity!=? OR p.projection_identity!=? OR p.dimensions!=?)
        """,
            workspaceId,
            target.embeddingIdentity(),
            target.projectionIdentity(),
            target.dimensions());
    if (conflicts != 0) {
      throw new ApplicationException(
          FailureKind.CONFLICT, "model_rebuild_required", "现有索引或任务与配置不兼容，需要专门的重建操作；当前配置已保留。");
    }
  }
}
