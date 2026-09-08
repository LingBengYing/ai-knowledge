package com.evidence.rag.security.authorization;

import static com.evidence.rag.model.domain.ModelValues.notFound;

/**
 * Pure policy over the role read in the current authority transaction. Organization and principal
 * scope are applied by Repository SQL before this snapshot can be supplied.
 */
public final class DocumentPermissionPolicy {
  public boolean canRead(String currentRole) {
    return "owner".equals(currentRole)
        || "editor".equals(currentRole)
        || "reader".equals(currentRole);
  }

  public boolean canEdit(String currentRole) {
    return "owner".equals(currentRole) || "editor".equals(currentRole);
  }

  public void require(String currentRole, boolean edit) {
    if (!(edit ? canEdit(currentRole) : canRead(currentRole))) {
      throw notFound();
    }
  }
}
