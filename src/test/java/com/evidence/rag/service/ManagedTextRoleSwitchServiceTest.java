package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.TextModelConnectionProbe;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.dto.SaveModelConfigurationCommand;
import com.evidence.rag.repository.ModelConfigurationRepository;
import com.evidence.rag.security.authorization.ModelConfigurationPermissionPolicy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedTextRoleSwitchServiceTest {
  @TempDir Path directory;

  @Test
  void existingPublicationAllowsRoleOnlyActivationAndAtomicPersistenceRetainsTheExactAnchor()
      throws Exception {
    try (var fixture = new TextRoleSwitchTestFixture(directory.resolve("authority"));
        var repository =
            new ModelConfigurationRepository(directory.resolve("private/config.json"))) {
      var service = service(fixture, repository);
      service.save(
          TextRoleSwitchTestFixture.ADMIN, command(0, "fixture-v1", "rerank-v1", "generation-v1"));
      service.activate(TextRoleSwitchTestFixture.ADMIN, 1);
      var original = repository.read().indexAnchor();
      fixture.publish(original.target());
      var stored =
          fixture.scalar(
              "SELECT group_concat(id || ':' || model_revision) FROM index_publications", null);
      String oldExecution = fixture.runtime.currentModelsRevision();
      service.save(
          TextRoleSwitchTestFixture.ADMIN, command(1, "fixture-v1", "rerank-v1", "generation-v2"));
      assertEquals(original, repository.read().indexAnchor());
      assertEquals(2L, service.activate(TextRoleSwitchTestFixture.ADMIN, 2).activeVersion());
      assertEquals(original, repository.read().indexAnchor());
      assertEquals(original.target(), fixture.runtime.currentTarget());
      assertNotEquals(oldExecution, fixture.runtime.currentModelsRevision());
      service.save(
          TextRoleSwitchTestFixture.ADMIN, command(2, "fixture-v1", "rerank-v2", "generation-v2"));
      assertEquals(3L, service.activate(TextRoleSwitchTestFixture.ADMIN, 3).activeVersion());
      assertEquals(original, repository.read().indexAnchor());
      assertEquals(
          stored,
          fixture.scalar(
              "SELECT group_concat(id || ':' || model_revision) FROM index_publications", null));
      assertTrue(fixture.projection.calls.isEmpty());
    }
  }

  @Test
  void embeddingChangeCannotReplaceTheActiveVersionOrItsPersistentAnchor() {
    try (var fixture = new TextRoleSwitchTestFixture(directory.resolve("authority"));
        var repository =
            new ModelConfigurationRepository(directory.resolve("private/config.json"))) {
      var service = service(fixture, repository);
      service.save(
          TextRoleSwitchTestFixture.ADMIN, command(0, "fixture-v1", "rerank-v1", "generation-v1"));
      service.activate(TextRoleSwitchTestFixture.ADMIN, 1);
      var previous = repository.read();
      service.save(
          TextRoleSwitchTestFixture.ADMIN, command(1, "fixture-v2", "rerank-v1", "generation-v2"));
      assertEquals(
          "model_rebuild_required",
          assertThrows(
                  ApplicationException.class,
                  () -> service.activate(TextRoleSwitchTestFixture.ADMIN, 2))
              .code());
      assertEquals(previous.active(), repository.read().active());
      assertEquals(previous.indexAnchor(), repository.read().indexAnchor());
      assertEquals(1L, fixture.runtime.currentVersion());
      assertTrue(fixture.authority.store().operationGate().isIdle());
    }
  }

  @Test
  void aConflictingExistingCompleteTargetIsNotAdoptedByAnOtherwiseCompatibleRoleSwitch() {
    try (var fixture = new TextRoleSwitchTestFixture(directory.resolve("authority"));
        var repository =
            new ModelConfigurationRepository(directory.resolve("private/config.json"))) {
      var service = service(fixture, repository);
      service.save(
          TextRoleSwitchTestFixture.ADMIN, command(0, "fixture-v1", "rerank-v1", "generation-v1"));
      service.activate(TextRoleSwitchTestFixture.ADMIN, 1);
      var original = repository.read().indexAnchor();
      var target = original.target();
      fixture.publish(
          new IndexTarget(
              target.embeddingIdentity(),
              target.projectionIdentity(),
              "unknown-origin-model-v1",
              2));
      service.save(
          TextRoleSwitchTestFixture.ADMIN, command(1, "fixture-v1", "rerank-v1", "generation-v2"));
      assertEquals(
          "model_rebuild_required",
          assertThrows(
                  ApplicationException.class,
                  () -> service.activate(TextRoleSwitchTestFixture.ADMIN, 2))
              .code());
      assertEquals(1L, repository.read().activeVersion());
      assertEquals(original, repository.read().indexAnchor());
      assertEquals(original.target(), fixture.runtime.currentTarget());
    }
  }

  @Test
  void unrecoveredSavedActiveCannotBeReanchoredFromANewerDraft() {
    try (var fixture = new TextRoleSwitchTestFixture(directory.resolve("authority"));
        var repository =
            new ModelConfigurationRepository(directory.resolve("private/config.json"))) {
      repository.save(0, TextRoleSwitchTestFixture.roles("rerank-v1", "generation-v1"));
      repository.activate(1);
      repository.save(1, TextRoleSwitchTestFixture.roles("rerank-v1", "generation-v2"));
      var service = service(fixture, repository);
      assertEquals(
          "model_configuration_unavailable",
          assertThrows(
                  ApplicationException.class,
                  () -> service.activate(TextRoleSwitchTestFixture.ADMIN, 2))
              .code());
      assertEquals(1L, repository.read().activeVersion());
      assertNull(repository.read().indexAnchor());
      assertNull(fixture.runtime.currentVersion());
      assertTrue(fixture.projection.calls.isEmpty());
    }
  }

  @Test
  void activeOrdinaryBodyStillRejectsRoleActivationWithoutMutatingEitherActiveIdentity() {
    try (var fixture = new TextRoleSwitchTestFixture(directory.resolve("authority"));
        var repository =
            new ModelConfigurationRepository(directory.resolve("private/config.json"))) {
      var service = service(fixture, repository);
      service.save(
          TextRoleSwitchTestFixture.ADMIN, command(0, "fixture-v1", "rerank-v1", "generation-v1"));
      service.activate(TextRoleSwitchTestFixture.ADMIN, 1);
      var original = repository.read();
      service.save(
          TextRoleSwitchTestFixture.ADMIN, command(1, "fixture-v1", "rerank-v2", "generation-v2"));
      try (var operation = fixture.authority.store().operationGate().enter()) {
        assertEquals(
            "configuration_busy",
            assertThrows(
                    ApplicationException.class,
                    () -> service.activate(TextRoleSwitchTestFixture.ADMIN, 2))
                .code());
        assertEquals(original.active(), repository.read().active());
        assertEquals(original.indexAnchor(), repository.read().indexAnchor());
        assertEquals(1L, fixture.runtime.capture().version());
      }
      assertEquals(2L, service.activate(TextRoleSwitchTestFixture.ADMIN, 2).activeVersion());
    }
  }

  private static ModelConfigurationService service(
      TextRoleSwitchTestFixture fixture, ModelConfigurationRepository repository) {
    return new ModelConfigurationService(
        fixture.authority.store(),
        repository,
        new ModelConfigurationPermissionPolicy("org-main", Set.of("owner")),
        fixture.runtime,
        new TextModelConnectionProbe(
            TextRoleSwitchTestFixture.PROVIDER, Duration.ofSeconds(1), 4096, false, null));
  }

  private static SaveModelConfigurationCommand command(
      long base, String revision, String rerank, String generation) {
    return new SaveModelConfigurationCommand(
        base,
        new SaveModelConfigurationCommand.EmbeddingInput(
            "fixture-embedding", 2, revision, "synthetic-embedding-key"),
        new SaveModelConfigurationCommand.RoleInput(rerank, "synthetic-rerank-key"),
        new SaveModelConfigurationCommand.RoleInput(generation, "synthetic-generation-key"));
  }
}
