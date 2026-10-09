package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.RetrievalSettings;
import com.evidence.rag.repository.RetrievalSettingsRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.service.RetrievalSettingsService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class RetrievalSettingsConfigurationTest {
  @TempDir Path directory;

  @Test
  void disabledManagedConfigurationDoesNotCreateSettingsBeansOrFiles() {
    try (var context = context(Map.of())) {
      context.refresh();
      assertTrue(context.getBeansOfType(RetrievalSettingsRepository.class).isEmpty());
      assertTrue(context.getBeansOfType(RetrievalSettingsService.class).isEmpty());
      assertFalse(Files.exists(directory.resolve("retrieval-settings.json")));
    }
  }

  @Test
  void enabledConfigurationUsesAuthoritySiblingAndReadsWithoutWriting() {
    try (var context = context(Map.of("rag.model-configuration.enabled", "true"))) {
      context.registerBean(
          RagProperties.class,
          () ->
              new RagProperties("test", "org", "development_headers", null, null, null, directory));
      context.registerBean(SqliteAuthorityStore.class, () -> new SqliteAuthorityStore(directory));
      context.refresh();
      var store = context.getBean(SqliteAuthorityStore.class);
      Path file = store.libraryPath().getParent().resolve("retrieval-settings.json");
      var service = context.getBean(RetrievalSettingsService.class);
      assertEquals(RetrievalSettings.defaults(), service.read(new Actor("org", "reader")));
      assertFalse(Files.exists(file));
      var saved = service.save(new Actor("org", "reader"), RetrievalSettings.defaults());
      assertTrue(Files.isRegularFile(file));
      assertEquals(saved, new RetrievalSettingsRepository(file, "org").read());
    }
  }

  private static AnnotationConfigApplicationContext context(Map<String, Object> values) {
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .addFirst(new MapPropertySource("synthetic-retrieval-settings", values));
    var context = new AnnotationConfigApplicationContext();
    context.setEnvironment(environment);
    context.register(RetrievalSettingsConfiguration.class);
    return context;
  }
}
