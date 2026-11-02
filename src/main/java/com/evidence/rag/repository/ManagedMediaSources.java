package com.evidence.rag.repository;

import static com.evidence.rag.repository.AuthorityRows.number;
import static com.evidence.rag.repository.AuthorityRows.text;

import com.evidence.rag.model.domain.Actor;
import java.util.List;
import java.util.Optional;

/**
 * Workspace-scoped source lookups shared by the sound and video audiovisual libraries: live
 * documents of one media type whose original is kept either by that library or by the corpus.
 * Callers hold the Store transaction.
 */
final class ManagedMediaSources {
  static final ManagedMediaSources SOUND = new ManagedMediaSources("audio", "sound_originals");
  static final ManagedMediaSources VIDEO_AV =
      new ManagedMediaSources("video", "video_av_originals");

  private final String documentType;
  private final String originals;
  private final String scope;

  private ManagedMediaSources(String documentType, String originals) {
    this.documentType = documentType;
    this.originals = originals;
    this.scope =
        """
        FROM documents d
        WHERE d.workspace_id=?
          AND d.document_type='%s'
          AND (EXISTS(SELECT 1 FROM %s s WHERE s.document_id=d.id)
            OR EXISTS(SELECT 1 FROM corpus_documents c WHERE c.document_id=d.id))
          AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
        """
            .formatted(documentType, originals);
  }

  /** Every live source document of this media type in the workspace, in id order. */
  List<String> documentIds(SqliteAuthorityStore store, String workspaceId) {
    return store.rows("SELECT d.id " + scope + "ORDER BY d.id", workspaceId).stream()
        .map(row -> text(row, "id"))
        .toList();
  }

  boolean anyDocument(SqliteAuthorityStore store, String workspaceId) {
    return store.count("SELECT count(*) " + scope, workspaceId) != 0;
  }

  /** The active source facts of one document, only when its stored original is intact. */
  Optional<Metadata> metadata(SqliteAuthorityStore store, Actor actor, String id) {
    return store
        .rows(
            """
        SELECT d.id,d.workspace_id,d.active_revision_id,d.source_sha256,d.filename,d.mime_type,d.size_bytes
        FROM documents d
        LEFT JOIN %s s ON s.document_id=d.id
        LEFT JOIN corpus_documents c ON c.document_id=d.id
        WHERE d.id=? AND d.workspace_id=?
          AND d.document_type='%s'
          AND ((s.source_revision_id=d.active_revision_id AND s.source_sha256=d.source_sha256
              AND s.filename=d.filename AND s.media_type=d.mime_type AND s.size_bytes=d.size_bytes AND length(s.original_blob)=d.size_bytes)
            OR (c.initial_revision_id=d.active_revision_id AND length(c.original_blob)=d.size_bytes
              AND EXISTS(SELECT 1 FROM corpus_revisions r WHERE r.id=c.initial_revision_id
                AND r.document_id=d.id AND r.source_sha256=d.source_sha256)))
          AND d.size_bytes BETWEEN 1 AND 20971520
          AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
        """
                .formatted(originals, documentType),
            id,
            actor.workspaceId())
        .stream()
        .findFirst()
        .map(
            row ->
                new Metadata(
                    text(row, "id"),
                    text(row, "workspace_id"),
                    text(row, "active_revision_id"),
                    text(row, "source_sha256"),
                    text(row, "filename"),
                    text(row, "mime_type"),
                    number(row, "size_bytes")));
  }

  record Metadata(
      String id,
      String workspace,
      String revision,
      String sha,
      String filename,
      String mime,
      long bytes) {
    @Override
    public String toString() {
      return "SourceMetadata[redacted]";
    }
  }
}
