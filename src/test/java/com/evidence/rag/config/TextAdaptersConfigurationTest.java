package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.job.IndexingJob;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class TextAdaptersConfigurationTest {
  @Test
  void independentFlagsLoadExactlyOneSettingsBeanWithoutAnyStartupNetwork() throws Exception {
    var requests = new AtomicInteger();
    var remote = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    remote.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          exchange.sendResponseHeaders(503, -1);
          exchange.close();
        });
    remote.start();
    try {
      for (boolean index : List.of(false, true)) {
        for (boolean answers : List.of(false, true)) {
          var values = valid("http://127.0.0.1:" + remote.getAddress().getPort());
          values.put("rag.indexing.enabled", Boolean.toString(index));
          values.put("rag.answers.enabled", Boolean.toString(answers));
          try (var context = context(values)) {
            context.refresh();
            assertEquals(
                index || answers ? 1 : 0, context.getBeansOfType(TextAdapterSettings.class).size());
            assertEquals(
                answers ? 1 : 0, context.getBeansOfType(OpenAiCompatibleModels.class).size());
            assertEquals(
                answers ? 1 : 0, context.getBeansOfType(MilvusRestProjection.class).size());
            assertTrue(context.getBeansOfType(IndexingJob.class).isEmpty());
            if (index || answers) {
              assertEquals(
                  "org-main",
                  context.getBean(TextAdapterSettings.class).projection().workspaceId());
              assertFalse(
                  context
                      .getBean(TextAdapterSettings.class)
                      .toString()
                      .contains("synthetic-config-credential"));
            }
            assertEquals(
                0, requests.get(), "Explicit configuration must not prepare or initialize clients");
          }
        }
      }
    } finally {
      remote.stop(0);
    }
  }

  @Test
  void disabledOrMissingFlagsDoNotRequireModelConfiguration() {
    for (var values :
        List.<Map<String, Object>>of(
            Map.of(), Map.of("rag.indexing.enabled", "false", "rag.answers.enabled", "false"))) {
      try (var context = context(values)) {
        context.refresh();
        assertTrue(context.getBeansOfType(TextAdapterSettings.class).isEmpty());
        assertTrue(context.getBeansOfType(OpenAiCompatibleModels.class).isEmpty());
        assertTrue(context.getBeansOfType(MilvusRestProjection.class).isEmpty());
      }
    }
  }

  @Test
  void answersOnlyRejectsMissingConfigurationAndNonliteralLoopbackWithoutSecretDetails() {
    var missing = valid("http://127.0.0.1:9");
    missing.put("rag.answers.enabled", "true");
    missing.remove("RAG_GENERATION_API_KEY");
    assertInvalid(missing);
    for (String bind : List.of("localhost", "0.0.0.0", "::", "127.0.0.2")) {
      var values = valid("http://127.0.0.1:9");
      values.put("rag.answers.enabled", "true");
      values.put("server.address", bind);
      assertInvalid(values);
    }
  }

  private static void assertInvalid(Map<String, Object> values) {
    try (var context = context(values)) {
      var failure = assertThrows(RuntimeException.class, context::refresh);
      assertFalse(failure.toString().contains("synthetic-config-credential"));
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
    environment.getPropertySources().addFirst(new MapPropertySource("synthetic", values));
    var context = new AnnotationConfigApplicationContext();
    context.setEnvironment(environment);
    context.registerBean(
        RagProperties.class,
        () ->
            new RagProperties(
                "test",
                "org-main",
                "jwt",
                "synthetic-configuration-jwt-credential",
                "issuer",
                "audience",
                Path.of("unused-config-data")));
    context.register(TextAdaptersConfiguration.class);
    return context;
  }

  private static LinkedHashMap<String, Object> valid(String endpoint) {
    var values = new LinkedHashMap<String, Object>();
    values.put("server.address", "127.0.0.1");
    for (String kind : List.of("EMBEDDING", "RERANK", "GENERATION")) {
      values.put("RAG_" + kind + "_BASE_URL", endpoint);
      values.put("RAG_" + kind + "_MODEL", "fixture-model");
      values.put("RAG_" + kind + "_API_KEY", "synthetic-config-credential");
    }
    values.put("RAG_EMBEDDING_DIMENSIONS", "2");
    values.put("RAG_EMBEDDING_REVISION", "fixture-v1");
    values.put("RAG_MILVUS_ENDPOINT", endpoint);
    values.put("RAG_MILVUS_TOKEN", "synthetic-config-credential");
    values.put("RAG_MILVUS_COLLECTION", "java_answer_configuration");
    values.put("RAG_TEXT_ALLOW_LOOPBACK_HTTP", "true");
    values.put("RAG_WORKSPACE_ID", "untrusted-org-override");
    return values;
  }
}
