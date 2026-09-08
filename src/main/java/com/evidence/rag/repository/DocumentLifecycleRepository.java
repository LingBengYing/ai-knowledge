package com.evidence.rag.repository;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.entity.DocumentEntity;
import com.evidence.rag.model.entity.DocumentRemovalEntity;
import java.util.Objects;
import java.util.Optional;

/** Removal history and current raw ACL, within the caller's shared authority transaction. */
public final class DocumentLifecycleRepository {
  private final SqliteAuthorityStore store;

  public DocumentLifecycleRepository(SqliteAuthorityStore store) {
    this.store = Objects.requireNonNull(store);
  }

  /** A repeated request still requires current write permission, even after removal. */
  public Optional<DocumentEntity> findWritableDocument(Actor actor, String documentId) {
    return store
        .rows(
            """
            SELECT d.*,acl.role AS current_role,f.name AS folder_name
            FROM documents d JOIN document_acl acl ON acl.document_id=d.id
            LEFT JOIN folders f ON f.id=d.folder_id AND f.workspace_id=d.workspace_id
            WHERE d.id=? AND d.workspace_id=? AND acl.principal_id=?
              AND acl.role IN ('owner','editor')
            """,
            documentId,
            actor.workspaceId(),
            actor.principalId())
        .stream()
        .findFirst()
        .map(AuthorityRows::document);
  }

  /** Historical receipt only; the Service separately checks the caller's current raw ACL. */
  public Optional<DocumentRemovalEntity> findRemoval(Actor actor, String documentId) {
    return store
        .rows(
            "SELECT document_id,workspace_id,requested_by,requested_at FROM document_tombstones WHERE document_id=? AND workspace_id=?",
            documentId,
            actor.workspaceId())
        .stream()
        .findFirst()
        .map(
            row ->
                new DocumentRemovalEntity(
                    AuthorityRows.text(row, "document_id"),
                    AuthorityRows.text(row, "workspace_id"),
                    AuthorityRows.text(row, "requested_by"),
                    AuthorityRows.text(row, "requested_at")));
  }

  public void insertRemoval(DocumentRemovalEntity removal) {
    store.execute(
        "INSERT INTO document_tombstones(document_id,workspace_id,requested_by,requested_at) VALUES(?,?,?,?)",
        removal.documentId(),
        removal.workspaceId(),
        removal.requestedBy(),
        removal.requestedAt());
  }
}
