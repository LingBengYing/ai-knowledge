package com.evidence.rag.security.authorization;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModelConfigurationPermissionPolicyTest {
  @Test
  void readerCanInspectSafeStateButDocumentOwnerAndForeignWorkspaceCannotAdminister() {
    var policy = new ModelConfigurationPermissionPolicy("org", Set.of("operator"));
    assertDoesNotThrow(() -> policy.requireRead(new Actor("org", "reader")));
    assertFalse(policy.canEdit(new Actor("org", "owner")));
    assertFalse(policy.canEdit(new Actor("org", "editor")));
    assertThrows(ApplicationException.class, () -> policy.requireEdit(new Actor("org", "owner")));
    assertThrows(
        ApplicationException.class, () -> policy.requireRead(new Actor("other", "operator")));
    assertTrue(policy.canEdit(new Actor("org", "operator")));
    assertFalse(policy.canEdit(new Actor("org", "Operator")));
    assertThrows(
        ApplicationException.class, () -> new ModelConfigurationPermissionPolicy("org", Set.of()));
  }
}
