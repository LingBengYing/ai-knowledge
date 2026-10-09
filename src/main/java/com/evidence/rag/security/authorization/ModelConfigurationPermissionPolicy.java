package com.evidence.rag.security.authorization;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.ModelValues;
import java.util.Set;

/** Model configuration belongs to the authenticated workspace, not a privileged role. */
public final class ModelConfigurationPermissionPolicy {
  private final String workspaceId;

  public ModelConfigurationPermissionPolicy(String workspaceId, Set<String> administrators) {
    this.workspaceId = ModelValues.identifier(workspaceId, 200);
    // Retain the constructor parameter for existing configuration compatibility only.
  }

  public boolean canEdit(Actor actor) {
    return actor != null && workspaceId.equals(actor.workspaceId());
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
        FailureKind.FORBIDDEN, "model_configuration_forbidden", "此模型配置操作需要登录当前组织。");
  }
}
