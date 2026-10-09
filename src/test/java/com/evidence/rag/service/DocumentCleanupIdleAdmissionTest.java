package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.repository.DocumentCleanupRepository;
import com.evidence.rag.repository.DocumentLifecycleRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.tool.parser.TextParser;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentCleanupIdleAdmissionTest {
  @TempDir Path directory;

  @Test
  void idleTickWaitingForAuthorityDoesNotRejectOrdinaryOperations() throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      var store = authority.store();
      var service = service(store);
      var tick = new FutureTask<>(service::runOnce);
      var worker = new Thread(tick, "cleanup-idle-admission-test");
      boolean admitted;
      try {
        admitted =
            store.transaction(
                () -> {
                  worker.start();
                  awaitAuthorityMonitor(worker, store);
                  var operation = store.operationGate().tryOperation();
                  try (var lease = operation.orElse(null)) {
                    return operation.isPresent();
                  }
                });
      } finally {
        worker.join(Duration.ofSeconds(5));
      }
      assertTrue(admitted, "An empty cleanup tick must not hold exclusive maintenance admission");
      assertFalse(tick.get(5, TimeUnit.SECONDS));
      assertTrue(store.operationGate().isIdle());
    }
  }

  @Test
  void pendingCleanupCannotBeClaimedUntilTheOperationGateIsIdle() {
    var owner = new Actor("org-main", "owner");
    try (var authority = new AuthorityTestContext(directory)) {
      byte[] content = "synthetic cleanup admission evidence".getBytes(StandardCharsets.UTF_8);
      var uploaded =
          authority.ingestion().uploadDocument(owner, "fixture.txt", "text/plain", content);
      var claim = authority.ingestion().claimIngestion(owner.workspaceId()).orElseThrow();
      assertTrue(
          authority
              .ingestion()
              .completeIngestion(
                  claim, new TextParser().parse("fixture.txt", "text/plain", content)));
      var service = service(authority.store());
      var requested = service.request(owner, uploaded.documentId());
      assertEquals("pending", requested.cleanupStatus());
      try (var operation = authority.store().operationGate().enter()) {
        assertFalse(service.runOnce());
        assertEquals(requested, service.status(owner, uploaded.documentId()));
      }
      assertTrue(service.runOnce());
      assertEquals("completed", service.status(owner, uploaded.documentId()).cleanupStatus());
      assertFalse(service.runOnce());
    }
  }

  private static void awaitAuthorityMonitor(Thread worker, SqliteAuthorityStore store) {
    var threads = ManagementFactory.getThreadMXBean();
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline) {
      var info = threads.getThreadInfo(worker.threadId());
      if (info != null
          && info.getThreadState() == Thread.State.BLOCKED
          && info.getLockOwnerId() == Thread.currentThread().threadId()
          && info.getLockInfo() != null
          && info.getLockInfo().getIdentityHashCode() == System.identityHashCode(store)) {
        return;
      }
      Thread.onSpinWait();
    }
    throw new IllegalStateException("Cleanup tick did not reach the held authority monitor");
  }

  private static DocumentCleanupService service(SqliteAuthorityStore store) {
    return new DocumentCleanupService(
        store,
        new DocumentCleanupRepository(store),
        new DocumentLifecycleRepository(store),
        new ManagementRepository(store),
        new DocumentPermissionPolicy(),
        attempts -> {
          throw new AssertionError("Local cleanup must not call a remote provider");
        });
  }
}
