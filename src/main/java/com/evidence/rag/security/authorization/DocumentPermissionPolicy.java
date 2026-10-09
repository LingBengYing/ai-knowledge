package com.evidence.rag.security.authorization;

import static com.evidence.rag.model.domain.ModelValues.notFound;

/**
 * Shared-workspace policy over a document snapshot. Repositories establish organization and current
 * lifecycle membership before supplying the member marker; legacy roles are no longer restrictions.
 */
public final class DocumentPermissionPolicy {
  public boolean canRead(String currentRole) {
    return "member".equals(currentRole)
        || "owner".equals(currentRole)
        || "editor".equals(currentRole)
        || "reader".equals(currentRole);
  }

  public boolean canEdit(String currentRole) {
    return canRead(currentRole);
  }

  public void require(String currentRole, boolean edit) {
    if (!(edit ? canEdit(currentRole) : canRead(currentRole))) {
      throw notFound();
    }
  }
}
