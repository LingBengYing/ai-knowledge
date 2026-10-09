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
  void everyWorkspaceMemberCanConfigureButForeignAndMissingActorsCannot() {
    var policy = new ModelConfigurationPermissionPolicy("org", Set.of("operator"));
    assertDoesNotThrow(() -> policy.requireRead(new Actor("org", "reader")));
    assertTrue(policy.canEdit(new Actor("org", "owner")));
    assertTrue(policy.canEdit(new Actor("org", "editor")));
    assertDoesNotThrow(() -> policy.requireEdit(new Actor("org", "owner")));
    assertThrows(
        ApplicationException.class, () -> policy.requireRead(new Actor("other", "operator")));
    assertTrue(policy.canEdit(new Actor("org", "operator")));
    assertTrue(policy.canEdit(new Actor("org", "Operator")));
    assertFalse(policy.canEdit(null));
    assertThrows(
        ApplicationException.class, () -> policy.requireEdit(new Actor("other", "operator")));
    assertDoesNotThrow(() -> new ModelConfigurationPermissionPolicy("org", Set.of()));
  }
}
