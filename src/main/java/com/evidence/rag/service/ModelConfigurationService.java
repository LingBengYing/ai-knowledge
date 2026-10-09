package com.evidence.rag.service;

import com.evidence.rag.client.model.TextModelConnectionProbe;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.exception.ModelConfigurationInputException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.ModelConfigurationState;
import com.evidence.rag.model.domain.TextIndexAnchor;
import com.evidence.rag.model.domain.TextModelRole;
import com.evidence.rag.model.dto.ModelConfigurationResult;
import com.evidence.rag.model.dto.ModelConfigurationTestResult;
import com.evidence.rag.model.dto.SaveModelConfigurationCommand;
import com.evidence.rag.repository.ModelConfigurationRepository;
import com.evidence.rag.repository.ModelRebuildRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.repository.TextModelTargetRepository;
import com.evidence.rag.security.authorization.ModelConfigurationPermissionPolicy;
import java.util.Objects;
import java.util.concurrent.Semaphore;

/** Save, explicitly test, then atomically activate one complete immutable configuration. */
public final class ModelConfigurationService {
  private final SqliteAuthorityStore store;
  private final ModelConfigurationRepository repository;
  private final ModelConfigurationPermissionPolicy permissions;
  private final ManagedTextRuntime runtime;
  private final TextModelConnectionProbe probe;
  private final TextModelTargetRepository targets;
  private final Semaphore tests = new Semaphore(1);

  public ModelConfigurationService(
      SqliteAuthorityStore store,
      ModelConfigurationRepository repository,
      ModelConfigurationPermissionPolicy policy,
      ManagedTextRuntime runtime,
      TextModelConnectionProbe probe) {
    this.store = Objects.requireNonNull(store);
    this.repository = Objects.requireNonNull(repository);
    this.permissions = Objects.requireNonNull(policy);
    this.runtime = Objects.requireNonNull(runtime);
    this.probe = Objects.requireNonNull(probe);
    targets = new TextModelTargetRepository(store);
  }

  public synchronized ModelConfigurationResult get(Actor actor) {
    permissions.requireRead(actor);
    return response(actor, repository.read());
  }

  public synchronized ModelConfigurationResult save(
      Actor actor, SaveModelConfigurationCommand command) {
    permissions.requireEdit(actor);
    synchronized (repository) {
      requireNoRebuild();
      var before = repository.read();
      if (command.baseVersion() != before.version()) {
        throw ModelConfigurationRepository.conflict();
      }
      return response(
          actor, repository.save(command.baseVersion(), command.resolve(before.draft())));
    }
  }

  public ModelConfigurationTestResult test(Actor actor, long version, TextModelRole role) {
    permissions.requireEdit(actor);
    if (role == null) {
      throw new ModelConfigurationInputException("role");
    }
    ModelConfigurationState selected;
    synchronized (this) {
      selected = repository.read();
      requireVersion(selected, version);
    }
    if (!tests.tryAcquire()) {
      throw busy();
    }
    try {
      String error = probe.test(selected.draft(), role);
      return new ModelConfigurationTestResult(
          version, role.wire(), error == null ? "passed" : "failed", error);
    } finally {
      tests.release();
    }
  }

  public synchronized ModelConfigurationResult activate(Actor actor, long version) {
    permissions.requireEdit(actor);
    synchronized (repository) {
      requireNoRebuild();
      var selected = repository.read();
      requireVersion(selected, version);
      try (var maintenance =
          store.operationGate().tryMaintenance().orElseThrow(ModelConfigurationService::busy)) {
        var anchor = activeAnchor(selected);
        var candidate = runtime.prepare(version, selected.draft(), anchor);
        boolean installed = false;
        try {
          store.transaction(
              () -> {
                targets.requireCompatible(actor.workspaceId(), candidate.target());
                return null;
              });
          runtime.install(
              candidate,
              maintenance,
              () -> {
                if (runtime.anchored()) {
                  repository.activate(version, candidate.indexAnchor());
                } else {
                  repository.activate(version);
                }
              });
          installed = true;
        } finally {
          if (!installed) {
            try {
              candidate.close();
            } catch (RuntimeException ignored) {
              /* Preserve the original rejected activation. */
            }
          }
        }
        return response(actor, repository.read());
      }
    }
  }

  private TextIndexAnchor activeAnchor(ModelConfigurationState saved) {
    if (!runtime.anchored()) {
      return null;
    }
    var current = runtime.currentAnchor();
    if (saved.active() == null) {
      if (saved.indexAnchor() != null || runtime.currentVersion() != null) {
        throw unavailable();
      }
      return null;
    }
    if (!Objects.equals(saved.activeVersion(), runtime.currentVersion())
        || current == null
        || (saved.indexAnchor() != null && !saved.indexAnchor().equals(current))) {
      throw unavailable();
    }
    return current;
  }

  private static ApplicationException unavailable() {
    return new ApplicationException(
        FailureKind.UNAVAILABLE, "model_configuration_unavailable", "活动模型配置尚未安全恢复。");
  }

  private void requireNoRebuild() {
    if (store.transaction(() -> new ModelRebuildRepository(store).hasPending())) {
      throw new ApplicationException(
          FailureKind.CONFLICT, "model_rebuild_in_progress", "模型索引正在重建；完成后可继续保存或应用配置。");
    }
  }

  private ModelConfigurationResult response(Actor actor, ModelConfigurationState saved) {
    if (!Objects.equals(saved.activeVersion(), runtime.currentVersion())
        || (saved.indexAnchor() != null && !saved.indexAnchor().equals(runtime.currentAnchor()))) {
      throw unavailable();
    }
    var draft = saved.draft();
    boolean edit = permissions.canEdit(actor);
    String state =
        draft == null
            ? "unconfigured"
            : Objects.equals(saved.activeVersion(), saved.version()) ? "active" : "draft";
    return new ModelConfigurationResult(
        saved.version(),
        saved.activeVersion(),
        state,
        edit,
        draft == null || "siliconflow".equals(draft.generation().provider())
            ? "siliconflow"
            : "mixed",
        draft == null
            ? new ModelConfigurationResult.EmbeddingResult(null, null, null, false)
            : new ModelConfigurationResult.EmbeddingResult(
                draft.embedding().model(),
                draft.embedding().dimensions(),
                draft.embedding().revision(),
                true,
                draft.embedding().provider()),
        new ModelConfigurationResult.RoleResult(
            draft == null ? null : draft.rerank().model(),
            draft != null,
            draft == null ? "siliconflow" : draft.rerank().provider()),
        new ModelConfigurationResult.RoleResult(
            draft == null ? null : draft.generation().model(),
            draft != null,
            draft == null ? "siliconflow" : draft.generation().provider()),
        new ModelConfigurationResult.ProjectionResult(
            probe.projectionConfigured(),
            draft == null ? null : draft.embedding().dimensions(),
            edit && probe.projectionConfigured()));
  }

  private static void requireVersion(ModelConfigurationState state, long version) {
    if (state.draft() == null || version != state.version()) {
      throw ModelConfigurationRepository.conflict();
    }
  }

  private static ApplicationException busy() {
    return new ApplicationException(FailureKind.CONFLICT, "configuration_busy", "当前操作尚未结束，请稍后再试。");
  }
}
