package com.evidence.rag.model.domain;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;

/** Actual operation bodies and exclusive, nonblocking library maintenance share one lock. */
public final class LibraryOperationGate {
  private final Path managedRoot;
  private int active;
  private MaintenanceLease maintenance;

  public LibraryOperationGate(Path managedRoot) {
    this.managedRoot = Objects.requireNonNull(managedRoot).toAbsolutePath().normalize();
  }

  public synchronized OperationLease enter() {
    if (maintenance != null) {
      throw new ApplicationException(
          FailureKind.UNAVAILABLE, "migration_incomplete", "Library maintenance is in progress");
    }
    LibraryWorkContext.Scope context = LibraryWorkContext.install(this, managedRoot, false);
    active++;
    return new OperationLease(this, context);
  }

  /** Scheduler ticks skip maintenance without changing persisted task state. */
  public synchronized Optional<OperationLease> tryOperation() {
    return maintenance == null ? Optional.of(enter()) : Optional.empty();
  }

  public synchronized Optional<MaintenanceLease> tryMaintenance() {
    if (active != 0 || maintenance != null) {
      return Optional.empty();
    }
    LibraryWorkContext.Scope context = LibraryWorkContext.install(this, managedRoot, true);
    maintenance = new MaintenanceLease(this, context);
    return Optional.of(maintenance);
  }

  public synchronized boolean isIdle() {
    return active == 0 && maintenance == null;
  }

  public Path managedRoot() {
    return managedRoot;
  }

  /** Reserve before handing a body to a thread; queued bodies also block maintenance. */
  public synchronized ReservedOperation reserve() {
    if (maintenance != null) {
      throw new ApplicationException(
          FailureKind.UNAVAILABLE, "migration_incomplete", "Library maintenance is in progress");
    }
    active++;
    return new ReservedOperation(this);
  }

  public static <T> ReservedCall<T> protectCurrent(Callable<T> body) {
    var reservation =
        LibraryWorkContext.currentGate().map(LibraryOperationGate::reserve).orElse(null);
    return new ReservedCall<>(Objects.requireNonNull(body), reservation);
  }

  public static ReservedRunnable protectCurrent(Runnable body) {
    var reservation =
        LibraryWorkContext.currentGate().map(LibraryOperationGate::reserve).orElse(null);
    return new ReservedRunnable(Objects.requireNonNull(body), reservation);
  }

  public static final class ReservedOperation implements AutoCloseable {
    private final LibraryOperationGate gate;
    private boolean begun;
    private boolean cancelled;

    private ReservedOperation(LibraryOperationGate gate) {
      this.gate = gate;
    }

    public OperationLease begin() {
      synchronized (gate) {
        if (begun || cancelled || gate.maintenance != null) {
          throw new IllegalStateException("A reserved body can begin only once");
        }
        LibraryWorkContext.Scope context =
            LibraryWorkContext.install(gate, gate.managedRoot, false);
        begun = true;
        return new OperationLease(gate, context);
      }
    }

    /** Submission failure may abandon an unused reservation; running bodies retain their lease. */
    @Override
    public void close() {
      synchronized (gate) {
        if (!begun && !cancelled) {
          cancelled = true;
          gate.active--;
        }
      }
    }
  }

  public static final class ReservedCall<T> implements Callable<T>, AutoCloseable {
    private final Callable<T> body;
    private final ReservedOperation reservation;

    private ReservedCall(Callable<T> body, ReservedOperation reservation) {
      this.body = body;
      this.reservation = reservation;
    }

    @Override
    public T call() throws Exception {
      try (var lease = reservation == null ? null : reservation.begin()) {
        return body.call();
      }
    }

    @Override
    public void close() {
      if (reservation != null) {
        reservation.close();
      }
    }
  }

  public static final class ReservedRunnable implements Runnable, AutoCloseable {
    private final Runnable body;
    private final ReservedOperation reservation;

    private ReservedRunnable(Runnable body, ReservedOperation reservation) {
      this.body = body;
      this.reservation = reservation;
    }

    @Override
    public void run() {
      try (var lease = reservation == null ? null : reservation.begin()) {
        body.run();
      }
    }

    @Override
    public void close() {
      if (reservation != null) {
        reservation.close();
      }
    }
  }

  public static final class OperationLease implements AutoCloseable {
    private final LibraryOperationGate gate;
    private final LibraryWorkContext.Scope context;
    private final Thread owner = Thread.currentThread();
    private boolean closed;

    private OperationLease(LibraryOperationGate gate, LibraryWorkContext.Scope context) {
      this.gate = gate;
      this.context = context;
    }

    @Override
    public void close() {
      synchronized (gate) {
        if (closed) {
          return;
        }
        if (owner != Thread.currentThread()) {
          throw new IllegalStateException(
              "An operation lease must close on its actual body thread");
        }
        context.close();
        activeRelease();
        closed = true;
      }
    }

    private void activeRelease() {
      gate.active--;
      if (gate.active < 0) {
        throw new IllegalStateException("Invalid library operation lifetime");
      }
    }
  }

  public static final class MaintenanceLease implements AutoCloseable {
    private final LibraryOperationGate gate;
    private final LibraryWorkContext.Scope context;
    private final Thread owner = Thread.currentThread();
    private boolean closed;

    private MaintenanceLease(LibraryOperationGate gate, LibraryWorkContext.Scope context) {
      this.gate = gate;
      this.context = context;
    }

    public boolean belongsTo(LibraryOperationGate expected) {
      return gate == expected;
    }

    public boolean isOwnerThread() {
      return owner == Thread.currentThread();
    }

    public boolean isHeld() {
      synchronized (gate) {
        return !closed && gate.maintenance == this && gate.active == 0;
      }
    }

    @Override
    public void close() {
      synchronized (gate) {
        if (closed) {
          return;
        }
        if (!isOwnerThread() || gate.maintenance != this) {
          throw new IllegalStateException("Maintenance must close on its owning thread");
        }
        context.close();
        gate.maintenance = null;
        closed = true;
      }
    }
  }
}
