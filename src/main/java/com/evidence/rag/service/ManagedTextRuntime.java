package com.evidence.rag.service;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.LibraryOperationGate;
import com.evidence.rag.model.domain.LibraryWorkContext;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.TextIndexAnchor;
import com.evidence.rag.model.domain.TextModelConfiguration;
import com.evidence.rag.repository.SqliteAuthorityStore;

/** A library-local pointer installed only after private persistence succeeds under maintenance. */
public final class ManagedTextRuntime implements AutoCloseable {
  @FunctionalInterface
  public interface SnapshotFactory {
    TextRuntimeSnapshot build(long version, TextModelConfiguration configuration);
  }

  @FunctionalInterface
  public interface AnchoredSnapshotFactory {
    TextRuntimeSnapshot build(
        long version, TextModelConfiguration configuration, TextIndexAnchor indexAnchor);
  }

  private final SqliteAuthorityStore store;
  private final SnapshotFactory factory;
  private final AnchoredSnapshotFactory anchoredFactory;
  private volatile TextRuntimeSnapshot active;
  private volatile boolean closed;

  public ManagedTextRuntime(SqliteAuthorityStore store, SnapshotFactory factory) {
    this(store, factory, null);
  }

  private ManagedTextRuntime(
      SqliteAuthorityStore store,
      SnapshotFactory factory,
      AnchoredSnapshotFactory anchoredFactory) {
    if (store == null || (factory == null) == (anchoredFactory == null)) {
      throw ModelValues.invalid();
    }
    this.store = store;
    this.factory = factory;
    this.anchoredFactory = anchoredFactory;
  }

  public static ManagedTextRuntime anchored(
      SqliteAuthorityStore store, AnchoredSnapshotFactory factory) {
    return new ManagedTextRuntime(store, null, factory);
  }

  boolean anchored() {
    return anchoredFactory != null;
  }

  public TextRuntimeSnapshot prepare(long version, TextModelConfiguration configuration) {
    return prepare(version, configuration, null);
  }

  public TextRuntimeSnapshot prepare(
      long version, TextModelConfiguration configuration, TextIndexAnchor indexAnchor) {
    if (closed || version < 1 || version > 9_007_199_254_740_991L || configuration == null) {
      throw ModelValues.invalid();
    }
    if (anchoredFactory == null && indexAnchor != null) {
      throw ModelValues.invalid();
    }
    var candidate =
        anchoredFactory == null
            ? factory.build(version, configuration)
            : anchoredFactory.build(version, configuration, indexAnchor);
    if (candidate == null) {
      throw ModelValues.invalid();
    }
    if (!candidate.isOpen()
        || candidate.version() != version
        || candidate.target().dimensions() != configuration.embedding().dimensions()
        || !candidate.identityCurrent()
        || (anchoredFactory != null
            && (candidate.indexAnchor() == null
                || (indexAnchor != null && !indexAnchor.equals(candidate.indexAnchor()))
                || (indexAnchor == null && candidate.indexAnchor().originatingVersion() != version)
                || !candidate
                    .indexAnchor()
                    .embeddingModel()
                    .equals(configuration.embedding().model())
                || !candidate
                    .indexAnchor()
                    .embeddingRevision()
                    .equals(configuration.embedding().revision())))) {
      try {
        candidate.close();
      } catch (RuntimeException ignored) {
        // A rejected unpublished candidate cannot replace the installed bundle.
      }
      throw ModelValues.invalid();
    }
    return candidate;
  }

  public synchronized void install(
      TextRuntimeSnapshot candidate,
      LibraryOperationGate.MaintenanceLease lease,
      Runnable persistActive) {
    if (closed
        || candidate == null
        || !candidate.isOpen()
        || lease == null
        || persistActive == null
        || !lease.belongsTo(store.operationGate())
        || !lease.isHeld()
        || !lease.isOwnerThread()
        || !candidate.identityCurrent()
        || (anchoredFactory == null
            && !candidate.target().modelRevision().equals(candidate.modelsRevision()))
        || (anchoredFactory != null && candidate.indexAnchor() == null)) {
      throw ModelValues.invalid();
    }
    persistActive.run();
    var previous = active;
    active = candidate;
    if (previous != null && previous != candidate) {
      closeAfterCommit(previous);
    }
  }

  public TextRuntimeSnapshot capture() {
    if (LibraryWorkContext.isMaintenance()
        || LibraryWorkContext.currentGate().orElse(null) != store.operationGate()) {
      throw ModelValues.invalid();
    }
    var snapshot = active;
    if (closed || snapshot == null || !snapshot.isOpen()) {
      throw new ApplicationException(
          FailureKind.UNAVAILABLE, "text_configuration_required", "请先完成并应用文字模型配置。");
    }
    return snapshot;
  }

  public Long currentVersion() {
    var snapshot = active;
    return closed || snapshot == null ? null : snapshot.version();
  }

  public IndexTarget currentTarget() {
    var snapshot = active;
    return closed || snapshot == null ? null : snapshot.target();
  }

  public TextIndexAnchor currentAnchor() {
    var snapshot = active;
    return closed || snapshot == null ? null : snapshot.indexAnchor();
  }

  public String currentModelsRevision() {
    var snapshot = active;
    return closed || snapshot == null ? null : snapshot.modelsRevision();
  }

  boolean isCurrent(TextRuntimeSnapshot snapshot) {
    return !closed
        && snapshot != null
        && snapshot == active
        && snapshot.isOpen()
        && snapshot.identityCurrent();
  }

  @Override
  public synchronized void close() {
    if (!closed) {
      closed = true;
      var previous = active;
      active = null;
      if (previous != null) {
        closeAfterCommit(previous);
      }
    }
  }

  private static void closeAfterCommit(TextRuntimeSnapshot snapshot) {
    try {
      snapshot.close();
    } catch (RuntimeException ignored) {
      // Persistence and the installed pointer already committed. No provider data is logged.
    }
  }

  @Override
  public String toString() {
    return "ManagedTextRuntime[redacted]";
  }
}
