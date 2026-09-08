package com.evidence.rag.repository;

import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.entity.AuditEventEntity;
import com.evidence.rag.model.entity.DocumentEntity;
import com.evidence.rag.model.entity.FolderEntity;
import com.evidence.rag.model.entity.TaskEntity;
import java.util.Map;

/** JDBC row conversion stays inside Repository; no untyped row escapes this package. */
final class AuthorityRows {
  private AuthorityRows() {}

  static String text(Map<String, Object> row, String column) {
    return (String) row.get(column);
  }

  static int integer(Map<String, Object> row, String column) {
    return row.get(column) == null ? 0 : ((Number) row.get(column)).intValue();
  }

  static long number(Map<String, Object> row, String column) {
    return row.get(column) == null ? 0 : ((Number) row.get(column)).longValue();
  }

  static DocumentEntity document(Map<String, Object> row) {
    return new DocumentEntity(
        text(row, "id"),
        text(row, "workspace_id"),
        text(row, "filename"),
        text(row, "document_type"),
        text(row, "mime_type"),
        text(row, "active_revision_id"),
        text(row, "source_sha256"),
        number(row, "size_bytes"),
        text(row, "updated_at"),
        text(row, "display_name"),
        text(row, "folder_id"),
        text(row, "current_role"),
        text(row, "folder_name"));
  }

  static TaskEntity task(Map<String, Object> row, boolean indexing) {
    return new TaskEntity(
        text(row, "id"),
        text(row, "document_id"),
        text(row, "revision_id"),
        text(row, "workspace_id"),
        text(row, "filename"),
        text(row, "mime_type"),
        text(row, "source_sha256"),
        text(row, "parser_revision"),
        text(row, "state"),
        integer(row, "attempt"),
        text(row, "claim_token_sha256"),
        text(row, "created_by"),
        text(row, "error_code"),
        text(row, "created_at"),
        text(row, "updated_at"),
        text(row, "current_role"),
        indexing
            ? new IndexTarget(
                text(row, "embedding_identity"),
                text(row, "projection_identity"),
                text(row, "model_revision"),
                integer(row, "dimensions"))
            : null,
        text(row, "projection_generation_id"));
  }

  static FolderEntity folder(Map<String, Object> row) {
    return new FolderEntity(
        text(row, "id"),
        text(row, "workspace_id"),
        text(row, "owner_id"),
        text(row, "name"),
        number(row, "document_count"));
  }

  static AuditEventEntity audit(Map<String, Object> row) {
    return new AuditEventEntity(
        text(row, "id"),
        text(row, "workspace_id"),
        text(row, "actor_id"),
        text(row, "entity_id"),
        text(row, "action"),
        text(row, "fields_json"),
        text(row, "before_sha256"),
        text(row, "after_sha256"),
        text(row, "created_at"));
  }
}
