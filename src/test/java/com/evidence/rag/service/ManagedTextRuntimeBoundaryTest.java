package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.LibraryOperationGate;
import com.evidence.rag.model.domain.TextModelConfiguration;
import com.evidence.rag.model.domain.VerifiedRevision;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Invalid prepared bundles never retire the previously usable runtime or persist activation. */
@SuppressWarnings("try")
class ManagedTextRuntimeBoundaryTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"version", "dimensions", "closed"})
  void unusableFactoryBundleIsReleasedOnceAndTheInstalledBundleRemainsUsable(String kind) {
    try (var fixture = new ManagedTextTestFixture(directory)) {
      var released = new AtomicInteger();
      var oldReleased = new AtomicInteger();
      var candidate =
          fixture.snapshot(
              kind.equals("version") ? 3 : 2,
              () -> {
                released.incrementAndGet();
                if (kind.equals("dimensions")) {
                  throw new IllegalStateException("Synthetic rejected-client close failure");
                }
              });
      if (kind.equals("closed")) {
        candidate.close();
      }
      var configuration = ManagedTextTestFixture.configuration();
      if (kind.equals("dimensions")) {
        configuration =
            new TextModelConfiguration(
                new TextModelConfiguration.Embedding(
                    "test/embedding", "synthetic-key", 3, "embedding-v1"),
                configuration.rerank(),
                configuration.generation());
      }
      var requested = configuration;
      try (var runtime =
          new ManagedTextRuntime(
              fixture.context.authority.store(), (version, config) -> candidate)) {
        var old = fixture.snapshot(1, oldReleased::incrementAndGet);
        try (var lease =
            fixture.context.authority.store().operationGate().tryMaintenance().orElseThrow()) {
          runtime.install(old, lease, () -> {});
        }

        assertThrows(ApplicationException.class, () -> runtime.prepare(2, requested));

        assertEquals(1, released.get());
        assertFalse(candidate.isOpen());
        assertEquals(0, oldReleased.get());
        assertEquals(1L, runtime.currentVersion());
        try (var operation = fixture.context.authority.store().operationGate().enter()) {
          assertSame(old, runtime.capture());
        }
        assertTrue(fixture.context.models.calls.isEmpty());
        assertTrue(fixture.context.projection.calls.isEmpty());
      }
      assertEquals(1, oldReleased.get());
    }
  }

  @Test
  void missingFactoryBundleCannotCreateAnActiveConfigurationOrStartProviders() {
    try (var fixture = new ManagedTextTestFixture(directory);
        var runtime =
            new ManagedTextRuntime(fixture.context.authority.store(), (version, config) -> null)) {
      assertThrows(
          ApplicationException.class,
          () -> runtime.prepare(1, ManagedTextTestFixture.configuration()));
      assertNull(runtime.currentVersion());
      assertNull(runtime.currentTarget());
      assertTrue(fixture.context.authority.store().operationGate().isIdle());
      assertTrue(fixture.context.models.calls.isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"model", "projection"})
  void preparedCandidateProfileDriftFailsBeforePersistenceAndKeepsOldActive(String changed) {
    try (var fixture = new ManagedTextTestFixture(directory)) {
      fixture.activate(1);
      var projection = new ChangingProjection(fixture.context.projection);
      var released = new AtomicInteger();
      var candidate = snapshot(fixture, projection, released::incrementAndGet);
      var persisted = new AtomicInteger();
      if (changed.equals("model")) {
        fixture.context.models.modelRevision = "changed-after-prepare-v2";
      } else {
        projection.identity.set("b".repeat(64));
      }
      try (var lease =
          fixture.context.authority.store().operationGate().tryMaintenance().orElseThrow()) {
        assertThrows(
            ApplicationException.class,
            () -> fixture.runtime.install(candidate, lease, persisted::incrementAndGet));
      }
      assertEquals(0, persisted.get());
      assertEquals(0, released.get());
      assertEquals(1L, fixture.runtime.currentVersion());
      candidate.close();
      assertEquals(1, released.get());
      assertTrue(fixture.context.models.calls.isEmpty());
      assertTrue(fixture.context.projection.calls.isEmpty());
    }
  }

  @Test
  void genuineLeaseBorrowedByAnotherThreadCannotPersistOrSwap() throws Exception {
    try (var fixture = new ManagedTextTestFixture(directory)) {
      fixture.activate(1);
      var candidate = fixture.snapshot(2, () -> {});
      var persisted = new AtomicInteger();
      var failure = new AtomicReference<Throwable>();
      try (var lease =
          fixture.context.authority.store().operationGate().tryMaintenance().orElseThrow()) {
        Thread outsider =
            Thread.ofVirtual()
                .start(
                    () -> {
                      try {
                        fixture.runtime.install(candidate, lease, persisted::incrementAndGet);
                      } catch (Throwable rejected) {
                        failure.set(rejected);
                      }
                    });
        outsider.join(2_000);
        assertFalse(outsider.isAlive());
        assertTrue(failure.get() instanceof ApplicationException);
        assertTrue(lease.isHeld());
      }
      assertEquals(0, persisted.get());
      assertEquals(1L, fixture.runtime.currentVersion());
      assertTrue(candidate.isOpen());
      candidate.close();
      assertTrue(fixture.context.authority.store().operationGate().isIdle());
    }
  }

  @Test
  void reinstallingSameBundleDoesNotCloseItsClientsAndForeignCaptureCannotBorrowIt() {
    try (var fixture = new ManagedTextTestFixture(directory)) {
      var released = new AtomicInteger();
      var candidate = fixture.snapshot(1, released::incrementAndGet);
      try (var lease =
          fixture.context.authority.store().operationGate().tryMaintenance().orElseThrow()) {
        fixture.runtime.install(candidate, lease, () -> {});
        fixture.runtime.install(candidate, lease, () -> {});
      }
      assertEquals(0, released.get());
      var foreign = new LibraryOperationGate(directory.resolve("foreign-work"));
      try (var operation = foreign.enter()) {
        assertThrows(ApplicationException.class, fixture.runtime::capture);
      }
      try (var operation = fixture.context.authority.store().operationGate().enter()) {
        assertSame(candidate, fixture.runtime.capture());
        assertTrue(fixture.runtime.isCurrent(candidate));
      }
      fixture.runtime.close();
      fixture.runtime.close();
      assertEquals(1, released.get());
      assertNull(fixture.runtime.currentVersion());
    }
  }

  @Test
  void unexpectedlyDisposedActiveBundleCannotBeCapturedOrQualifyAndShutdownCannotReactivate() {
    try (var fixture = new ManagedTextTestFixture(directory)) {
      var candidate = fixture.snapshot(1, () -> {});
      try (var lease =
          fixture.context.authority.store().operationGate().tryMaintenance().orElseThrow()) {
        fixture.runtime.install(candidate, lease, () -> {});
      }
      candidate.close();
      assertFalse(fixture.runtime.isCurrent(candidate));
      try (var operation = fixture.context.authority.store().operationGate().enter()) {
        assertEquals(
            "text_configuration_required",
            assertThrows(ApplicationException.class, fixture.runtime::capture).code());
      }
      fixture.runtime.close();
      var replacement = fixture.snapshot(2, () -> {});
      try (var lease =
          fixture.context.authority.store().operationGate().tryMaintenance().orElseThrow()) {
        assertThrows(
            ApplicationException.class,
            () -> fixture.runtime.install(replacement, lease, () -> {}));
      }
      assertThrows(
          ApplicationException.class,
          () -> fixture.runtime.prepare(2, ManagedTextTestFixture.configuration()));
      assertTrue(replacement.isOpen());
      replacement.close();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"model", "projection", "answer-target"})
  void snapshotAssemblyRejectsCrossProfileCollaboratorsWithoutClosingBorrowedServices(String kind) {
    try (var fixture = new ManagedTextTestFixture(directory)) {
      var base = fixture.context.target;
      var target =
          switch (kind) {
            case "model" ->
                new IndexTarget(
                    base.embeddingIdentity(), base.projectionIdentity(), "other-model-v2", 2);
            case "projection" ->
                new IndexTarget(base.embeddingIdentity(), "c".repeat(64), base.modelRevision(), 2);
            case "answer-target" ->
                new IndexTarget(
                    "other-embedding-v2", base.projectionIdentity(), base.modelRevision(), 2);
            default -> throw new AssertionError("Unknown synthetic profile mismatch");
          };
      var indexing = indexer(fixture, target);
      var released = new AtomicInteger();
      assertThrows(
          ApplicationException.class,
          () ->
              new TextRuntimeSnapshot(
                  1,
                  fixture.context.models,
                  fixture.context.projection,
                  target,
                  fixture.context.answers,
                  indexing,
                  released::incrementAndGet));
      assertEquals(0, released.get());
      assertEquals(base, fixture.context.answers.runtimeTarget());
      assertTrue(fixture.context.models.calls.isEmpty());
      assertTrue(fixture.context.projection.calls.isEmpty());
    }
  }

  private static TextRuntimeSnapshot snapshot(
      ManagedTextTestFixture fixture, RetrievalProjection projection, Runnable release) {
    var answers =
        new AnswerService(
            fixture.context.evidence,
            fixture.context.models,
            projection,
            fixture.context.target,
            Duration.ofSeconds(3),
            1);
    return new TextRuntimeSnapshot(
        2,
        fixture.context.models,
        projection,
        fixture.context.target,
        answers,
        indexer(fixture, fixture.context.target),
        release);
  }

  private static IndexingTaskProcessor indexer(ManagedTextTestFixture fixture, IndexTarget target) {
    return new IndexingTaskProcessor(
        fixture.context.authority.indexing(),
        fixture.context.owner.workspaceId(),
        target,
        Duration.ofSeconds(3),
        ignored -> {
          throw new AssertionError("Offline bundle tests must not index");
        });
  }

  private static final class ChangingProjection implements RetrievalProjection {
    private final RetrievalProjection delegate;
    private final AtomicReference<String> identity;

    ChangingProjection(RetrievalProjection delegate) {
      this.delegate = delegate;
      identity = new AtomicReference<>(delegate.identity());
    }

    @Override
    public String identity() {
      return identity.get();
    }

    @Override
    public VerifiedRevision verify(RevisionManifest manifest) {
      throw new AssertionError("No verification");
    }

    @Override
    public void initialize() {
      throw new AssertionError("No initialization");
    }

    @Override
    public void prepareSearch() {
      delegate.prepareSearch();
    }

    @Override
    public void upsert(List<Entry> entries) {
      throw new AssertionError("No writes");
    }

    @Override
    public List<Candidate> search(Query query) {
      return delegate.search(query);
    }
  }
}
