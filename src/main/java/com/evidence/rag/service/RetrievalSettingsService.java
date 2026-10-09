package com.evidence.rag.service;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.RetrievalSettings;
import com.evidence.rag.repository.RetrievalSettingsRepository;
import java.util.Objects;

/** Organization members share settings; snapshots are immutable and never call a provider. */
public final class RetrievalSettingsService {
  private final RetrievalSettingsRepository repository;
  private final String workspaceId;

  public RetrievalSettingsService(RetrievalSettingsRepository repository, String workspaceId) {
    this.repository = Objects.requireNonNull(repository);
    this.workspaceId = ModelValues.identifier(workspaceId, 200);
  }

  public RetrievalSettings read(Actor actor) {
    requireMember(actor);
    return snapshot();
  }

  public RetrievalSettings save(Actor actor, RetrievalSettings settings) {
    requireMember(actor);
    return repository.save(settings);
  }

  public RetrievalSettings snapshot() {
    return repository.read();
  }

  private void requireMember(Actor actor) {
    if (actor == null) {
      throw new ApplicationException(
          FailureKind.UNAUTHENTICATED, "authentication_required", "需要有效的组织身份。");
    }
    if (!workspaceId.equals(actor.workspaceId())) {
      throw new ApplicationException(
          FailureKind.FORBIDDEN, "retrieval_settings_forbidden", "不能访问其他组织的检索设置。");
    }
  }
}
