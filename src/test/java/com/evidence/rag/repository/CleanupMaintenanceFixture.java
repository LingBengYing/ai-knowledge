package com.evidence.rag.repository;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.worker.cleanup.CleanupRestoreJournal;
import java.io.IOException;
import java.time.Instant;

/** Uses the real claim, sealed plan, fsynced intent and owner-thread maintenance capability. */
public final class CleanupMaintenanceFixture {
  private CleanupMaintenanceFixture() {}

  public static void purge(SqliteAuthorityStore store, Actor actor, String documentId)
      throws IOException {
    var repository = new DocumentCleanupRepository(store);
    try (var maintenance = store.operationGate().tryMaintenance().orElseThrow()) {
      var claim =
          store.transaction(
              () -> {
                String now = Instant.now().toString();
                repository.create(actor, documentId, now);
                return repository.claimNext(now).orElseThrow();
              });
      var plan = store.transaction(() -> repository.sealPlan(claim));
      CleanupRestoreJournal.intent(store.libraryPath().getParent(), store.libraryIdentity(), plan);
      store.purge(claim, plan, maintenance);
    }
  }
}
