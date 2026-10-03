package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.TextModelConfiguration;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The immutable index anchor and the actual executing model are independently proved. */
class ManagedTextRoleSwitchRuntimeTest {
  @TempDir Path directory;

  @Test
  void consecutiveGenerationAndRerankSwitchesKeepTheOriginalIndexAndDisableLegacyMedia() {
    try (var fixture = new TextRoleSwitchTestFixture(directory)) {
      var first =
          fixture.activate(1, TextRoleSwitchTestFixture.roles("rerank-v1", "generation-v1"), null);
      var legacy = new LegacyTextProfileGuard(fixture.runtime, first.target());
      assertTrue(legacy.compatible());
      var second =
          fixture.activate(
              2,
              TextRoleSwitchTestFixture.roles("rerank-v1", "generation-v2"),
              first.indexAnchor());
      assertEquals(first.target(), second.target());
      assertEquals(first.indexAnchor(), second.indexAnchor());
      assertNotEquals(first.modelsRevision(), second.modelsRevision());
      assertFalse(legacy.compatible());
      assertEquals(
          "media_text_configuration_mismatch",
          assertThrows(ApplicationException.class, legacy::requireCompatible).code());
      var third =
          fixture.activate(
              3,
              TextRoleSwitchTestFixture.roles("rerank-v2", "generation-v2"),
              second.indexAnchor());
      assertEquals(first.target(), third.target());
      assertEquals(1L, third.indexAnchor().originatingVersion());
      assertEquals(
          TextRoleSwitchTestFixture.revision(
              TextRoleSwitchTestFixture.roles("rerank-v2", "generation-v2")),
          fixture.runtime.currentModelsRevision());
      assertEquals(first.indexAnchor(), fixture.runtime.currentAnchor());
      try (var operation = fixture.authority.store().operationGate().enter()) {
        assertSame(third, fixture.runtime.capture());
        assertTrue(fixture.runtime.isCurrent(third));
      }
      assertEquals(2, fixture.released.get());
      assertTrue(fixture.projection.calls.isEmpty());
    }
  }

  @Test
  void currentCredentialsDoNotChangeEitherProfileAndDoNotRetireOldBundleBeforeCommit() {
    try (var fixture = new TextRoleSwitchTestFixture(directory)) {
      var roles = TextRoleSwitchTestFixture.roles("rerank-v1", "generation-v1");
      var first = fixture.activate(1, roles, null);
      var changedKeys =
          new TextModelConfiguration(
              new TextModelConfiguration.Embedding(
                  roles.embedding().model(),
                  "rotated-embedding-key",
                  2,
                  roles.embedding().revision()),
              new TextModelConfiguration.Role(roles.rerank().model(), "rotated-rerank-key"),
              new TextModelConfiguration.Role(
                  roles.generation().model(), "rotated-generation-key"));
      var candidate = fixture.runtime.prepare(2, changedKeys, first.indexAnchor());
      assertEquals(first.modelsRevision(), candidate.modelsRevision());
      assertEquals(first.target(), candidate.target());
      try (var lease = fixture.authority.store().operationGate().tryMaintenance().orElseThrow()) {
        assertThrows(
            IllegalStateException.class,
            () ->
                fixture.runtime.install(
                    candidate,
                    lease,
                    () -> {
                      throw new IllegalStateException("Synthetic atomic persistence failure");
                    }));
      }
      assertEquals(1L, fixture.runtime.currentVersion());
      assertEquals(0, fixture.released.get());
      assertTrue(first.isOpen());
      assertTrue(candidate.isOpen());
      candidate.close();
      assertEquals(1, fixture.released.get());
      try (var operation = fixture.authority.store().operationGate().enter()) {
        assertSame(first, fixture.runtime.capture());
      }
    }
  }

  @Test
  void anchoredCandidateCannotBeInstalledThroughTheOldStrictRuntime() {
    try (var fixture = new TextRoleSwitchTestFixture(directory)) {
      var first =
          fixture.activate(1, TextRoleSwitchTestFixture.roles("rerank-v1", "generation-v1"), null);
      var candidate =
          fixture.runtime.prepare(
              2,
              TextRoleSwitchTestFixture.roles("rerank-v1", "generation-v2"),
              first.indexAnchor());
      var persisted = new AtomicInteger();
      try (var strict =
              new ManagedTextRuntime(fixture.authority.store(), (version, roles) -> candidate);
          var lease = fixture.authority.store().operationGate().tryMaintenance().orElseThrow()) {
        assertThrows(
            ApplicationException.class,
            () -> strict.install(candidate, lease, persisted::incrementAndGet));
      }
      assertEquals(0, persisted.get());
      assertEquals(1L, fixture.runtime.currentVersion());
      candidate.close();
      assertTrue(fixture.authority.store().operationGate().isIdle());
    }
  }
}
