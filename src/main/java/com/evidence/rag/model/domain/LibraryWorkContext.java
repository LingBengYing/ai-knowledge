package com.evidence.rag.model.domain;

import java.nio.file.Path;
import java.util.Optional;

/** Thread-owned library work context; only an actual operation lease installs it. */
public final class LibraryWorkContext {
  private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

  private LibraryWorkContext() {}

  public static Optional<Path> currentRoot() {
    Scope scope = CURRENT.get();
    return scope == null ? Optional.empty() : Optional.of(scope.root);
  }

  public static boolean isMaintenance() {
    Scope scope = CURRENT.get();
    return scope != null && scope.maintenance;
  }

  public static Optional<LibraryOperationGate> currentGate() {
    Scope scope = CURRENT.get();
    return scope == null ? Optional.empty() : Optional.of(scope.gate);
  }

  static Scope install(LibraryOperationGate gate, Path root, boolean maintenance) {
    Scope previous = CURRENT.get();
    if (previous != null && !previous.root.equals(root)) {
      throw new IllegalStateException("A thread cannot operate on two libraries at once");
    }
    Scope scope = new Scope(gate, root, maintenance, previous);
    CURRENT.set(scope);
    return scope;
  }

  static final class Scope implements AutoCloseable {
    private final LibraryOperationGate gate;
    private final Path root;
    private final boolean maintenance;
    private final Scope previous;
    private final Thread owner = Thread.currentThread();
    private boolean closed;

    private Scope(LibraryOperationGate gate, Path root, boolean maintenance, Scope previous) {
      this.gate = gate;
      this.root = root;
      this.maintenance = maintenance;
      this.previous = previous;
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      if (owner != Thread.currentThread() || CURRENT.get() != this) {
        throw new IllegalStateException("Library work context must close on its owning thread");
      }
      if (previous == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(previous);
      }
      closed = true;
    }
  }
}
