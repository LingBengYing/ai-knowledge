package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.repository.IndexingRepository;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.DocumentLifecycleService;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class DocumentRemovalConfigurationTest {
  @TempDir Path directory;

  @Test
  void settingsDefaultToOffWithoutCreatingTheRemovalUseCase() {
    try (var context = configured(Map.of("server.address", "0.0.0.0"))) {
      context.refresh();
      assertFalse(context.getBean(DocumentRemovalSettings.class).enabled());
      assertTrue(context.getBeansOfType(DocumentLifecycleService.class).isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"127.0.0.1", "::1"})
  void explicitLiteralLoopbackSupportsRemovalWithoutAnyModelSettings(String address) {
    try (var context =
        configured(Map.of("server.address", address, "rag.document-removal.enabled", "true"))) {
      context.refresh();
      assertTrue(context.getBean(DocumentRemovalSettings.class).enabled());
      assertEquals(1, context.getBeansOfType(DocumentLifecycleService.class).size());
      assertTrue(context.getBeansOfType(TextAdapterSettings.class).isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"localhost", "0.0.0.0", "::", "192.0.2.1", ""})
  void enabledRemovalRejectsNonliteralOrMissingLoopbackEvenWithJwtIdentity(String address) {
    var values = new LinkedHashMap<String, Object>();
    values.put("rag.document-removal.enabled", "true");
    if (!address.isEmpty()) {
      values.put("server.address", address);
    }
    try (var context = configured(values)) {
      var failure = assertThrows(RuntimeException.class, context::refresh);
      Throwable cause = failure;
      while (cause.getCause() != null) {
        cause = cause.getCause();
      }
      assertTrue(cause instanceof IllegalArgumentException);
      assertEquals(
          "Document removal requires a local development or test runtime", cause.getMessage());
    }
  }

  private AnnotationConfigApplicationContext configured(Map<String, Object> values) {
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .addFirst(new MapPropertySource("synthetic-removal-settings", values));
    var context = new AnnotationConfigApplicationContext();
    context.setEnvironment(environment);
    context.register(DocumentRemovalConfiguration.class);
    context.registerBean(
        RagProperties.class,
        () ->
            new RagProperties(
                "test",
                "org-main",
                "jwt",
                "synthetic-config-secret".repeat(3),
                "test-issuer",
                "test-audience",
                directory));
    context.registerBean(SqliteAuthorityStore.class, () -> new SqliteAuthorityStore(directory));
    context.registerBean(
        ManagementRepository.class,
        () -> new ManagementRepository(context.getBean(SqliteAuthorityStore.class)));
    context.registerBean(
        IngestionRepository.class,
        () -> new IngestionRepository(context.getBean(SqliteAuthorityStore.class)));
    context.registerBean(
        IndexingRepository.class,
        () -> new IndexingRepository(context.getBean(SqliteAuthorityStore.class)));
    context.registerBean(DocumentPermissionPolicy.class, DocumentPermissionPolicy::new);
    return context;
  }
}
