package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibraryOperationGateTest {
  @TempDir Path directory;

  @Test
  void exclusiveMaintenanceClosesAdmissionAndCannotAuthorizeAnotherLibrary() {
    var gate = new LibraryOperationGate(directory.resolve("one"));
    var other = new LibraryOperationGate(directory.resolve("two"));
    try (var lease = gate.tryMaintenance().orElseThrow()) {
      assertTrue(lease.belongsTo(gate));
      assertFalse(lease.belongsTo(other));
      assertTrue(lease.isHeld());
      assertTrue(lease.isOwnerThread());
      assertTrue(LibraryWorkContext.isMaintenance());
      assertFalse(gate.isIdle());
      assertTrue(gate.tryMaintenance().isEmpty());
      var rejected = assertThrows(ApplicationException.class, gate::enter);
      assertEquals(FailureKind.UNAVAILABLE, rejected.kind());
      assertEquals("migration_incomplete", rejected.code());
    }
    assertTrue(gate.isIdle());
    assertTrue(LibraryWorkContext.currentRoot().isEmpty());
    try (var operation = gate.enter()) {
      assertEquals(gate.managedRoot(), LibraryWorkContext.currentRoot().orElseThrow());
      assertFalse(LibraryWorkContext.isMaintenance());
    }
  }

  @Test
  void nestedBodyLeasesRestoreContextAndDoubleCloseCannotReleaseAnotherBody() {
    var gate = new LibraryOperationGate(directory);
    var outer = gate.enter();
    var inner = gate.enter();
    assertTrue(gate.tryMaintenance().isEmpty());
    assertThrows(IllegalStateException.class, outer::close);
    inner.close();
    inner.close();
    assertFalse(gate.isIdle());
    assertEquals(gate.managedRoot(), LibraryWorkContext.currentRoot().orElseThrow());
    outer.close();
    outer.close();
    assertTrue(gate.isIdle());
    assertTrue(LibraryWorkContext.currentRoot().isEmpty());
  }

  @Test
  void callerTimeoutAndInterruptDoNotReleaseBodyBeforeItsActualFinally() throws Exception {
    var gate = new LibraryOperationGate(directory);
    var started = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var actualExit = new CountDownLatch(1);
    var finished = new CountDownLatch(1);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var body =
          executor.submit(
              () -> {
                try (var operation = gate.enter()) {
                  started.countDown();
                  boolean done = false;
                  while (!done) {
                    try {
                      done = actualExit.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException cancelled) {
                      interrupted.countDown();
                    }
                  }
                } finally {
                  finished.countDown();
                }
              });
      try {
        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertTrue(body.cancel(true));
        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        assertTrue(body.isDone());
        assertFalse(gate.isIdle());
        assertTrue(gate.tryMaintenance().isEmpty());
      } finally {
        actualExit.countDown();
      }
      assertTrue(finished.await(2, TimeUnit.SECONDS));
    }
    assertTrue(gate.isIdle());
    try (var maintenance = gate.tryMaintenance().orElseThrow()) {
      assertTrue(maintenance.isHeld());
    }
  }

  @Test
  void operationCannotBeReleasedByAnHttpWaitingThread() throws Exception {
    var gate = new LibraryOperationGate(directory);
    try (var bodyLease = gate.enter();
        var executor = Executors.newSingleThreadExecutor()) {
      assertTrue(
          executor
              .submit(
                  () -> {
                    assertThrows(IllegalStateException.class, bodyLease::close);
                    assertTrue(LibraryWorkContext.currentRoot().isEmpty());
                    return true;
                  })
              .get(2, TimeUnit.SECONDS));
      assertTrue(gate.tryMaintenance().isEmpty());
    }
    assertTrue(gate.isIdle());
  }

  @Test
  void queuedBodiesAreReservedBeforeTheirCallerLeavesAndReleaseOnTheirOwnThread() throws Exception {
    var gate = new LibraryOperationGate(directory);
    LibraryOperationGate.ReservedCall<Path> queued;
    try (var caller = gate.enter()) {
      queued =
          LibraryOperationGate.protectCurrent(
              () -> {
                return LibraryWorkContext.currentRoot().orElseThrow();
              });
    }
    assertFalse(gate.isIdle());
    assertTrue(gate.tryMaintenance().isEmpty());
    try (var executor = Executors.newSingleThreadExecutor()) {
      assertEquals(gate.managedRoot(), executor.submit(queued).get(2, TimeUnit.SECONDS));
    }
    queued.close();
    assertTrue(gate.isIdle());
    assertThrows(IllegalStateException.class, queued::call);
    assertTrue(LibraryWorkContext.currentRoot().isEmpty());
  }

  @Test
  void rejectedSubmissionCanCancelUnusedReservationWithoutReleasingRunningBody() {
    var gate = new LibraryOperationGate(directory);
    var reservation = gate.reserve();
    reservation.close();
    reservation.close();
    assertTrue(gate.isIdle());
    assertThrows(IllegalStateException.class, reservation::begin);
    var started = gate.reserve();
    try (var body = started.begin()) {
      started.close();
      assertFalse(gate.isIdle());
    }
    assertTrue(gate.isIdle());
  }
}
