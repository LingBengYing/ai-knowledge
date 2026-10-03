package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.LibraryOperationGate;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedTextRuntimeTest {
  @TempDir Path directory;

  @Test
  void unconfiguredCaptureRequiresActualOrdinaryLibraryContextAndCallsNoProvider() {
    try (var fixture = new ManagedTextTestFixture(directory)) {
      assertNull(fixture.runtime.currentVersion());
      assertNull(fixture.runtime.currentTarget());
      assertThrows(ApplicationException.class, fixture.runtime::capture);
      try (var operation = fixture.context.authority.store().operationGate().enter()) {
        assertEquals(
            "text_configuration_required",
            assertThrows(ApplicationException.class, fixture.runtime::capture).code());
      }
      assertTrue(fixture.context.models.calls.isEmpty());
      assertTrue(fixture.context.projection.calls.isEmpty());
    }
  }

  @Test
  void persistenceFailureKeepsOldBundleAndDoesNotReleaseIt() {
    try (var fixture = new ManagedTextTestFixture(directory)) {
      var oldReleased = new AtomicInteger();
      var newReleased = new AtomicInteger();
      var old = fixture.snapshot(1, oldReleased::incrementAndGet);
      var replacement = fixture.snapshot(2, newReleased::incrementAndGet);
      try (var lease =
          fixture.context.authority.store().operationGate().tryMaintenance().orElseThrow()) {
        fixture.runtime.install(old, lease, () -> {});
        assertThrows(
            IllegalStateException.class,
            () ->
                fixture.runtime.install(
                    replacement,
                    lease,
                    () -> {
                      throw new IllegalStateException("synthetic persist failure");
                    }));
      }
      assertEquals(1L, fixture.runtime.currentVersion());
      assertEquals(0, oldReleased.get());
      assertEquals(0, newReleased.get());
      replacement.close();
      assertEquals(1, newReleased.get());
      try (var operation = fixture.context.authority.store().operationGate().enter()) {
        assertSame(old, fixture.runtime.capture());
      }
    }
  }

  @Test
  void authenticHeldOwnerLeaseIsRequiredBeforePersistence() {
    try (var fixture = new ManagedTextTestFixture(directory)) {
      var persisted = new AtomicInteger();
      var candidate = fixture.snapshot(1, () -> {});
      var foreign = new LibraryOperationGate(directory.resolve("foreign"));
      try (var lease = foreign.tryMaintenance().orElseThrow()) {
        assertThrows(
            ApplicationException.class,
            () -> fixture.runtime.install(candidate, lease, persisted::incrementAndGet));
      }
      var lease = fixture.context.authority.store().operationGate().tryMaintenance().orElseThrow();
      lease.close();
      assertThrows(
          ApplicationException.class,
          () -> fixture.runtime.install(candidate, lease, persisted::incrementAndGet));
      assertEquals(0, persisted.get());
      assertNull(fixture.runtime.currentVersion());
      candidate.close();
    }
  }

  @Test
  void capturedBodyBlocksActivationAndUsesOneImmutableBundle() {
    try (var fixture = new ManagedTextTestFixture(directory)) {
      fixture.activate(1);
      try (var operation = fixture.context.authority.store().operationGate().enter()) {
        var captured = fixture.runtime.capture();
        assertTrue(fixture.context.authority.store().operationGate().tryMaintenance().isEmpty());
        assertSame(captured, fixture.runtime.capture());
        assertSame(fixture.context.models, captured.models());
        assertSame(fixture.context.projection, captured.projection());
        assertEquals(fixture.context.target, captured.target());
      }
      try (var maintenance =
          fixture.context.authority.store().operationGate().tryMaintenance().orElseThrow()) {
        assertThrows(ApplicationException.class, fixture.runtime::capture);
      }
    }
  }

  @Test
  void successfulSwapClosesOldOnceEvenWhenItsClientReleaseFails() {
    try (var fixture = new ManagedTextTestFixture(directory)) {
      var released = new AtomicInteger();
      var old =
          fixture.snapshot(
              1,
              () -> {
                released.incrementAndGet();
                throw new IllegalStateException("synthetic close");
              });
      var replacement = fixture.snapshot(2, () -> {});
      try (var lease =
          fixture.context.authority.store().operationGate().tryMaintenance().orElseThrow()) {
        fixture.runtime.install(old, lease, () -> {});
        fixture.runtime.install(replacement, lease, () -> {});
      }
      old.close();
      assertEquals(1, released.get());
      assertEquals(2L, fixture.runtime.currentVersion());
      try (var operation = fixture.context.authority.store().operationGate().enter()) {
        assertSame(replacement, fixture.runtime.capture());
      }
      fixture.runtime.close();
      fixture.runtime.close();
      assertNull(fixture.runtime.currentTarget());
      assertFalse(fixture.runtime.isCurrent(replacement));
    }
  }

  @Test
  void bundleRejectsIndexProcessorBoundToAnotherTargetWithoutProviders() {
    try (var fixture = new ManagedTextTestFixture(directory)) {
      var target = fixture.context.target;
      var other =
          new IndexTarget(
              "other-embedding", target.projectionIdentity(), target.modelRevision(), 2);
      assertThrows(ApplicationException.class, () -> fixture.snapshot(1, other, () -> {}));
      assertTrue(fixture.context.models.calls.isEmpty());
      assertTrue(fixture.context.projection.calls.isEmpty());
    }
  }
}
