package com.evidence.rag.support;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.entity.DocumentRemovalEntity;
import com.evidence.rag.repository.DocumentLifecycleRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import java.time.Instant;

/** Test-only tombstone, as written by the cleanup flow or retained from an older withdrawal. */
public final class DocumentWithdrawal {
  private DocumentWithdrawal() {}

  public static void withdraw(SqliteAuthorityStore store, Actor actor, String documentId) {
    store.transaction(
        () -> {
          var lifecycle = new DocumentLifecycleRepository(store);
          var document = lifecycle.findWritableDocument(actor, documentId).orElseThrow();
          new DocumentPermissionPolicy().require(document.currentRole(), true);
          if (lifecycle.findRemoval(actor, documentId).isEmpty()) {
            lifecycle.insertRemoval(
                new DocumentRemovalEntity(
                    documentId,
                    actor.workspaceId(),
                    actor.principalId(),
                    Instant.now().toString()));
          }
          return null;
        });
  }
}
