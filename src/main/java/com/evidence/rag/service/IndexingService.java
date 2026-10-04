package com.evidence.rag.service;

import static com.evidence.rag.model.domain.ModelValues.identifier;
import static com.evidence.rag.model.domain.ModelValues.invalid;
import static com.evidence.rag.model.domain.ModelValues.notFound;
import static com.evidence.rag.model.domain.ModelValues.sha256;

import com.evidence.rag.client.vector.MilvusProjectionCleanup;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.IndexClaim;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ProjectionAttempt;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.ReindexVectorPlan;
import com.evidence.rag.model.domain.VerifiedReindexVectors;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.model.dto.TaskResult;
import com.evidence.rag.model.entity.AuditEventEntity;
import com.evidence.rag.model.entity.IndexPublicationEntity;
import com.evidence.rag.model.entity.TaskEntity;
import com.evidence.rag.repository.DocumentCleanupRepository;
import com.evidence.rag.repository.DocumentUpdateRepository;
import com.evidence.rag.repository.IndexingRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.ModelRebuildRepository;
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
import java.util.function.BiPredicate;
import java.util.function.Supplier;

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
  private final BiPredicate<String, IndexTarget> receiptTargets;

  public IndexingService(
      SqliteAuthorityStore store,
      IndexingRepository indexing,
      ManagementRepository management,
      DocumentPermissionPolicy permissions) {
    this(store, indexing, management, permissions, null);
  }

  public IndexingService(
      SqliteAuthorityStore store,
      IndexingRepository indexing,
      ManagementRepository management,
      DocumentPermissionPolicy permissions,
      BiPredicate<String, IndexTarget> receiptTargets) {
    this.receiptTargets = receiptTargets;
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

  /** Explicitly rebuild the current saved source without displacing its active publication. */
  public TaskResult createReindexing(
      Actor actor, String documentId, String basePublicationId, IndexTarget target) {
    if (actor == null || target == null) {
      throw invalid();
    }
    identifier(documentId, 100);
    identifier(basePublicationId, 100);
    return store.transaction(
        () -> {
          permissions.require(management.currentRole(actor, documentId), true);
          var base = indexing.activePublication(documentId).orElse(null);
          var revision = indexing.parsedRevision(documentId).orElse(null);
          if (base == null
              || !base.id().equals(basePublicationId)
              || revision == null
              || !(receiptTargets == null
                  ? indexing.canReindex(documentId)
                  : indexing.canReindexWithVectors(documentId))) {
            throw indexConflict();
          }
          if (!base.target().equals(target)) {
            throw new ApplicationException(
                FailureKind.CONFLICT, "index_configuration_changed", "当前索引配置与已发布索引不一致。");
          }
          String jobId = UUID.randomUUID().toString(), now = Instant.now().toString();
          if (receiptTargets == null) {
            indexing.insertRebuildJob(
                jobId,
                documentId,
                revision,
                target,
                actor.principalId(),
                basePublicationId,
                indexing.nextRebuildSequence(documentId),
                now);
          } else {
            var plan = indexing.vectorPlanForBase(jobId, actor.workspaceId(), basePublicationId);
            if (!configured(plan)) {
              throw configurationChanged();
            }
            indexing.insertRebuildJob(
                jobId,
                documentId,
                revision,
                target,
                actor.principalId(),
                basePublicationId,
                indexing.nextRebuildSequence(documentId),
                now,
                plan.setSha256());
          }
          var internal = indexing.findInternalTask(jobId).orElseThrow();
          if (!indexing.sourceCurrent(internal)) {
            throw indexConflict();
          }
          audit(
              actor,
              documentId,
              "indexing_rebuild_queued",
              values("publication_id", basePublicationId),
              values("revision_id", revision.id(), "state", "queued"),
              Set.of("publication_id", "revision_id", "state"));
          var task = authorizedTask(actor, jobId, false);
          return TaskResults.from(
              task,
              permissions.canEdit(task.currentRole()),
              true,
              indexing.publicationId(task.id()));
        });
  }

  /** Index a parsed candidate without changing the active original or publication. */
  public TaskResult createReplacementIndexing(
      Actor actor,
      String documentId,
      String candidateRevisionId,
      String baseRevisionId,
      IndexTarget target) {
    if (actor == null || target == null) {
      throw invalid();
    }
    identifier(documentId, 100);
    identifier(candidateRevisionId, 100);
    identifier(baseRevisionId, 100);
    return store.transaction(
        () -> {
          permissions.require(management.currentRole(actor, documentId), true);
          var updates = new DocumentUpdateRepository(store);
          var replacement =
              updates.current(documentId).orElseThrow(IndexingService::indexConflict);
          if (!"corpus".equals(replacement.pipeline())
              || !"parsed".equals(replacement.state())
              || !candidateRevisionId.equals(replacement.candidateRevisionId())
              || !baseRevisionId.equals(replacement.baseRevisionId())
              || replacement.indexJobId() != null
              || !updates.sourceCurrent(replacement.id())
              || indexing.hasPending(documentId)) {
            throw indexConflict();
          }
          var revision =
              indexing.parsedRevision(documentId, candidateRevisionId)
                  .orElseThrow(IndexingService::indexConflict);
          var base = indexing.activePublication(documentId).orElse(null);
          if (!Objects.equals(replacement.basePublicationId(), base == null ? null : base.id())) {
            throw indexConflict();
          }
          if (base != null && !base.target().equals(target)) {
            throw new ApplicationException(
                FailureKind.CONFLICT,
                "index_configuration_changed",
                "当前索引配置与已发布索引不一致。");
          }
          String jobId = UUID.randomUUID().toString();
          String now = Instant.now().toString();
          indexing.insertReplacementJob(
              jobId,
              documentId,
              revision,
              target,
              actor.principalId(),
              replacement.basePublicationId(),
              indexing.nextRebuildSequence(documentId),
              now,
              replacement.id());
          var task = authorizedTask(actor, jobId, false);
          audit(
              actor,
              documentId,
              "replacement_indexing_queued",
              null,
              values("revision_id", candidateRevisionId, "state", "queued"),
              Set.of("revision_id", "state"));
          return TaskResults.from(task, true, true, indexing.publicationId(jobId));
        });
  }

  /** Full saved-source and configured-receipt eligibility; no provider calls. */
  public boolean canReindexWithVectors(Actor actor, String documentId, IndexTarget target) {
    return store.transaction(() -> canReindexWithVectorsInTransaction(actor, documentId, target));
  }

  /** The caller owns the existing authority transaction; safe for management row qualification. */
  public boolean canReindexWithVectorsInTransaction(
      Actor actor, String documentId, IndexTarget target) {
    if (actor == null || target == null || receiptTargets == null) {
      return false;
    }
    identifier(documentId, 100);
    if (!permissions.canEdit(management.currentRole(actor, documentId))
        || !indexing.canReindexWithVectors(documentId)) {
      return false;
    }
    var base = indexing.activePublication(documentId).orElse(null);
    return base != null
        && base.target().equals(target)
        && configured(indexing.vectorPlanForBase("eligibility", actor.workspaceId(), base.id()));
  }

  /** A legacy or initial claim has no continuation snapshot, including after restart. */
  public Optional<ReindexVectorPlan> reindexVectorPlan(IndexClaim claim) {
    return store.transaction(
        () -> {
          var task = currentClaim(claim);
          if (task == null || !creatorCanWrite(task)) {
            throw indexConflict();
          }
          if (indexing.modelRebuildIdForJob(task.id()).isPresent()) {
            var plan = new ModelRebuildRepository(store).plan(task.id());
            if (plan.isPresent() && !plan.orElseThrow().isEmpty()
                && !configured(plan.orElseThrow())) {
              throw configurationChanged();
            }
            return plan.filter(value -> !value.isEmpty());
          }
          if (indexing.baseVectorSetSha256(task.id()).isEmpty()) {
            return Optional.empty();
          }
          var plan = indexing.freezeVectorPlan(task.id());
          if (!configured(plan)) {
            throw configurationChanged();
          }
          return Optional.of(plan);
        });
  }

  public Optional<IndexClaim> claimIndexing(String workspaceId) {
    return claim(workspaceId, null);
  }

  public Optional<IndexClaim> claimModelRebuild(String workspaceId, String batchId) {
    identifier(batchId, 100);
    return claim(workspaceId, batchId);
  }

  private Optional<IndexClaim> claim(String workspaceId, String batchId) {
    Actor worker = new Actor(workspaceId, "system:indexing");
    return store.transaction(
        () -> {
          if (indexing.hasProcessing()) {
            return Optional.empty();
          }
          var queued = batchId == null ? indexing.queuedIds(workspaceId)
              : indexing.queuedModelRebuildIds(workspaceId, batchId);
          for (String jobId : queued) {
            var task = indexing.findInternalTask(jobId).orElseThrow();
            if (!creatorCanWrite(task)) {
              finishFailed(task, "authorization_changed");
              continue;
            }
            if (!indexing.sourceCurrent(task)) {
              finishFailed(task, "indexing_output_invalid");
              continue;
            }
            if (!configuredTask(task)) {
              finishFailed(task, "index_configuration_changed");
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
                    indexing.projectionItems(task.revisionId()),
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
    return completeIndexing(claim, entryDigests, verified, null);
  }

  public boolean completeIndexing(
      IndexClaim claim,
      Map<String, String> entryDigests,
      VerifiedRevision verified,
      VerifiedReindexVectors vectors) {
    return store.transaction(
        () -> {
          var task = claimedTask(claim);
          if (task == null) {
            return false;
          }
          if (!creatorCanWrite(task)) {
            finishFailed(task, "authorization_changed");
            return false;
          }
          if (!indexing.sourceCurrent(task)) {
            finishFailed(task, "indexing_output_invalid");
            return false;
          }
          ReindexVectorPlan plan = null;
          boolean modelRebuild = indexing.modelRebuildIdForJob(task.id()).isPresent();
          if (modelRebuild) {
            plan = new ModelRebuildRepository(store).plan(task.id()).orElse(null);
            if (plan != null && !plan.isEmpty()) {
              if (!configured(plan)) {
                finishFailed(task, "index_configuration_changed");
                return false;
              }
              if (vectors == null || !vectors.plan().equals(plan)) {
                throw invalidIndexOutput();
              }
              new VerifiedReindexVectors(plan, vectors.receipts());
            } else if (vectors != null) {
              throw invalidIndexOutput();
            }
          } else if (indexing.baseVectorSetSha256(task.id()).isPresent()) {
            plan = indexing.freezeVectorPlan(task.id());
            if (!configured(plan)) {
              finishFailed(task, "index_configuration_changed");
              return false;
            }
            if (vectors == null || !vectors.plan().equals(plan)) {
              throw invalidIndexOutput();
            }
            // Reconstruct to enforce the full exact receipt set, not a caller-supplied subset.
            new VerifiedReindexVectors(plan, vectors.receipts());
          } else if (vectors != null) {
            throw invalidIndexOutput();
          }
          var authoritative = indexing.projectionItems(claim.revisionId());
          var full = new TreeMap<String, String>();
          if (entryDigests == null
              || verified == null
              || entryDigests.size() != authoritative.size()) {
            throw invalidIndexOutput();
          }
          for (var segment : authoritative) {
            String physicalId =
                RetrievalProjection.physicalSegmentId(
                    claim.projectionGenerationId(), segment.evidenceId());
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
          final ReindexVectorPlan verifiedPlan = plan;
          return persistCandidate(claim, () -> {
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
                    claim.projectionGenerationId(), segment.evidenceId());
            indexing.insertPublicationEntry(
                publicationId, segment.evidenceId(), physicalId, full.get(physicalId));
          }
          if (verifiedPlan != null) {
            var newPublication = new PublicationVersion(
                    claim.documentId(),
                    publicationId,
                    claim.revisionId(),
                    claim.projectionGenerationId(),
                    claim.sourceSha256(),
                    claim.parserRevision(),
                    claim.target(),
                    manifest.sha256(),
                    authoritative.size());
            if (modelRebuild) {
              indexing.insertModelRebuildBindings(claim.jobId(), newPublication, verifiedPlan);
            } else {
              indexing.insertInheritedBindings(newPublication, verifiedPlan);
            }
          }
          if (modelRebuild) {
            indexing.sealModelRebuildPublication(claim.jobId(), publicationId, now);
            return true;
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
        });
  }

  private boolean persistCandidate(IndexClaim claim, Supplier<Boolean> persist) {
    var updates = new DocumentUpdateRepository(store);
    var replacementId = updates.replacementIdForIndexJob(claim.jobId());
    return replacementId.isEmpty()
        ? persist.get()
        : updates.withCandidateSource(replacementId.orElseThrow(), persist);
  }

  /** Persist the exact remote target and generation before the first possible worker write. */
  public boolean registerProjectionWrite(
      IndexClaim claim, MilvusRestProjection.Settings projection) {
    if (projection == null
        || claim == null
        || !projection.identity().equals(claim.target().projectionIdentity())
        || !projection.workspaceId().equals(claim.workspaceId())
        || !projection.embeddingIdentity().equals(claim.target().embeddingIdentity())
        || projection.dimension() != claim.target().dimensions()) {
      throw invalid();
    }
    return store.transaction(
        () -> {
          var task = currentClaim(claim);
          if (task == null || !creatorCanWrite(task)) {
            return false;
          }
          return persistCandidate(claim, () -> {
          var registry = new DocumentCleanupRepository(store);
          registry.registerProjectionAttempt(
              new ProjectionAttempt(
                  claim.documentId(),
                  claim.workspaceId(),
                  claim.revisionId(),
                  claim.sourceSha256(),
                  claim.projectionGenerationId(),
                  "legacy",
                  MilvusProjectionCleanup.qualified(projection),
                  false));
          registry.markProjectionWriteIssued(claim.projectionGenerationId(), "legacy");
          return true;
          });
        });
  }

  public boolean failIndexing(IndexClaim claim, String safeCode) {
    if (safeCode == null || !ERRORS.contains(safeCode)) {
      throw invalid();
    }
    return store.transaction(
        () -> {
          var task = claimedTask(claim);
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
          if (indexing.hasPending(task.documentId()) || !indexing.sourceCurrent(internal)) {
            throw indexConflict();
          }
          if (!configuredTask(internal)) {
            throw configurationChanged();
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
    var task = claimedTask(claim);
    return task != null && indexing.sourceCurrent(task) && configuredTask(task) ? task : null;
  }

  /** Exact current worker identity; source eligibility is required separately for publication. */
  private TaskEntity claimedTask(IndexClaim claim) {
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
        || !indexing.attemptExists(claim.jobId(), claim.attempt(), claim.projectionGenerationId())
        || !claim.items().equals(indexing.projectionItems(claim.revisionId()))) {
      return null;
    }
    return task;
  }

  private boolean configuredTask(TaskEntity task) {
    if (indexing.modelRebuildIdForJob(task.id()).isPresent()) {
      var plan = new ModelRebuildRepository(store).plan(task.id());
      return plan.isEmpty() || plan.orElseThrow().isEmpty() || configured(plan.orElseThrow());
    }
    return indexing.baseVectorSetSha256(task.id()).isEmpty()
        || configured(indexing.freezeVectorPlan(task.id()));
  }

  public boolean supportsModelRebuildPlanInTransaction(ReindexVectorPlan plan) {
    return plan == null || plan.isEmpty() || configured(plan);
  }

  private boolean configured(ReindexVectorPlan plan) {
    return receiptTargets != null
        && plan.images().stream()
            .allMatch(value -> receiptTargets.test("image", value.origin().target()))
        && plan.audios().stream()
            .allMatch(value -> receiptTargets.test("audio", value.origin().target()));
  }

  private static ApplicationException configurationChanged() {
    return new ApplicationException(
        FailureKind.CONFLICT, "index_configuration_changed", "当前索引配置不能完整验证已保存的图片或音频向量。");
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
