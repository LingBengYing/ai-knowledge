package com.evidence.rag.service;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.ModelConfigurationState;
import com.evidence.rag.model.domain.TextIndexAnchor;
import com.evidence.rag.model.domain.TextModelConfiguration;
import com.evidence.rag.model.dto.ModelRebuildResult;
import com.evidence.rag.model.entity.ModelRebuildEntity;
import com.evidence.rag.model.entity.ModelRebuildItemEntity;
import com.evidence.rag.repository.IndexingRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.ModelConfigurationRepository;
import com.evidence.rag.repository.ModelRebuildRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.repository.TextModelTargetRepository;
import com.evidence.rag.repository.TextRuntimeSelectionRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.security.authorization.ModelConfigurationPermissionPolicy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Rebuild saved parsed materials into a separate target before one whole-library switch. */
public final class ModelRebuildService implements AutoCloseable {
  @FunctionalInterface
  public interface AnchorFactory {
    TextIndexAnchor create(long version, TextModelConfiguration configuration);
  }

  private final SqliteAuthorityStore store;
  private final ModelConfigurationRepository configurations;
  private final DocumentPermissionPolicy permissions;
  private final ModelConfigurationPermissionPolicy operators;
  private final ManagedTextRuntime runtime;
  private final IndexingService indexing;
  private final String workspace;
  private final AnchorFactory anchors;
  private final ModelRebuildRepository batches;
  private final IndexingRepository tasks;
  private final ManagementRepository management;
  private TextRuntimeSnapshot candidate;
  private String candidateBatch;
  private volatile boolean closed;

  public ModelRebuildService(
      SqliteAuthorityStore store,
      ModelConfigurationRepository configurations,
      DocumentPermissionPolicy permissions,
      ManagedTextRuntime runtime,
      IndexingService indexing,
      String workspaceId,
      Set<String> administrators,
      AnchorFactory anchors) {
    this.store = Objects.requireNonNull(store);
    this.configurations = Objects.requireNonNull(configurations);
    this.permissions = Objects.requireNonNull(permissions);
    this.runtime = Objects.requireNonNull(runtime);
    this.indexing = Objects.requireNonNull(indexing);
    this.workspace = Objects.requireNonNull(workspaceId);
    this.anchors = Objects.requireNonNull(anchors);
    operators = new ModelConfigurationPermissionPolicy(workspaceId, administrators);
    batches = new ModelRebuildRepository(store);
    tasks = new IndexingRepository(store);
    management = new ManagementRepository(store);
    store.transaction(
        () -> {
          batches.recoverInterrupted(Instant.now().toString());
          return null;
        });
  }

  /** Content mutations share the same global pending-rebuild gate as repository writes. */
  public boolean mutationsBlocked() {
    return store.transaction(batches::hasPending);
  }

  public synchronized ModelRebuildResult get(Actor actor) {
    operators.requireRead(actor);
    var saved = configurations.read();
    String configurationReason = configurationReason(saved);
    return store.transaction(() -> response(actor, saved, configurationReason));
  }

  public synchronized ModelRebuildResult start(Actor actor, long version) {
    synchronized (configurations) {
      return startLocked(actor, version);
    }
  }

  private ModelRebuildResult startLocked(Actor actor, long version) {
    operators.requireEdit(actor);
    try (var maintenance =
        store.operationGate().tryMaintenance().orElseThrow(() -> conflict("tasks_pending"))) {
      var saved = configurations.read();
      if (saved.version() != version || saved.draft() == null) {
        throw ModelConfigurationRepository.conflict();
      }
      String reason = configurationReason(saved);
      if (!"rebuild_required".equals(reason)) {
        throw conflict(reason == null ? "no_rebuild_required" : reason);
      }
      // Only construction and private sealing occur here; all provider work belongs to the job.
      var anchor = anchors.create(version, saved.draft());
      try (var preview = runtime.prepare(version, saved.draft(), anchor)) {
        if (!preview.target().equals(anchor.target())) {
          throw conflict("model_configuration_unavailable");
        }
      }
      var sealed = configurations.seal(version, saved.draft(), anchor);
      store.transaction(
          () -> {
            if (batches.hasPending(workspace)) {
              throw conflict("rebuild_in_progress");
            }
            if (!batches.canStart(workspace)) {
              throw conflict("tasks_pending");
            }
            var selected = new TextRuntimeSelectionRepository(store).read();
            if (!Objects.equals(selected.activeVersion(), saved.activeVersion())
                || !Objects.equals(runtime.currentVersion(), saved.activeVersion())) {
              throw ModelConfigurationRepository.conflict();
            }
            var materials = batches.snapshot(workspace);
            requireAllWritable(actor, materials);
            String batchId = UUID.randomUUID().toString();
            String now = Instant.now().toString();
            var items = new ArrayList<ModelRebuildItemEntity>();
            for (var material : materials) {
              String jobId = UUID.randomUUID().toString();
              if (material.basePublicationId() != null
                  && !indexing.supportsModelRebuildPlanInTransaction(
                      tasks.vectorPlanForBase(jobId, workspace, material.basePublicationId()))) {
                throw conflict("projection_configuration_required");
              }
              items.add(
                  new ModelRebuildItemEntity(
                      batchId,
                      material.ordinal(),
                      material.documentId(),
                      material.revisionId(),
                      material.sourceSha256(),
                      material.parserRevision(),
                      material.basePublicationId(),
                      material.baseVectorSetSha256(),
                      jobId,
                      null,
                      "queued"));
            }
            batches.insert(
                new ModelRebuildEntity(
                    batchId,
                    workspace,
                    actor.principalId(),
                    selected,
                    version,
                    sealed.configurationSha256(),
                    sealed.anchorSha256(),
                    anchor.target(),
                    "queued",
                    items.size(),
                    0,
                    null,
                    now,
                    now),
                items);
            for (var item : items) {
              var revision = tasks.parsedRevision(item.documentId()).orElseThrow();
              tasks.insertModelRebuildJob(
                  item.jobId(),
                  item.documentId(),
                  revision,
                  anchor.target(),
                  actor.principalId(),
                  item.basePublicationId(),
                  tasks.nextRebuildSequence(item.documentId()),
                  now,
                  batchId);
            }
            return null;
          });
      return get(actor);
    }
  }

  /**
   * One actual indexing body per tick; the active bundle remains installed until all are sealed.
   */
  public boolean processNext() {
    if (closed) {
      return false;
    }
    var batch = store.transaction(() -> batches.current(workspace).orElse(null));
    if (batch == null || !Set.of("queued", "running", "applying").contains(batch.state())) {
      discardCandidate();
      return false;
    }
    try {
      if (candidate == null || !batch.id().equals(candidateBatch)) {
        discardCandidate();
        var sealed =
            configurations.sealed(
                batch.targetVersion(), batch.configurationSha256(), batch.anchorSha256());
        candidate = runtime.prepare(sealed.version(), sealed.configuration(), sealed.anchor());
        if (!candidate.target().equals(batch.target())) {
          throw conflict("model_configuration_unavailable");
        }
        candidateBatch = batch.id();
      }
      var admission = store.operationGate().tryOperation();
      if (admission.isEmpty()) {
        return false;
      }
      try (var operation = admission.orElseThrow()) {
        store.transaction(
            () -> {
              requireAllWritable(
                  new Actor(workspace, batch.createdBy()), batches.items(batch.id()));
              batches.markRunning(batch.id(), Instant.now().toString());
              return null;
            });
        var claim = candidate.indexing().claimModelRebuild(batch.id());
        if (claim.isPresent()) {
          candidate.indexing().process(claim.orElseThrow());
        }
        var states =
            store.transaction(
                () -> {
                  var items = batches.items(batch.id());
                  for (var item : items) {
                    var task = tasks.findInternalTask(item.jobId()).orElseThrow();
                    if (!Set.of("queued", "prepared").contains(task.state())) {
                      throw conflict("indexing_failed");
                    }
                  }
                  return items;
                });
        if (states.stream().anyMatch(item -> !"prepared".equals(item.state()))) {
          return true;
        }
      }
      var maintenance = store.operationGate().tryMaintenance();
      if (maintenance.isEmpty()) {
        return false;
      }
      try (var lease = maintenance.orElseThrow()) {
        if (!candidate.identityCurrent()) {
          throw conflict("model_configuration_unavailable");
        }
        store.transaction(
            () -> {
              requireAllWritable(
                  new Actor(workspace, batch.createdBy()), batches.items(batch.id()));
              batches.publishAll(batch.id(), lease, Instant.now().toString());
              return null;
            });
        runtime.install(candidate, lease, () -> {});
        candidate = null;
        candidateBatch = null;
      }
      return true;
    } catch (RuntimeException failure) {
      String code =
          Thread.currentThread().isInterrupted()
              ? "worker_interrupted"
              : failure instanceof ApplicationException problem
                      && "authorization_changed".equals(problem.code())
                  ? "authorization_changed"
                  : "model_rebuild_failed";
      store.transaction(
          () -> {
            batches.fail(batch.id(), code, Instant.now().toString());
            return null;
          });
      discardCandidate();
      return true;
    }
  }

  private ModelRebuildResult response(
      Actor actor, ModelConfigurationState saved, String configurationReason) {
    var batch = batches.current(workspace).orElse(null);
    List<ModelRebuildItemEntity> materials;
    try {
      materials = batches.snapshot(workspace);
    } catch (RuntimeException unavailable) {
      return result(
          saved,
          batch,
          "rebuild_required".equals(configurationReason),
          false,
          "source_unavailable",
          0);
    }
    boolean required = "rebuild_required".equals(configurationReason);
    String reason = configurationReason;
    if (batches.hasPending(workspace)) {
      reason = "rebuild_in_progress";
    } else if (!operators.canEdit(actor) || !allWritable(actor, materials)) {
      reason = "authorization_changed";
    } else if (required && !batches.canStart(workspace)) {
      reason = "tasks_pending";
    } else if (required) {
      for (var item : materials) {
        if (item.basePublicationId() != null
            && !indexing.supportsModelRebuildPlanInTransaction(
                tasks.vectorPlanForBase("eligibility", workspace, item.basePublicationId()))) {
          reason = "projection_configuration_required";
          break;
        }
      }
    }
    boolean canStart = required && "rebuild_required".equals(reason);
    return result(saved, batch, required, canStart, canStart ? null : reason, materials.size());
  }

  private String configurationReason(ModelConfigurationState saved) {
    if (saved.draft() == null) {
      return "configuration_required";
    }
    try (var compatible =
        runtime.prepare(saved.version(), saved.draft(), runtime.currentAnchor())) {
      store.transaction(
          () -> {
            new TextModelTargetRepository(store).requireCompatible(workspace, compatible.target());
            return null;
          });
      return "no_rebuild_required";
    } catch (ApplicationException problem) {
      return "model_rebuild_required".equals(problem.code())
          ? "rebuild_required"
          : "projection_configuration_required".equals(problem.code())
              ? "projection_configuration_required"
              : "model_configuration_unavailable";
    } catch (RuntimeException invalid) {
      return "model_configuration_unavailable";
    }
  }

  private static ModelRebuildResult result(
      ModelConfigurationState saved,
      ModelRebuildEntity batch,
      boolean required,
      boolean canStart,
      String reason,
      int count) {
    var job =
        batch == null
            ? null
            : new ModelRebuildResult.Job(
                batch.id(),
                batch.baseSelection().activeVersion(),
                batch.targetVersion(),
                batch.state(),
                batch.totalDocuments(),
                batch.completedDocuments(),
                batch.errorCode(),
                batch.createdAt(),
                batch.updatedAt());
    return new ModelRebuildResult(
        saved.version(), saved.activeVersion(), required, canStart, reason, count, job);
  }

  private boolean allWritable(Actor actor, List<ModelRebuildItemEntity> items) {
    return items.stream()
        .allMatch(item -> permissions.canEdit(management.currentRole(actor, item.documentId())));
  }

  private void requireAllWritable(Actor actor, List<ModelRebuildItemEntity> items) {
    if (!operators.canEdit(actor) || !allWritable(actor, items)) {
      throw conflict("authorization_changed");
    }
  }

  private static ApplicationException conflict(String code) {
    return new ApplicationException(FailureKind.CONFLICT, code, "暂不能切换索引配置；当前已应用配置和资料索引继续保留。");
  }

  private void discardCandidate() {
    if (candidate != null) {
      candidate.close();
      candidate = null;
      candidateBatch = null;
    }
  }

  @Override
  public synchronized void close() {
    closed = true;
    discardCandidate();
  }
}
