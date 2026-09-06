package com.evidence.rag.config;

import com.evidence.rag.models.OpenAiCompatibleModels;
import com.evidence.rag.retrieval.MilvusRestProjection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Explicit configuration only: does not create clients, Spring beans, or remote resources. */
public record TextAdapterSettings(
    OpenAiCompatibleModels.Configuration models, MilvusRestProjection.Settings projection) {
  private static final Set<String> NAMES =
      Set.of(
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
          "RAG_MILVUS_DATABASE",
          "RAG_MILVUS_COLLECTION",
          "RAG_TEXT_DEADLINE_MS",
          "RAG_TEXT_MAX_RESPONSE_BYTES",
          "RAG_TEXT_ALLOW_LOOPBACK_HTTP");
  private static final List<String> PREFIXES =
      List.of("RAG_EMBEDDING_", "RAG_RERANK_", "RAG_GENERATION_", "RAG_MILVUS_", "RAG_TEXT_");

  public TextAdapterSettings {
    if (models == null
        || projection == null
        || models.embeddingDimensions() != projection.dimension()) throw new Invalid();
  }

  public static TextAdapterSettings load(Map<String, String> environment) {
    try {
      if (environment == null) throw new Invalid();
      for (var name : environment.keySet()) {
        if (name == null || (PREFIXES.stream().anyMatch(name::startsWith) && !NAMES.contains(name)))
          throw new Invalid();
      }
      var embedding = endpoint(environment, "EMBEDDING");
      var rerank = endpoint(environment, "RERANK");
      var generation = endpoint(environment, "GENERATION");
      int dimensions = number(required(environment, "RAG_EMBEDDING_DIMENSIONS"), 2, 8192);
      var timeout =
          Duration.ofMillis(
              number(environment.getOrDefault("RAG_TEXT_DEADLINE_MS", "30000"), 1, 60000));
      int bytes =
          number(environment.getOrDefault("RAG_TEXT_MAX_RESPONSE_BYTES", "4194304"), 1024, 4194304);
      boolean loopback = flag(environment.getOrDefault("RAG_TEXT_ALLOW_LOOPBACK_HTTP", "false"));
      String revision = required(environment, "RAG_EMBEDDING_REVISION");
      if (!revision.matches("[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,159}")
          || Set.of("latest", "default", "unknown").contains(revision.toLowerCase(Locale.ROOT)))
        throw new Invalid();
      var models =
          new OpenAiCompatibleModels.Configuration(
              embedding, rerank, generation, dimensions, timeout, bytes, loopback);
      var projection =
          new MilvusRestProjection.Settings(
              URI.create(required(environment, "RAG_MILVUS_ENDPOINT")),
              secret(environment, "RAG_MILVUS_TOKEN"),
              environment.getOrDefault("RAG_MILVUS_DATABASE", "default"),
              required(environment, "RAG_MILVUS_COLLECTION"),
              required(environment, "RAG_WORKSPACE_ID"),
              embeddingIdentity(embedding, revision, dimensions),
              dimensions,
              timeout,
              bytes,
              loopback);
      return new TextAdapterSettings(models, projection);
    } catch (RuntimeException ignored) {
      // Input, URI and downstream validation errors may contain private values. Never chain them.
      throw new Invalid();
    }
  }

  private static OpenAiCompatibleModels.Endpoint endpoint(Map<String, String> env, String kind) {
    String prefix = "RAG_" + kind;
    return new OpenAiCompatibleModels.Endpoint(
        URI.create(required(env, prefix + "_BASE_URL")),
        required(env, prefix + "_MODEL"),
        secret(env, prefix + "_API_KEY"));
  }

  private static String required(Map<String, String> env, String key) {
    var value = env.get(key);
    if (value == null
        || value.isBlank()
        || value.length() > 4096
        || !value.equals(value.strip())
        || value.codePoints().anyMatch(c -> c < 32 || c == 127)) throw new Invalid();
    return value;
  }

  private static String secret(Map<String, String> env, String key) {
    var value = required(env, key);
    var lower = value.toLowerCase(Locale.ROOT);
    if (List.of("replace-", "your-", "example-", "changeme").stream().anyMatch(lower::startsWith))
      throw new Invalid();
    return value;
  }

  private static int number(String value, int minimum, int maximum) {
    if (value == null || !value.matches("[0-9]{1,8}")) throw new Invalid();
    int number = Integer.parseInt(value);
    if (number < minimum || number > maximum) throw new Invalid();
    return number;
  }

  private static boolean flag(String value) {
    if ("true".equals(value)) return true;
    if ("false".equals(value)) return false;
    throw new Invalid();
  }

  private static String embeddingIdentity(
      OpenAiCompatibleModels.Endpoint endpoint, String revision, int dimensions) {
    String value =
        endpoint.baseUrl() + "\n" + endpoint.model() + "\n" + revision + "\n" + dimensions;
    try {
      return "sha256:"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new Invalid();
    }
  }

  @Override
  public String toString() {
    return "TextAdapterSettings[configuration=redacted]";
  }

  public static final class Invalid extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public Invalid() {
      super("Invalid text adapter configuration");
    }
  }
}
