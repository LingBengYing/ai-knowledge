package com.evidence.rag.repository;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.model.domain.SyntheticDocument;
import com.evidence.rag.model.entity.AuditEventEntity;
import com.evidence.rag.model.entity.DocumentEntity;
import com.evidence.rag.model.entity.DocumentEvidenceEntity;
import com.evidence.rag.model.entity.FolderEntity;
import com.evidence.rag.model.query.DocumentQuery;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Bound SQL and authority row mapping. Callers must establish the shared Store transaction. */
public final class ManagementRepository {
  private final SqliteAuthorityStore store;

  public ManagementRepository(SqliteAuthorityStore store) {
    this.store = Objects.requireNonNull(store);
  }

  public boolean documentExists(String id) {
    return store.count("SELECT COUNT(*) FROM documents WHERE id=?", id) != 0;
  }

  public void insertDocument(Actor owner, SyntheticDocument document, String now) {
    store.execute(
        "INSERT INTO documents VALUES(?,?,?,?,?,?,?,?,?,?,NULL)",
        document.documentId(),
        owner.workspaceId(),
        document.filename(),
        document.type(),
        document.mimeType(),
        document.revisionId(),
        document.sha256(),
        document.sizeBytes(),
        now,
        document.filename());
  }

  public void insertGrant(String documentId, String principalId, String role) {
    store.execute("INSERT INTO document_acl VALUES(?,?,?)", documentId, principalId, role);
  }

  public String currentRole(Actor actor, String documentId) {
    var rows =
        store.rows(
            "SELECT acl.role FROM documents d JOIN document_acl acl ON acl.document_id=d.id WHERE d.id=? AND d.workspace_id=? AND acl.principal_id=? AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)",
            documentId,
            actor.workspaceId(),
            actor.principalId());
    return rows.isEmpty() ? null : AuthorityRows.text(rows.getFirst(), "role");
  }

  public Optional<DocumentEntity> findAuthorizedDocument(Actor actor, String id, boolean edit) {
    var rows =
        store.rows(
            "SELECT d.*,acl.role AS current_role,f.name AS folder_name FROM documents d JOIN document_acl acl ON acl.document_id=d.id LEFT JOIN folders f ON f.id=d.folder_id WHERE d.id=? AND d.workspace_id=? AND acl.principal_id=? AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)"
                + (edit ? " AND acl.role IN ('owner','editor')" : ""),
            id,
            actor.workspaceId(),
            actor.principalId());
    return rows.stream().findFirst().map(AuthorityRows::document);
  }

  public Optional<DocumentOriginal> findDocumentOriginal(Actor actor, String id) {
    var videoAv =
        store.rows(
            """
        SELECT d.id,d.filename,d.document_type,d.mime_type,d.source_sha256,d.size_bytes,
          s.source_revision_id,s.original_blob
        FROM documents d JOIN document_acl a ON a.document_id=d.id
        JOIN video_av_originals s ON s.document_id=d.id AND s.source_revision_id=d.active_revision_id
          AND s.source_sha256=d.source_sha256 AND s.filename=d.filename
          AND s.media_type=d.mime_type AND s.size_bytes=d.size_bytes
        WHERE d.id=? AND d.workspace_id=? AND a.principal_id=?
          AND a.role IN ('owner','editor','reader') AND d.document_type='video'
          AND length(s.original_blob)=d.size_bytes AND d.size_bytes BETWEEN 1 AND 20971520
          AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
        """,
            id,
            actor.workspaceId(),
            actor.principalId());
    if (!videoAv.isEmpty()) {
      var row = videoAv.getFirst();
      return Optional.of(
          new DocumentOriginal(
              AuthorityRows.text(row, "id"),
              AuthorityRows.text(row, "source_revision_id"),
              AuthorityRows.text(row, "filename"),
              AuthorityRows.text(row, "document_type"),
              AuthorityRows.text(row, "mime_type"),
              AuthorityRows.text(row, "source_sha256"),
              AuthorityRows.number(row, "size_bytes"),
              (byte[]) row.get("original_blob")));
    }
    var sound =
        store.rows(
            """
        SELECT d.id,d.filename,d.document_type,d.mime_type,d.source_sha256,d.size_bytes,
          s.source_revision_id,s.original_blob
        FROM documents d JOIN document_acl a ON a.document_id=d.id
        JOIN sound_originals s ON s.document_id=d.id AND s.source_revision_id=d.active_revision_id
          AND s.source_sha256=d.source_sha256 AND s.filename=d.filename
          AND s.media_type=d.mime_type AND s.size_bytes=d.size_bytes
        WHERE d.id=? AND d.workspace_id=? AND a.principal_id=?
          AND a.role IN ('owner','editor','reader') AND d.document_type='audio'
          AND length(s.original_blob)=d.size_bytes AND d.size_bytes BETWEEN 1 AND 20971520
          AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
        """,
            id,
            actor.workspaceId(),
            actor.principalId());
    if (!sound.isEmpty()) {
      var row = sound.getFirst();
      return Optional.of(
          new DocumentOriginal(
              AuthorityRows.text(row, "id"),
              AuthorityRows.text(row, "source_revision_id"),
              AuthorityRows.text(row, "filename"),
              AuthorityRows.text(row, "document_type"),
              AuthorityRows.text(row, "mime_type"),
              AuthorityRows.text(row, "source_sha256"),
              AuthorityRows.number(row, "size_bytes"),
              (byte[]) row.get("original_blob")));
    }
    return store
        .rows(
            """
        SELECT d.id,d.filename,d.document_type,d.mime_type,d.source_sha256,d.size_bytes,
          c.initial_revision_id,c.original_blob
        FROM documents d JOIN document_acl acl ON acl.document_id=d.id
        JOIN corpus_documents c ON c.document_id=d.id AND c.initial_revision_id=d.active_revision_id
        JOIN corpus_revisions r ON r.id=c.initial_revision_id AND r.document_id=d.id
          AND r.source_sha256=d.source_sha256
        WHERE d.id=? AND d.workspace_id=? AND acl.principal_id=?
          AND acl.role IN ('owner','editor','reader')
          AND LENGTH(c.original_blob) BETWEEN 1 AND 20971520
          AND LENGTH(c.original_blob)=d.size_bytes
          AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
        """,
            id,
            actor.workspaceId(),
            actor.principalId())
        .stream()
        .findFirst()
        .map(
            row ->
                new DocumentOriginal(
                    AuthorityRows.text(row, "id"),
                    AuthorityRows.text(row, "initial_revision_id"),
                    AuthorityRows.text(row, "filename"),
                    AuthorityRows.text(row, "document_type"),
                    AuthorityRows.text(row, "mime_type"),
                    AuthorityRows.text(row, "source_sha256"),
                    AuthorityRows.number(row, "size_bytes"),
                    (byte[]) row.get("original_blob")));
  }

  public long countDocuments(Actor actor, DocumentQuery query) {
    var filter = filter(actor, query);
    return store.count("SELECT COUNT(*)" + DOCUMENT_FROM + filter.sql(), filter.args().toArray());
  }

  public List<DocumentEntity> findDocuments(Actor actor, DocumentQuery query) {
    var filter = filter(actor, query);
    var args = new ArrayList<>(filter.args());
    args.add(query.pageSize());
    args.add((long) (query.page() - 1) * query.pageSize());
    String sort =
        switch (query.sort()) {
          case "updated_desc" -> "d.updated_at DESC";
          case "updated_asc" -> "d.updated_at ASC";
          case "name_asc" -> "d.display_name COLLATE NOCASE ASC";
          case "name_desc" -> "d.display_name COLLATE NOCASE DESC";
          default -> throw new IllegalArgumentException("Unsupported document sort");
        };
    return store
        .rows(
            "SELECT d.*,acl.role AS current_role,f.name AS folder_name"
                + DOCUMENT_FROM
                + " LEFT JOIN folders f ON f.id=d.folder_id AND f.workspace_id=d.workspace_id"
                + filter.sql()
                + " ORDER BY "
                + sort
                + ",d.id ASC LIMIT ? OFFSET ?",
            args.toArray())
        .stream()
        .map(AuthorityRows::document)
        .toList();
  }

  private static final String DOCUMENT_FROM =
      " FROM documents d JOIN document_acl acl ON acl.document_id=d.id LEFT JOIN ingestion_jobs j ON j.document_id=d.id";

  private record Filter(String sql, List<Object> args) {}

  private Filter filter(Actor actor, DocumentQuery query) {
    var args = new ArrayList<Object>(List.of(actor.workspaceId(), actor.principalId()));
    var predicate =
        new StringBuilder(
            " WHERE d.workspace_id=? AND acl.principal_id=? AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)");
    if (!query.q().isEmpty()) {
      predicate.append(
          " AND (instr(lower(d.filename),lower(?))>0 OR instr(lower(d.display_name),lower(?))>0)");
      args.add(query.q());
      args.add(query.q());
    }
    if (query.type() != null) {
      predicate.append(" AND d.document_type=?");
      args.add(query.type());
    }
    if (query.status() != null) {
      predicate.append(
          " AND COALESCE(j.state,CASE WHEN EXISTS(SELECT 1 FROM sound_originals so JOIN sound_publications sp ON sp.document_id=so.document_id AND sp.source_revision_id=so.source_revision_id AND sp.source_sha256=so.source_sha256 WHERE so.document_id=d.id) OR EXISTS(SELECT 1 FROM video_av_originals vo JOIN video_av_publications vp ON vp.document_id=vo.document_id AND vp.source_revision_id=vo.source_revision_id AND vp.source_sha256=vo.source_sha256 WHERE vo.document_id=d.id) THEN 'parsed' ELSE 'ready' END)=?");
      args.add(query.status());
    }
    if ("unfiled".equals(query.folderId())) {
      predicate.append(" AND d.folder_id IS NULL");
    } else if (query.folderId() != null && !query.folderId().isEmpty()) {
      predicate.append(" AND d.folder_id=?");
      args.add(query.folderId());
    }
    if (query.tag() != null) {
      predicate.append(
          " AND EXISTS(SELECT 1 FROM document_tags t WHERE t.document_id=d.id AND t.tag=?)");
      args.add(query.tag());
    }
    return new Filter(predicate.toString(), args);
  }

  public Optional<DocumentEvidenceEntity> evidence(String id) {
    return store
        .rows(
            "SELECT a.revision_id AS active_revision_id,a.publication_id,j.id,j.state,COALESCE(r.segment_count,0) AS segment_count FROM corpus_documents c JOIN ingestion_jobs j ON j.document_id=c.document_id LEFT JOIN corpus_revisions r ON r.id=c.parsed_revision_id LEFT JOIN active_corpus_publications a ON a.document_id=c.document_id WHERE c.document_id=? AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=c.document_id)",
            id)
        .stream()
        .findFirst()
        .map(
            row ->
                new DocumentEvidenceEntity(
                    AuthorityRows.text(row, "active_revision_id"),
                    AuthorityRows.text(row, "publication_id"),
                    AuthorityRows.text(row, "id"),
                    AuthorityRows.text(row, "state"),
                    AuthorityRows.integer(row, "segment_count")));
  }

  public List<String> documentTags(String id) {
    return store
        .rows(
            "SELECT tag FROM document_tags dt WHERE document_id=? AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=dt.document_id) ORDER BY ordinal,tag",
            id)
        .stream()
        .map(row -> AuthorityRows.text(row, "tag"))
        .toList();
  }

  public void updateMetadata(
      Actor actor, String id, String displayName, String folderId, String now) {
    store.execute(
        "UPDATE documents SET display_name=?,folder_id=?,updated_at=? WHERE id=? AND workspace_id=? AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=documents.id)",
        displayName,
        folderId,
        now,
        id,
        actor.workspaceId());
  }

  public void replaceTags(String documentId, List<String> tags) {
    store.execute("DELETE FROM document_tags WHERE document_id=?", documentId);
    for (int index = 0; index < tags.size(); index++) {
      store.execute("INSERT INTO document_tags VALUES(?,?,?)", documentId, tags.get(index), index);
    }
  }

  public List<FolderEntity> findFolders(Actor actor) {
    return store
        .rows(
            """
        SELECT f.id,f.workspace_id,f.name,f.owner_id,COUNT(acl.document_id) AS document_count
        FROM folders f LEFT JOIN documents d ON d.folder_id=f.id AND d.workspace_id=f.workspace_id
          AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
        LEFT JOIN document_acl acl ON acl.document_id=d.id AND acl.principal_id=?
        WHERE f.workspace_id=? GROUP BY f.id HAVING f.owner_id=? OR COUNT(acl.document_id)>0
        ORDER BY f.name COLLATE NOCASE,f.id
        """,
            actor.principalId(),
            actor.workspaceId(),
            actor.principalId())
        .stream()
        .map(AuthorityRows::folder)
        .toList();
  }

  public Optional<FolderEntity> findVisibleFolder(Actor actor, String id, boolean ownerOnly) {
    return store
        .rows(
            """
        SELECT f.* FROM folders f WHERE f.id=? AND f.workspace_id=? AND (f.owner_id=? OR
        (?=0 AND EXISTS(SELECT 1 FROM documents d JOIN document_acl acl ON acl.document_id=d.id
        WHERE d.folder_id=f.id AND d.workspace_id=f.workspace_id AND acl.principal_id=?
          AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id))))
        """,
            id,
            actor.workspaceId(),
            actor.principalId(),
            ownerOnly ? 1 : 0,
            actor.principalId())
        .stream()
        .findFirst()
        .map(AuthorityRows::folder);
  }

  public boolean folderNameExists(Actor actor, String key, String except) {
    return store.count(
            "SELECT COUNT(*) FROM folders WHERE workspace_id=? AND owner_id=? AND name_key=? AND id!=?",
            actor.workspaceId(),
            actor.principalId(),
            key,
            except)
        != 0;
  }

  public void insertFolder(Actor actor, String id, String name, String key, String now) {
    store.execute(
        "INSERT INTO folders VALUES(?,?,?,?,?,?)",
        id,
        actor.workspaceId(),
        actor.principalId(),
        name,
        key,
        now);
  }

  public void renameFolder(String id, String name, String key, String now) {
    store.execute(
        "UPDATE folders SET name=?,name_key=?,updated_at=? WHERE id=?", name, key, now, id);
  }

  public long folderDocumentCount(String id) {
    return store.count("SELECT COUNT(*) FROM documents WHERE folder_id=?", id);
  }

  public void deleteFolder(String id) {
    store.execute("DELETE FROM folders WHERE id=?", id);
  }

  public List<String> findTags(Actor actor) {
    return store
        .rows(
            """
        SELECT DISTINCT t.tag FROM document_tags t JOIN documents d ON d.id=t.document_id
        JOIN document_acl acl ON acl.document_id=d.id
        WHERE d.workspace_id=? AND acl.principal_id=?
          AND NOT EXISTS(SELECT 1 FROM document_tombstones removed WHERE removed.document_id=d.id)
        ORDER BY t.tag
        """,
            actor.workspaceId(),
            actor.principalId())
        .stream()
        .map(row -> AuthorityRows.text(row, "tag"))
        .toList();
  }

  public void insertAudit(AuditEventEntity audit) {
    store.execute(
        "INSERT INTO management_audit VALUES(?,?,?,?,?,?,?,?,?)",
        audit.id(),
        audit.workspaceId(),
        audit.actorId(),
        audit.entityId(),
        audit.action(),
        audit.fieldsJson(),
        audit.beforeSha256(),
        audit.afterSha256(),
        audit.createdAt());
  }

  public List<AuditEventEntity> findAudit(Actor actor) {
    return store
        .rows(
            "SELECT * FROM management_audit WHERE workspace_id=? AND actor_id=? ORDER BY created_at DESC,id DESC LIMIT 100",
            actor.workspaceId(),
            actor.principalId())
        .stream()
        .map(AuthorityRows::audit)
        .toList();
  }
}
