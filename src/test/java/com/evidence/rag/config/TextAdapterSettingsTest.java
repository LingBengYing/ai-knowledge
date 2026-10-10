package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TextAdapterSettingsTest {
  @Test
  void chineseAnalyzerHasAnIndependentProjectionWithoutChangingEmbeddingIdentity() {
    var env = valid();
    var legacy = TextAdapterSettings.load(env);
    env.put("RAG_MILVUS_ANALYZER", "chinese");
    var chinese = assertDoesNotThrow(() -> TextAdapterSettings.load(env));
    assertNotEquals(legacy.projection().identity(), chinese.projection().identity());
    assertEquals(legacy.projection().embeddingIdentity(), chinese.projection().embeddingIdentity());
    env.put("RAG_MILVUS_ANALYZER", "standard");
    assertEquals(
        legacy.projection().identity(), TextAdapterSettings.load(env).projection().identity());
    for (String invalid : List.of("", "Chinese", "jieba", "unknown")) {
      env.put("RAG_MILVUS_ANALYZER", invalid);
      assertFailure(env);
    }
  }

  @Test
  void explicitlyLoadsIndependentEndpointsAndSecretsWithoutContactingThem() {
    var env = valid();
    var config = TextAdapterSettings.load(env);
    assertEquals(
        "https://embedding.example.invalid/v1", config.models().embedding().baseUrl().toString());
    assertEquals(
        "https://rerank.example.invalid/v1", config.models().rerank().baseUrl().toString());
    assertEquals(
        "https://generation.example.invalid/v1", config.models().generation().baseUrl().toString());
    assertEquals(env.get("RAG_EMBEDDING_API_KEY"), config.models().embedding().apiKey());
    assertEquals(env.get("RAG_RERANK_API_KEY"), config.models().rerank().apiKey());
    assertEquals(env.get("RAG_GENERATION_API_KEY"), config.models().generation().apiKey());
    assertEquals(1024, config.models().embeddingDimensions());
    assertEquals(1024, config.projection().dimension());
    assertEquals("org-main", config.projection().workspaceId());
    assertEquals("default", config.projection().database());
    assertEquals("java_text_test", config.projection().collection());
    assertEquals(Duration.ofSeconds(30), config.models().deadline());
    assertEquals(Duration.ofSeconds(30), config.projection().timeout());
    assertEquals(4 * 1024 * 1024, config.models().maxResponseBytes());
    assertFalse(config.models().allowLoopbackHttp());
    assertFalse(config.projection().allowLoopbackHttp());
    for (String sensitive :
        List.of(
            "https://",
            "fixture-embedding",
            "fixture-rerank",
            "fixture-generation",
            "fixture-milvus")) {
      assertFalse(config.toString().contains(sensitive));
      assertFalse(config.models().toString().contains(sensitive));
      assertFalse(config.projection().toString().contains(sensitive));
    }
    env.clear();
    assertEquals(1024, config.models().embeddingDimensions());
  }

  @Test
  void requiresEveryConnectionAndPinnedModelValueRatherThanFallingBack() {
    for (String key :
        List.of(
            "RAG_EMBEDDING_BASE_URL",
            "RAG_EMBEDDING_MODEL",
            "RAG_EMBEDDING_API_KEY",
            "RAG_EMBEDDING_DIMENSIONS",
            "RAG_EMBEDDING_REVISION",
            "RAG_RERANK_BASE_URL",
            "RAG_RERANK_MODEL",
            "RAG_RERANK_API_KEY",
            "RAG_GENERATION_BASE_URL",
            "RAG_GENERATION_MODEL",
            "RAG_GENERATION_API_KEY",
            "RAG_MILVUS_ENDPOINT",
            "RAG_MILVUS_TOKEN",
            "RAG_MILVUS_COLLECTION",
            "RAG_WORKSPACE_ID")) {
      var env = valid();
      env.remove(key);
      assertFailure(env);
      env.put(key, "  ");
      assertFailure(env);
    }
    assertThrows(TextAdapterSettings.Invalid.class, () -> TextAdapterSettings.load(null));
  }

  @Test
  void rejectsTyposButIgnoresUnrelatedProcessEnvironment() {
    for (String key :
        List.of(
            "RAG_EMBEDDING_DIMENTIONS",
            "RAG_RERANK_KEY",
            "RAG_GENERATION_TOKEN",
            "RAG_MILVUS_DATABASES",
            "RAG_TEXT_ALLOW_HTTP")) {
      var env = valid();
      env.put(key, "value");
      assertFailure(env);
    }
    var env = valid();
    env.put("PATH", "/usr/bin");
    env.put("RAG_AUTH_MODE", "jwt");
    assertDoesNotThrow(() -> TextAdapterSettings.load(env));
  }

  @Test
  void validatesNumbersFlagsEndpointsAndKeysWithoutLeakingInputOrCauses() {
    for (String key :
        List.of(
            "RAG_EMBEDDING_DIMENSIONS", "RAG_TEXT_DEADLINE_MS", "RAG_TEXT_MAX_RESPONSE_BYTES")) {
      for (String value :
          List.of("", "1.5", "-1", "0", "99999999999999", "sensitive-invalid-input")) {
        var env = valid();
        env.put(key, value);
        assertFailure(env);
      }
    }
    for (String key :
        List.of(
            "RAG_EMBEDDING_BASE_URL",
            "RAG_RERANK_BASE_URL",
            "RAG_GENERATION_BASE_URL",
            "RAG_MILVUS_ENDPOINT")) {
      for (String value :
          List.of(
              "not a URI",
              "https://user:password@example.invalid",
              "https://example.invalid?key=private",
              "http://example.invalid",
              "https://example.invalid/#private")) {
        var env = valid();
        env.put(key, value);
        assertFailure(env);
      }
    }
    for (String key :
        List.of(
            "RAG_EMBEDDING_API_KEY",
            "RAG_RERANK_API_KEY",
            "RAG_GENERATION_API_KEY",
            "RAG_MILVUS_TOKEN")) {
      for (String value :
          List.of("replace-me", "your-key", "example-secret", "changeme", "newline\nvalue")) {
        var env = valid();
        env.put(key, value);
        assertFailure(env);
      }
    }
    for (String value : List.of("yes", "1", "TRUE", "")) {
      var env = valid();
      env.put("RAG_TEXT_ALLOW_LOOPBACK_HTTP", value);
      assertFailure(env);
    }
    var env = valid();
    env.put("RAG_MILVUS_COLLECTION", "old_python_collection");
    assertFailure(env);
  }

  @Test
  void explicitLoopbackTestOptionAndBoundsAreSharedAcrossBothAdapters() {
    var env = valid();
    env.put("RAG_TEXT_ALLOW_LOOPBACK_HTTP", "true");
    env.put("RAG_EMBEDDING_BASE_URL", "http://127.0.0.1:1/v1");
    env.put("RAG_MILVUS_ENDPOINT", "http://127.0.0.1:2");
    env.put("RAG_TEXT_DEADLINE_MS", "1000");
    env.put("RAG_TEXT_MAX_RESPONSE_BYTES", "2048");
    env.put("RAG_MILVUS_DATABASE", "staging");
    var config = TextAdapterSettings.load(env);
    assertTrue(config.models().allowLoopbackHttp());
    assertTrue(config.projection().allowLoopbackHttp());
    assertEquals(Duration.ofSeconds(1), config.models().deadline());
    assertEquals(Duration.ofSeconds(1), config.projection().timeout());
    assertEquals(2048, config.models().maxResponseBytes());
    assertEquals(2048, config.projection().maxResponseBytes());
    assertEquals("staging", config.projection().database());
    env.put("RAG_TEXT_ALLOW_LOOPBACK_HTTP", "false");
    assertFailure(env);
    env.put("RAG_TEXT_ALLOW_LOOPBACK_HTTP", "true");
    env.put("RAG_MILVUS_ENDPOINT", "http://localhost:2");
    assertFailure(env);
  }

  @Test
  void pinsEmbeddingIdentityToEndpointModelRevisionAndDimensionButNotCredentials() {
    var baseline = TextAdapterSettings.load(valid());
    var identity = baseline.projection().embeddingIdentity();
    assertTrue(identity.matches("sha256:[0-9a-f]{64}"));
    for (var change :
        Map.of(
                "RAG_EMBEDDING_BASE_URL",
                "https://different.example.invalid/v1",
                "RAG_EMBEDDING_MODEL",
                "different/model",
                "RAG_EMBEDDING_REVISION",
                "test-revision-2",
                "RAG_EMBEDDING_DIMENSIONS",
                "2048")
            .entrySet()) {
      var env = valid();
      env.put(change.getKey(), change.getValue());
      assertNotEquals(identity, TextAdapterSettings.load(env).projection().embeddingIdentity());
    }
    var rotated = valid();
    rotated.put("RAG_EMBEDDING_API_KEY", "rotated-fixture-value");
    rotated.put("RAG_RERANK_MODEL", "different/reranker");
    assertEquals(identity, TextAdapterSettings.load(rotated).projection().embeddingIdentity());
    for (String revision :
        List.of("latest", "LATEST", "unknown", "default", "not pinned", "x".repeat(161))) {
      var env = valid();
      env.put("RAG_EMBEDDING_REVISION", revision);
      assertFailure(env);
    }
    for (var change :
        Map.of(
                "RAG_EMBEDDING_DIMENSIONS",
                "8193",
                "RAG_TEXT_DEADLINE_MS",
                "60001",
                "RAG_TEXT_MAX_RESPONSE_BYTES",
                "4194305")
            .entrySet()) {
      var env = valid();
      env.put(change.getKey(), change.getValue());
      assertFailure(env);
    }
    assertThrows(
        TextAdapterSettings.Invalid.class,
        () -> new TextAdapterSettings(null, baseline.projection()));
    assertThrows(
        TextAdapterSettings.Invalid.class, () -> new TextAdapterSettings(baseline.models(), null));
    var changed = valid();
    changed.put("RAG_EMBEDDING_DIMENSIONS", "2048");
    assertThrows(
        TextAdapterSettings.Invalid.class,
        () ->
            new TextAdapterSettings(
                baseline.models(), TextAdapterSettings.load(changed).projection()));
  }

  static Map<String, String> valid() {
    var env = new HashMap<String, String>();
    for (String kind : List.of("EMBEDDING", "RERANK", "GENERATION")) {
      env.put(
          "RAG_" + kind + "_BASE_URL",
          "https://" + kind.toLowerCase(java.util.Locale.ROOT) + ".example.invalid/v1");
      env.put("RAG_" + kind + "_MODEL", "test/" + kind);
      env.put("RAG_" + kind + "_API_KEY", "fixture-" + kind.toLowerCase(java.util.Locale.ROOT));
    }
    env.put("RAG_EMBEDDING_DIMENSIONS", "1024");
    env.put("RAG_EMBEDDING_REVISION", "test-embedding-revision-1");
    env.put("RAG_MILVUS_ENDPOINT", "https://milvus.example.invalid");
    env.put("RAG_MILVUS_TOKEN", "fixture-milvus");
    env.put("RAG_MILVUS_COLLECTION", "java_text_test");
    env.put("RAG_WORKSPACE_ID", "org-main");
    return env;
  }

  private static void assertFailure(Map<String, String> env) {
    var error =
        assertThrows(TextAdapterSettings.Invalid.class, () -> TextAdapterSettings.load(env));
    assertEquals("Invalid text adapter configuration", error.getMessage());
    assertNull(error.getCause());
    assertEquals(0, error.getSuppressed().length);
  }
}
