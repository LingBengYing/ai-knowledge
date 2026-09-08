package com.evidence.rag.service;

import static com.evidence.rag.model.domain.ModelValues.identifier;
import static com.evidence.rag.model.domain.ModelValues.invalid;
import static com.evidence.rag.model.domain.ModelValues.notFound;
import static com.evidence.rag.model.domain.ModelValues.sha256;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.IndexClaim;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.model.dto.TaskResult;
import com.evidence.rag.model.entity.AuditEventEntity;
import com.evidence.rag.model.entity.IndexPublicationEntity;
import com.evidence.rag.model.entity.TaskEntity;
import com.evidence.rag.repository.IndexingRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Claim fencing and full source-to-projection publication acceptance, under one authority
 * transaction.
 */
public final class IndexingService {
  private static final Set<String> ERRORS =
      Set.of(
          "indexing_failed",
          "indexing_timeout",
          "indexing_output_invalid",
          "worker_interrupted",
          "authorization_changed",
          "index_configuration_changed");
  private final SqliteAuthorityStore store;
  private final IndexingRepository indexing;
  private final ManagementRepository management;
  private final DocumentPermissionPolicy permissions;

  public IndexingService(
      SqliteAuthorityStore store,
      IndexingRepository indexing,
      ManagementRepository management,
      DocumentPermissionPolicy permissions) {
    this.store = Objects.requireNonNull(store);
    this.indexing = Objects.requireNonNull(indexing);
    this.management = Objects.requireNonNull(management);
    this.permissions = Objects.requireNonNull(permissions);
  }

  public TaskResult createIndexing(Actor actor, String documentId, IndexTarget target) {
    if (actor == null || target == null) {
      throw invalid();
    }
    identifier(documentId, 100);
    return store.transaction(
        () -> {
          permissions.require(management.currentRole(actor, documentId), true);
          var revision = indexing.parsedRevision(documentId).orElse(null);
          if (revision == null
              || indexing.jobExists(documentId)
              || indexing.activePublicationExists(documentId)) {
            throw indexConflict();
          }
          String jobId = UUID.randomUUID().toString(), now = Instant.now().toString();
          indexing.insertJob(jobId, documentId, revision, target, actor.principalId(), now);
          audit(
              actor,
              documentId,
              "indexing_queued",
              null,
              values("revision_id", revision.id(), "state", "queued"),
              Set.of("revision_id", "state"));
          var task = authorizedTask(actor, jobId, false);
          return TaskResults.from(
              task,
              permissions.canEdit(task.currentRole()),
              true,
              indexing.publicationId(task.id()));
        });
  }

  public Optional<IndexClaim> claimIndexing(String workspaceId) {
    Actor worker = new Actor(workspaceId, "system:indexing");
    return store.transaction(
        () -> {
          if (indexing.hasProcessing()) {
            return Optional.empty();
          }
          for (String jobId : indexing.queuedIds(workspaceId)) {
            var task = indexing.findInternalTask(jobId).orElseThrow();
            if (!creatorCanWrite(task)) {
              finishFailed(task, "authorization_changed");
              continue;
            }
            if (!indexing.sourceCurrent(task)) {
              finishFailed(task, "indexing_output_invalid");
              continue;
            }
            String token = UUID.randomUUID().toString() + UUID.randomUUID();
            String generation = UUID.randomUUID().toString(), now = Instant.now().toString();
            indexing.insertAttempt(task.id(), task.attempt(), generation, now);
            indexing.markProcessing(
                task.id(), sha256(token.getBytes(StandardCharsets.UTF_8)), generation, now);
            audit(
                worker,
                task.documentId(),
                "indexing_claimed",
                values("state", "queued"),
                values("state", "processing", "attempt", task.attempt()),
                Set.of("state", "attempt"));
            return Optional.of(
                new IndexClaim(
                    task.id(),
                    task.documentId(),
                    task.revisionId(),
                    workspaceId,
                    task.attempt(),
                    token,
                    task.sourceSha256(),
                    task.parserRevision(),
                    task.target(),
                    indexing.segments(task.revisionId()),
                    generation));
          }
          return Optional.empty();
        });
  }

  public boolean isIndexingClaimCurrent(IndexClaim claim) {
    return store.transaction(
        () -> {
          var task = currentClaim(claim);
          return task != null && creatorCanWrite(task);
        });
  }

  public boolean completeIndexing(
      IndexClaim claim, Map<String, String> entryDigests, VerifiedRevision verified) {
    return store.transaction(
        () -> {
          var task = currentClaim(claim);
          if (task == null) {
            return false;
          }
          if (!creatorCanWrite(task)) {
            finishFailed(task, "authorization_changed");
            return false;
          }
          var authoritative = indexing.segments(claim.revisionId());
          var full = new TreeMap<String, String>();
          if (entryDigests == null
              || verified == null
              || entryDigests.size() != authoritative.size()) {
            throw invalidIndexOutput();
          }
          for (var segment : authoritative) {
            String physicalId =
                RetrievalProjection.physicalSegmentId(
                    claim.projectionGenerationId(), segment.segmentId());
            String value = entryDigests.get(physicalId);
            if (value == null || !value.matches("[a-f0-9]{64}")) {
              throw invalidIndexOutput();
            }
            full.put(physicalId, value);
          }
          var manifest =
              new RetrievalProjection.RevisionManifest(
                  claim.workspaceId(), claim.documentId(), claim.projectionGenerationId(), full);
          if (!manifest.sha256().equals(verified.manifestSha256())
              || !claim.target().projectionIdentity().equals(verified.projectionIdentity())
              || verified.segmentCount() != authoritative.size()) {
            throw invalidIndexOutput();
          }
          String publicationId = UUID.randomUUID().toString(), now = Instant.now().toString();
          indexing.insertPublication(
              new IndexPublicationEntity(
                  publicationId,
                  claim.jobId(),
                  claim.documentId(),
                  claim.revisionId(),
                  claim.attempt(),
                  claim.projectionGenerationId(),
                  claim.sourceSha256(),
                  claim.parserRevision(),
                  claim.target(),
                  manifest.sha256(),
                  authoritative.size(),
                  now));
          for (var segment : authoritative) {
            String physicalId =
                RetrievalProjection.physicalSegmentId(
                    claim.projectionGenerationId(), segment.segmentId());
            indexing.insertPublicationEntry(
                publicationId, segment.segmentId(), physicalId, full.get(physicalId));
          }
          indexing.activatePublication(claim.documentId(), publicationId, claim.revisionId());
          audit(
              new Actor(claim.workspaceId(), "system:indexing"),
              claim.documentId(),
              "indexing_published",
              values("state", "processing"),
              values(
                  "state",
                  "indexed",
                  "publication_id",
                  publicationId,
                  "revision_id",
                  claim.revisionId(),
                  "projection_generation_id",
                  claim.projectionGenerationId(),
                  "manifest_sha256",
                  manifest.sha256()),
              Set.of(
                  "state",
                  "publication_id",
                  "revision_id",
                  "projection_generation_id",
                  "manifest_sha256"));
          indexing.markIndexed(claim.jobId(), now);
          return true;
        });
  }

  public boolean failIndexing(IndexClaim claim, String safeCode) {
    if (safeCode == null || !ERRORS.contains(safeCode)) {
      throw invalid();
    }
    return store.transaction(
        () -> {
          var task = currentClaim(claim);
          if (task == null) {
            return false;
          }
          finishFailed(task, creatorCanWrite(task) ? safeCode : "authorization_changed");
          return true;
        });
  }

  public TaskResult indexingStatus(Actor actor, String jobId) {
    return store.transaction(
        () -> {
          var task = authorizedTask(actor, jobId, false);
          return TaskResults.from(
              task,
              permissions.canEdit(task.currentRole()),
              true,
              indexing.publicationId(task.id()));
        });
  }

  public TaskResult cancelIndexing(Actor actor, String jobId) {
    return store.transaction(
        () -> {
          var task = authorizedTask(actor, jobId, true);
          if (!Set.of("queued", "processing").contains(task.state())) {
            throw indexConflict();
          }
          indexing.markCancelled(jobId, Instant.now().toString());
          audit(
              actor,
              task.documentId(),
              "indexing_cancelled",
              values("state", task.state()),
              values("state", "cancelled"),
              Set.of("state"));
          var updated = authorizedTask(actor, jobId, false);
          return TaskResults.from(
              updated,
              permissions.canEdit(updated.currentRole()),
              true,
              indexing.publicationId(updated.id()));
        });
  }

  public TaskResult retryIndexing(Actor actor, String jobId, IndexTarget target) {
    if (target == null) {
      throw invalid();
    }
    return store.transaction(
        () -> {
          var task = authorizedTask(actor, jobId, true);
          if (!Set.of("failed", "cancelled").contains(task.state())) {
            throw indexConflict();
          }
          if (!target.equals(task.target())) {
            throw new ApplicationException(
                FailureKind.CONFLICT, "index_configuration_changed", "索引配置已变化，不能重试该任务。");
          }
          if (task.attempt() >= 3) {
            throw new ApplicationException(
                FailureKind.CONFLICT, "indexing_retry_limit", "该任务已达到三次尝试上限。");
          }
          var internal = indexing.findInternalTask(jobId).orElseThrow();
          if (!creatorCanWrite(internal)) {
            throw new ApplicationException(
                FailureKind.CONFLICT, "authorization_changed", "任务创建者已无当前写权限。");
          }
          if (!indexing.sourceCurrent(internal)) {
            throw indexConflict();
          }
          indexing.markQueued(jobId, task.attempt() + 1, Instant.now().toString());
          audit(
              actor,
              task.documentId(),
              "indexing_retried",
              values("state", task.state(), "attempt", task.attempt()),
              values("state", "queued", "attempt", task.attempt() + 1),
              Set.of("state", "attempt"));
          var updated = authorizedTask(actor, jobId, false);
          return TaskResults.from(
              updated,
              permissions.canEdit(updated.currentRole()),
              true,
              indexing.publicationId(updated.id()));
        });
  }

  /** Called by startup composition before scheduling an indexing worker. */
  public void recoverIndexings() {
    store.transaction(
        () -> {
          for (String id : indexing.processingIds()) {
            finishFailed(indexing.findInternalTask(id).orElseThrow(), "worker_interrupted");
          }
          return null;
        });
  }

  private TaskEntity authorizedTask(Actor actor, String jobId, boolean edit) {
    if (actor == null) {
      throw invalid();
    }
    identifier(jobId, 100);
    var task = indexing.findAuthorizedTask(actor, jobId, edit).orElseThrow(() -> notFound());
    permissions.require(task.currentRole(), edit);
    return task;
  }

  private boolean creatorCanWrite(TaskEntity task) {
    return task != null
        && permissions.canEdit(
            management.currentRole(
                new Actor(task.workspaceId(), task.createdBy()), task.documentId()));
  }

  private TaskEntity currentClaim(IndexClaim claim) {
    if (claim == null || claim.token() == null || claim.token().length() != 72) {
      return null;
    }
    var task = indexing.findInternalTask(claim.jobId()).orElse(null);
    if (task == null
        || !"processing".equals(task.state())
        || !Objects.equals(claim.workspaceId(), task.workspaceId())
        || !Objects.equals(claim.documentId(), task.documentId())
        || !Objects.equals(claim.revisionId(), task.revisionId())
        || !Objects.equals(claim.sourceSha256(), task.sourceSha256())
        || !Objects.equals(claim.parserRevision(), task.parserRevision())
        || !Objects.equals(claim.projectionGenerationId(), task.projectionGenerationId())
        || !claim.target().equals(task.target())
        || claim.attempt() != task.attempt()
        || !MessageDigest.isEqual(
            sha256(claim.token().getBytes(StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.US_ASCII),
            task.claimTokenSha256().getBytes(StandardCharsets.US_ASCII))
        || !indexing.sourceCurrent(task)
        || !indexing.attemptExists(claim.jobId(), claim.attempt(), claim.projectionGenerationId())
        || !claim.segments().equals(indexing.segments(claim.revisionId()))) {
      return null;
    }
    return task;
  }

  private void finishFailed(TaskEntity task, String safeCode) {
    indexing.markFailed(task.id(), safeCode, Instant.now().toString());
    audit(
        new Actor(task.workspaceId(), "system:indexing"),
        task.documentId(),
        "indexing_failed",
        values("state", task.state()),
        values("state", "failed", "error_code", safeCode),
        Set.of("state", "error_code"));
  }

  private static ApplicationException indexConflict() {
    return new ApplicationException(
        FailureKind.CONFLICT, "indexing_state_conflict", "当前资料或任务状态不能执行该索引操作。");
  }

  private static ApplicationException invalidIndexOutput() {
    return new ApplicationException(
        FailureKind.INVALID_INPUT, "indexing_output_invalid", "索引结果未通过完整性校验。");
  }

  private void audit(
      Actor actor, String id, String action, Object before, Object after, Set<String> fields) {
    management.insertAudit(AuditEventEntity.create(actor, id, action, before, after, fields));
  }

  private static Map<String, Object> values(Object... pairs) {
    var result = new LinkedHashMap<String, Object>();
    for (int index = 0; index < pairs.length; index += 2) {
      result.put((String) pairs[index], pairs[index + 1]);
    }
    return result;
  }
}
