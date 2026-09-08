package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.job.IndexingJob;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class IndexingConfigurationTest {
  @Test
  void disabledOrAbsentFlagDoesNotConstructRemoteAdaptersOrRequireSecrets() {
    for (var values :
        List.<Map<String, Object>>of(Map.of(), Map.of("rag.indexing.enabled", "false"))) {
      try (var context = new AnnotationConfigApplicationContext()) {
        context.setEnvironment(environment(values));
        context.register(IndexingConfiguration.class);
        context.refresh();
        assertTrue(context.getBeansOfType(TextAdapterSettings.class).isEmpty());
        assertTrue(context.getBeansOfType(IndexingJob.class).isEmpty());
      }
    }
  }

  @Test
  void explicitSettingsUseResolvedPrecedenceFixedWorkspaceAndRedactedTarget() {
    var env = environment(valid());
    env.getPropertySources()
        .addFirst(
            new MapPropertySource("override", Map.of("RAG_EMBEDDING_MODEL", "override-model")));
    var settings = IndexingConfiguration.loadAdapters(env, "fixed-org");
    assertEquals("fixed-org", settings.projection().workspaceId());
    assertEquals("override-model", settings.models().embedding().model());
    var target = new IndexingConfiguration().indexingTarget(settings);
    assertEquals(settings.projection().identity(), target.projectionIdentity());
    assertEquals(settings.projection().embeddingIdentity(), target.embeddingIdentity());
    assertEquals(2, target.dimensions());
    assertFalse(target.toString().contains("fixture-model"));
    assertFalse(settings.toString().contains("synthetic-configuration-value"));
  }

  @Test
  void nonliteralBindingAndEveryUnknownModelPrefixFailWithoutPrivateErrorDetails() {
    for (String bind :
        new String[] {
          null, "", "0.0.0.0", "::", "localhost", "localhost.example.org", "127.0.0.2"
        }) {
      var values = valid();
      if (bind == null) {
        values.remove("server.address");
      } else {
        values.put("server.address", bind);
      }
      var failure =
          assertThrows(
              IllegalArgumentException.class,
              () -> IndexingConfiguration.loadAdapters(environment(values), "fixed-org"));
      assertNull(failure.getCause());
      assertFalse(failure.toString().contains("synthetic-configuration-value"));
    }
    for (String prefix :
        List.of("RAG_EMBEDDING_", "RAG_RERANK_", "RAG_GENERATION_", "RAG_MILVUS_", "RAG_TEXT_")) {
      var values = valid();
      values.put(prefix + "UNKNOWN", "synthetic-configuration-value");
      var failure =
          assertThrows(
              TextAdapterSettings.Invalid.class,
              () -> IndexingConfiguration.loadAdapters(environment(values), "fixed-org"));
      assertNull(failure.getCause());
      assertFalse(failure.toString().contains("synthetic-configuration-value"));
    }
    var incomplete = valid();
    incomplete.remove("RAG_EMBEDDING_API_KEY");
    assertThrows(
        TextAdapterSettings.Invalid.class,
        () -> IndexingConfiguration.loadAdapters(environment(incomplete), "fixed-org"));
    var ipv6 = valid();
    ipv6.put("server.address", "::1");
    assertDoesNotThrow(() -> IndexingConfiguration.loadAdapters(environment(ipv6), "fixed-org"));
  }

  private static StandardEnvironment environment(Map<String, Object> values) {
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    environment.getPropertySources().addFirst(new MapPropertySource("synthetic", values));
    return environment;
  }

  private static LinkedHashMap<String, Object> valid() {
    var values = new LinkedHashMap<String, Object>();
    values.put("server.address", "127.0.0.1");
    for (String kind : List.of("EMBEDDING", "RERANK", "GENERATION")) {
      values.put("RAG_" + kind + "_BASE_URL", "http://127.0.0.1:9");
      values.put("RAG_" + kind + "_MODEL", "fixture-model");
      values.put("RAG_" + kind + "_API_KEY", "synthetic-configuration-value");
    }
    values.put("RAG_EMBEDDING_DIMENSIONS", "2");
    values.put("RAG_EMBEDDING_REVISION", "fixture-v1");
    values.put("RAG_MILVUS_ENDPOINT", "http://127.0.0.1:9");
    values.put("RAG_MILVUS_TOKEN", "synthetic-configuration-value");
    values.put("RAG_MILVUS_COLLECTION", "java_index_configuration");
    values.put("RAG_TEXT_ALLOW_LOOPBACK_HTTP", "true");
    values.put("RAG_WORKSPACE_ID", "ignored-input-org");
    values.put("RAG_JWT_SECRET", "synthetic-do-not-pass-to-worker");
    return values;
  }
}
