package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.job.DocumentCleanupJob;
import com.evidence.rag.repository.DocumentLifecycleRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.DocumentCleanupService;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class DocumentCleanupConfigurationTest {
  @TempDir Path directory;

  @Test
  void disabledCleanupHasNoExecutorOrServiceEvenOutsideLoopback() {
    try (var context = configured(false, false, "0.0.0.0", "test")) {
      context.refresh();
      assertFalse(context.getBean(DocumentCleanupSettings.class).enabled());
      assertTrue(context.getBeansOfType(DocumentCleanupService.class).isEmpty());
      assertTrue(context.getBeansOfType(DocumentCleanupJob.class).isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"127.0.0.1", "::1"})
  void explicitLocalCleanupHasAnActualExecutorWithoutModelConfiguration(String address) {
    try (var context = configured(true, true, address, "test")) {
      context.refresh();
      assertTrue(context.getBean(DocumentCleanupSettings.class).enabled());
      assertNotNull(context.getBean(DocumentCleanupService.class));
      assertNotNull(context.getBean(DocumentCleanupJob.class));
      assertTrue(context.getBeansOfType(TextModels.class).isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"localhost", "0.0.0.0", "::", "192.0.2.1", ""})
  void enabledCleanupRejectsNonliteralLoopback(String address) {
    try (var context = configured(true, true, address, "test")) {
      assertThrows(RuntimeException.class, context::refresh);
    }
  }

  @Test
  void cleanupRequiresRemovalAndLocalDevelopmentEnvironment() {
    for (var values :
        java.util.List.of(new Object[] {false, "test"}, new Object[] {true, "production"})) {
      try (var context = configured(true, (Boolean) values[0], "127.0.0.1", (String) values[1])) {
        assertThrows(RuntimeException.class, context::refresh);
      }
    }
  }

  private AnnotationConfigApplicationContext configured(
      boolean enabled, boolean removal, String address, String runtime) {
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "synthetic-cleanup",
                Map.of("rag.document-cleanup.enabled", enabled, "server.address", address)));
    var context = new AnnotationConfigApplicationContext();
    context.setEnvironment(environment);
    context.register(DocumentCleanupConfiguration.class);
    context.registerBean(DocumentRemovalSettings.class, () -> new DocumentRemovalSettings(removal));
    context.registerBean(
        RagProperties.class,
        () ->
            new RagProperties(
                runtime,
                "org-main",
                "jwt",
                "synthetic-cleanup-config".repeat(3),
                "issuer",
                "audience",
                directory));
    context.registerBean(SqliteAuthorityStore.class, () -> new SqliteAuthorityStore(directory));
    context.registerBean(
        DocumentLifecycleRepository.class,
        () -> new DocumentLifecycleRepository(context.getBean(SqliteAuthorityStore.class)));
    context.registerBean(
        ManagementRepository.class,
        () -> new ManagementRepository(context.getBean(SqliteAuthorityStore.class)));
    context.registerBean(DocumentPermissionPolicy.class, DocumentPermissionPolicy::new);
    return context;
  }
}
