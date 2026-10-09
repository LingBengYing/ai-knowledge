package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.TextModelConnectionProbe;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.TextModelRole;
import com.evidence.rag.model.dto.SaveModelConfigurationCommand;
import com.evidence.rag.repository.ModelConfigurationRepository;
import com.evidence.rag.security.authorization.ModelConfigurationPermissionPolicy;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelConfigurationServiceTest {
  private static final Actor ADMIN = new Actor("org-main", "operator");
  @TempDir Path directory;

  @Test
  void emptyStartupReaderGetSaveAndSameTargetRotationKeepTheirDistinctStatesWithoutProviderCalls() {
    try (var fixture = new ManagedTextTestFixture(directory.resolve("authority"));
        var repository =
            new ModelConfigurationRepository(directory.resolve("private/config.json"))) {
      var service = service(fixture, repository);
      var reader = service.get(new Actor("org-main", "reader"));
      assertEquals("unconfigured", reader.state());
      assertTrue(reader.canEdit());
      assertNull(reader.embedding().model());
      var member = new Actor("org-main", "second-member-with-no-administrator-role");
      var saved = service.save(member, command(0, "first-synthetic-key"));
      assertEquals("draft", saved.state());
      assertNull(saved.activeVersion());
      assertEquals("active", service.activate(member, 1).state());
      var next = service.save(ADMIN, command(1, "second-synthetic-key"));
      assertEquals("draft", next.state());
      assertEquals(1L, next.activeVersion());
      assertEquals("first-synthetic-key", repository.read().active().rerank().apiKey());
      assertEquals(2L, service.activate(ADMIN, 2).activeVersion());
      assertEquals("second-synthetic-key", repository.read().active().rerank().apiKey());
      assertTrue(fixture.context.models.calls.isEmpty());
      assertTrue(fixture.context.projection.calls.isEmpty());
    }
  }

  @Test
  void staleVersionsAndForeignWorkspaceControlsFailBeforeTestingOrTakingMaintenance() {
    try (var fixture = new ManagedTextTestFixture(directory.resolve("authority"));
        var repository =
            new ModelConfigurationRepository(directory.resolve("private/config.json"))) {
      var service = service(fixture, repository);
      service.save(ADMIN, command(0, "synthetic-key"));
      assertEquals(
          "configuration_conflict",
          assertThrows(
                  ApplicationException.class, () -> service.save(ADMIN, command(0, "other-key")))
              .code());
      assertEquals(
          "configuration_conflict",
          assertThrows(
                  ApplicationException.class, () -> service.test(ADMIN, 0, TextModelRole.EMBEDDING))
              .code());
      try (var operation = fixture.context.authority.store().operationGate().enter()) {
        assertEquals(
            "model_configuration_forbidden",
            assertThrows(
                    ApplicationException.class,
                    () -> service.activate(new Actor("other-workspace", "owner"), 1))
                .code());
        assertEquals(
            "configuration_busy",
            assertThrows(ApplicationException.class, () -> service.activate(ADMIN, 1)).code());
      }
      assertNull(repository.read().activeVersion());
      assertNull(fixture.runtime.currentVersion());
      assertFalse(service.get(ADMIN).projection().configured());
      assertEquals(
          "projection_configuration_required",
          service.test(ADMIN, 1, TextModelRole.PROJECTION).errorCode());
    }
  }

  @Test
  void missingKeyCanBeKeptOnlyAfterItsRoleHasBeenSaved() {
    try (var fixture = new ManagedTextTestFixture(directory.resolve("authority"));
        var repository =
            new ModelConfigurationRepository(directory.resolve("private/config.json"))) {
      var service = service(fixture, repository);
      service.save(ADMIN, command(0, "synthetic-key"));
      service.save(ADMIN, command(1, null));
      assertEquals("synthetic-key", repository.read().draft().generation().apiKey());
      assertTrue(service.get(ADMIN).generation().hasKey());
      assertFalse(service.get(ADMIN).toString().contains("synthetic-key"));
    }
  }

  private static ModelConfigurationService service(
      ManagedTextTestFixture fixture, ModelConfigurationRepository repository) {
    return new ModelConfigurationService(
        fixture.context.authority.store(),
        repository,
        new ModelConfigurationPermissionPolicy("org-main", Set.of("operator")),
        fixture.runtime,
        new TextModelConnectionProbe(
            URI.create("https://synthetic.invalid/v1"), Duration.ofSeconds(1), 4096, false, null));
  }

  private static SaveModelConfigurationCommand command(long version, String key) {
    return new SaveModelConfigurationCommand(
        version,
        new SaveModelConfigurationCommand.EmbeddingInput("test/embedding", 2, "embedding-v1", key),
        new SaveModelConfigurationCommand.RoleInput("test/rerank", key),
        new SaveModelConfigurationCommand.RoleInput("test/generation", key));
  }
}
