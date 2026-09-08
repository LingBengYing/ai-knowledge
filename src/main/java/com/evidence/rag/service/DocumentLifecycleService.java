package com.evidence.rag.service;

import static com.evidence.rag.model.domain.ModelValues.identifier;
import static com.evidence.rag.model.domain.ModelValues.invalid;
import static com.evidence.rag.model.domain.ModelValues.notFound;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.dto.DocumentRemovalResult;
import com.evidence.rag.model.entity.AuditEventEntity;
import com.evidence.rag.model.entity.DocumentRemovalEntity;
import com.evidence.rag.repository.DocumentLifecycleRepository;
import com.evidence.rag.repository.IndexingRepository;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Atomic withdrawal and claim cancellation; physical cleanup remains unimplemented. */
public final class DocumentLifecycleService {
  private final SqliteAuthorityStore store;
  private final DocumentLifecycleRepository lifecycle;
  private final ManagementRepository management;
  private final IngestionRepository ingestion;
  private final IndexingRepository indexing;
  private final DocumentPermissionPolicy permissions;

  public DocumentLifecycleService(
      SqliteAuthorityStore store,
      DocumentLifecycleRepository lifecycle,
      ManagementRepository management,
      IngestionRepository ingestion,
      IndexingRepository indexing,
      DocumentPermissionPolicy permissions) {
    this.store = Objects.requireNonNull(store);
    this.lifecycle = Objects.requireNonNull(lifecycle);
    this.management = Objects.requireNonNull(management);
    this.ingestion = Objects.requireNonNull(ingestion);
    this.indexing = Objects.requireNonNull(indexing);
    this.permissions = Objects.requireNonNull(permissions);
  }

  public DocumentRemovalResult removeDocument(Actor actor, String documentId) {
    if (actor == null) {
      throw invalid();
    }
    identifier(documentId, 100);
    return store.transaction(
        () -> {
          var document =
              lifecycle.findWritableDocument(actor, documentId).orElseThrow(() -> notFound());
          permissions.require(document.currentRole(), true);
          var existing = lifecycle.findRemoval(actor, documentId);
          if (existing.isPresent()) {
            return receipt(existing.orElseThrow());
          }

          // Read current views before inserting the tombstone; all changes commit together.
          var evidence = management.evidence(documentId).orElse(null);
          var parseTask =
              evidence == null ? null : ingestion.findInternalTask(evidence.jobId()).orElseThrow();
          var indexId = indexing.documentJobId(documentId).orElse(null);
          var indexTask = indexId == null ? null : indexing.findInternalTask(indexId).orElseThrow();
          String now = Instant.now().toString();
          if (document.folderId() != null) {
            management.updateMetadata(actor, documentId, document.displayName(), null, now);
            audit(
                actor,
                documentId,
                "document_updated",
                Map.of("folder_id", document.folderId()),
                Collections.singletonMap("folder_id", null),
                Set.of("folder_id"));
          }
          if (parseTask != null && Set.of("queued", "processing").contains(parseTask.state())) {
            ingestion.markCancelled(parseTask.id(), now);
            audit(
                actor,
                documentId,
                "ingestion_cancelled",
                Map.of("state", parseTask.state()),
                Map.of("state", "cancelled"),
                Set.of("state"));
          }
          if (indexTask != null && Set.of("queued", "processing").contains(indexTask.state())) {
            indexing.markCancelled(indexTask.id(), now);
            audit(
                actor,
                documentId,
                "indexing_cancelled",
                Map.of("state", indexTask.state()),
                Map.of("state", "cancelled"),
                Set.of("state"));
          }
          var removal =
              new DocumentRemovalEntity(documentId, actor.workspaceId(), actor.principalId(), now);
          lifecycle.insertRemoval(removal);
          audit(
              actor,
              documentId,
              "document_removal_requested",
              null,
              Map.of("status", "deleting", "cleanup_status", "pending", "requested_at", now),
              Set.of("status", "cleanup_status", "requested_at"));
          return receipt(removal);
        });
  }

  private static DocumentRemovalResult receipt(DocumentRemovalEntity removal) {
    return new DocumentRemovalResult(
        removal.documentId(), "deleting", "pending", removal.requestedAt());
  }

  private void audit(
      Actor actor, String id, String action, Object before, Object after, Set<String> fields) {
    management.insertAudit(AuditEventEntity.create(actor, id, action, before, after, fields));
  }
}
