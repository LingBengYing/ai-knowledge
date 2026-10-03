package com.evidence.rag.security.authorization;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.ModelValues;
import java.util.Set;

/** Server-owned operator policy, deliberately unrelated to document ACL roles. */
public final class ModelConfigurationPermissionPolicy {
  private final String workspaceId;
  private final Set<String> administrators;

  public ModelConfigurationPermissionPolicy(String workspaceId, Set<String> administrators) {
    this.workspaceId = ModelValues.identifier(workspaceId, 200);
    this.administrators = Set.copyOf(administrators);
    if (this.administrators.isEmpty()) {
      throw ModelValues.invalid();
    }
    for (String administrator : this.administrators) {
      new Actor(workspaceId, administrator);
    }
  }

  public boolean canEdit(Actor actor) {
    return actor != null
        && workspaceId.equals(actor.workspaceId())
        && administrators.contains(actor.principalId());
  }

  public void requireRead(Actor actor) {
    if (actor == null || !workspaceId.equals(actor.workspaceId())) {
      throw denied();
    }
  }

  public void requireEdit(Actor actor) {
    if (!canEdit(actor)) {
      throw denied();
    }
  }

  private static ApplicationException denied() {
    return new ApplicationException(
        FailureKind.FORBIDDEN, "model_configuration_forbidden", "此模型配置操作需要授权管理员。");
  }
}
