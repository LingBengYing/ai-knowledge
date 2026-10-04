package com.evidence.rag.config;

import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.model.TextModelConnectionProbe;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.TextIndexAnchor;
import com.evidence.rag.model.domain.TextModelConfiguration;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;

/** Trusted server addresses and limits; browser configuration supplies only model roles. */
final class ManagedTextSettings {
  private final ConfigurableEnvironment environment;
  private final URI provider;
  private final URI deepseekProvider;
  private final Duration deadline;
  private final int maxBytes;
  private final boolean loopback;
  private final Set<String> administrators;

  ManagedTextSettings(ConfigurableEnvironment environment) {
    this.environment = environment;
    try {
      String bind = environment.getProperty("server.address");
      String mode = environment.getProperty("rag.environment", "development");
      if (!("127.0.0.1".equals(bind) || "::1".equals(bind))
          || !("development".equals(mode) || "test".equals(mode))) {
        throw invalid();
      }
      var allowed =
          Set.of(
              "ENABLED",
              "ADMINISTRATORS",
              "PROVIDER_BASE_URL",
              "DEEPSEEK_BASE_URL",
              "DEADLINE_MS",
              "MAX_RESPONSE_BYTES",
              "ALLOW_LOOPBACK_HTTP",
              "RETRIEVAL_TIMEOUT_MS",
              "RETRIEVAL_MAX_CONCURRENT");
      for (var source : environment.getPropertySources()) {
        if (source instanceof EnumerablePropertySource<?> enumerable) {
          for (String name : enumerable.getPropertyNames()) {
            if (name.startsWith("RAG_MODEL_CONFIGURATION_")
                && !allowed.contains(name.substring("RAG_MODEL_CONFIGURATION_".length()))) {
              throw invalid();
            }
          }
        }
      }
      provider =
          URI.create(
              environment.getProperty(
                  "rag.model-configuration.provider-base-url", "https://api.siliconflow.cn/v1"));
      String legacyGeneration = environment.getProperty("RAG_GENERATION_BASE_URL");
      String configuredDeepseek =
          environment.getProperty("rag.model-configuration.deepseek-base-url", "");
      deepseekProvider =
          !configuredDeepseek.isEmpty()
              ? URI.create(configuredDeepseek)
              : TextIndexAnchor.isDeepSeekEndpoint(legacyGeneration)
                  ? URI.create(legacyGeneration)
                  : TextModelConnectionProbe.DEEPSEEK_BASE_URL;
      deadline =
          Duration.ofMillis(
              environment.getProperty("rag.model-configuration.deadline-ms", Long.class, 30000L));
      maxBytes =
          environment.getProperty(
              "rag.model-configuration.max-response-bytes", Integer.class, 4194304);
      loopback =
          environment.getProperty(
              "rag.model-configuration.allow-loopback-http", Boolean.class, false);
      administrators =
          Arrays.stream(
                  environment
                      .getProperty("rag.model-configuration.administrators", "")
                      .split(",", -1))
              .map(String::strip)
              .collect(Collectors.toUnmodifiableSet());
      if (administrators.isEmpty()
          || administrators.stream()
              .anyMatch(value -> !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
          || deadline.toMillis() < 1
          || deadline.toMillis() > 60000
          || maxBytes < 1024
          || maxBytes > 4194304) {
        throw invalid();
      }
      // Probe constructor validates the trusted URI without issuing a request.
      new TextModelConnectionProbe(provider, deadline, maxBytes, loopback, null, deepseekProvider);
    } catch (RuntimeException rejected) {
      throw invalid();
    }
  }

  ConfigurableEnvironment environment() {
    return environment;
  }

  URI provider() {
    return provider;
  }

  URI deepseekProvider() {
    return deepseekProvider;
  }

  private URI provider(String name) {
    return switch (name) {
      case "siliconflow" -> provider;
      case "deepseek" -> deepseekProvider;
      default -> throw invalid();
    };
  }

  Duration deadline() {
    return deadline;
  }

  int maxBytes() {
    return maxBytes;
  }

  boolean loopback() {
    return loopback;
  }

  Set<String> administrators() {
    return administrators;
  }

  static Path privateFile(Path dataDirectory) {
    return dataDirectory.resolve("private-model-settings").resolve("text-models.json");
  }

  Map<String, String> serverValues(String workspace) {
    var values = new LinkedHashMap<String, String>();
    for (String name :
        List.of(
            "RAG_MILVUS_ENDPOINT",
            "RAG_MILVUS_TOKEN",
            "RAG_MILVUS_DATABASE",
            "RAG_MILVUS_COLLECTION")) {
      String value = environment.getProperty(name);
      if (value != null) {
        values.put(name, value);
      }
    }
    values.put("RAG_WORKSPACE_ID", workspace);
    values.put("RAG_TEXT_DEADLINE_MS", Long.toString(deadline.toMillis()));
    values.put("RAG_TEXT_MAX_RESPONSE_BYTES", Integer.toString(maxBytes));
    values.put("RAG_TEXT_ALLOW_LOOPBACK_HTTP", Boolean.toString(loopback));
    return values;
  }

  TextAdapterSettings adapters(TextModelConfiguration roles, String workspace) {
    return adapters(roles, workspace, (TextIndexAnchor) null);
  }

  TextAdapterSettings adapters(
      TextModelConfiguration roles, String workspace, TextIndexAnchor anchor) {
    return adapters(
        roles,
        workspace,
        provider(roles.embedding().provider()),
        provider(roles.rerank().provider()),
        provider(roles.generation().provider()),
        anchor == null ? null : anchor.projectionCollection());
  }

  private TextAdapterSettings adapters(
      TextModelConfiguration roles,
      String workspace,
      URI embeddingProvider,
      URI rerankProvider,
      URI generationProvider,
      String collection) {
    if (projection() == null) {
      throw new ApplicationException(
          FailureKind.UNAVAILABLE, "projection_configuration_required", "请由管理员先配置知识索引连接。");
    }
    var values = serverValues(workspace);
    if (collection != null) {
      values.put("RAG_MILVUS_COLLECTION", collection);
    }
    values.put("RAG_EMBEDDING_BASE_URL", embeddingProvider.toASCIIString());
    values.put("RAG_EMBEDDING_MODEL", roles.embedding().model());
    values.put("RAG_EMBEDDING_API_KEY", roles.embedding().apiKey());
    values.put("RAG_EMBEDDING_DIMENSIONS", Integer.toString(roles.embedding().dimensions()));
    values.put("RAG_EMBEDDING_REVISION", roles.embedding().revision());
    values.put("RAG_RERANK_BASE_URL", rerankProvider.toASCIIString());
    values.put("RAG_RERANK_MODEL", roles.rerank().model());
    values.put("RAG_RERANK_API_KEY", roles.rerank().apiKey());
    values.put("RAG_GENERATION_BASE_URL", generationProvider.toASCIIString());
    values.put("RAG_GENERATION_MODEL", roles.generation().model());
    values.put("RAG_GENERATION_API_KEY", roles.generation().apiKey());
    return TextAdapterSettings.load(values);
  }

  TextAdapterSettings indexAdapters(
      TextModelConfiguration roles, TextIndexAnchor anchor, String workspace) {
    var actual = adapters(roles, workspace, anchor);
    if (anchor == null) {
      return actual;
    }
    if (!anchor.providerBaseUrl().equals(provider(roles.embedding().provider()).toASCIIString())
        || !anchor.embeddingModel().equals(roles.embedding().model())
        || !anchor.embeddingRevision().equals(roles.embedding().revision())
        || anchor.dimensions() != roles.embedding().dimensions()) {
      throw rebuildRequired();
    }
    // Keep all original endpoint/name bytes in the index identity. Indexing only embeds,
    // so current answer-role credentials never cause a call to an old generation/rerank role.
    var indexingRoles =
        new TextModelConfiguration(
            roles.embedding(),
            new TextModelConfiguration.Role(
                anchor.rerankModel(), roles.rerank().apiKey(), "siliconflow"),
            new TextModelConfiguration.Role(
                anchor.generationModel(),
                roles.generation().apiKey(),
                TextIndexAnchor.isDeepSeekEndpoint(anchor.generationProviderBaseUrl())
                    ? "deepseek"
                    : "siliconflow"));
    var indexed =
        adapters(
            indexingRoles,
            workspace,
            URI.create(anchor.providerBaseUrl()),
            URI.create(anchor.rerankProviderBaseUrl()),
            URI.create(anchor.generationProviderBaseUrl()),
            anchor.projectionCollection());
    if (!anchor.target().equals(indexTarget(indexed))) {
      throw rebuildRequired();
    }
    return indexed;
  }

  TextIndexAnchor anchor(long version, TextModelConfiguration roles, TextAdapterSettings indexed) {
    return new TextIndexAnchor(
        version,
        indexed.models().embedding().baseUrl().toASCIIString(),
        roles.embedding().model(),
        roles.embedding().revision(),
        roles.embedding().dimensions(),
        roles.rerank().model(),
        roles.generation().model(),
        indexTarget(indexed),
        indexed.models().rerank().baseUrl().toASCIIString(),
        indexed.models().generation().baseUrl().toASCIIString());
  }

  TextIndexAnchor rebuildAnchor(long version, TextModelConfiguration roles, String workspace) {
    var original = adapters(roles, workspace);
    String suffix =
        ModelValues.sha256(original.projection().identity().getBytes(StandardCharsets.UTF_8))
            .substring(0, 32);
    String collection = "java_text_v" + version + "_" + suffix;
    var indexed =
        adapters(
            roles,
            workspace,
            provider(roles.embedding().provider()),
            provider(roles.rerank().provider()),
            provider(roles.generation().provider()),
            collection);
    return new TextIndexAnchor(
        version,
        indexed.models().embedding().baseUrl().toASCIIString(),
        roles.embedding().model(),
        roles.embedding().revision(),
        roles.embedding().dimensions(),
        roles.rerank().model(),
        roles.generation().model(),
        indexTarget(indexed),
        indexed.models().rerank().baseUrl().toASCIIString(),
        indexed.models().generation().baseUrl().toASCIIString(),
        collection);
  }

  static IndexTarget indexTarget(TextAdapterSettings indexed) {
    try (var models = new OpenAiCompatibleModels(indexed.models())) {
      return new IndexTarget(
          indexed.projection().embeddingIdentity(),
          indexed.projection().identity(),
          models.revision(),
          indexed.projection().dimension());
    }
  }

  private static ApplicationException rebuildRequired() {
    return new ApplicationException(
        FailureKind.CONFLICT, "model_rebuild_required", "现有索引或任务与配置不兼容，需要专门的重建操作；当前配置已保留。");
  }

  TextModelConnectionProbe.Projection projection() {
    String endpoint = environment.getProperty("RAG_MILVUS_ENDPOINT");
    String collection = environment.getProperty("RAG_MILVUS_COLLECTION");
    String token = environment.getProperty("RAG_MILVUS_TOKEN");
    if (endpoint == null
        || endpoint.isBlank()
        || collection == null
        || collection.isBlank()
        || token == null
        || token.isBlank()) {
      return null;
    }
    try {
      return new TextModelConnectionProbe.Projection(
          URI.create(endpoint),
          token,
          environment.getProperty("RAG_MILVUS_DATABASE", "default"),
          collection);
    } catch (RuntimeException rejected) {
      throw invalid();
    }
  }

  static TextAdapterSettings legacy(ConfigurableEnvironment environment, String workspace) {
    return IndexingConfiguration.loadAdapters(environment, workspace);
  }

  static TextModelConfiguration bootstrap(ConfigurableEnvironment environment) {
    // Absence means first setup; partial input is never made into a placeholder model.
    var names =
        List.of(
            "RAG_EMBEDDING_MODEL",
            "RAG_EMBEDDING_API_KEY",
            "RAG_EMBEDDING_DIMENSIONS",
            "RAG_EMBEDDING_REVISION",
            "RAG_RERANK_MODEL",
            "RAG_RERANK_API_KEY",
            "RAG_GENERATION_MODEL",
            "RAG_GENERATION_API_KEY");
    if (names.stream()
        .anyMatch(
            name ->
                environment.getProperty(name) == null || environment.getProperty(name).isBlank())) {
      return null;
    }
    try {
      return new TextModelConfiguration(
          new TextModelConfiguration.Embedding(
              environment.getProperty("RAG_EMBEDDING_MODEL"),
              environment.getProperty("RAG_EMBEDDING_API_KEY"),
              Integer.parseInt(environment.getProperty("RAG_EMBEDDING_DIMENSIONS")),
              environment.getProperty("RAG_EMBEDDING_REVISION"),
              bootstrapProvider(environment, "EMBEDDING")),
          new TextModelConfiguration.Role(
              environment.getProperty("RAG_RERANK_MODEL"),
              environment.getProperty("RAG_RERANK_API_KEY"),
              bootstrapProvider(environment, "RERANK")),
          new TextModelConfiguration.Role(
              environment.getProperty("RAG_GENERATION_MODEL"),
              environment.getProperty("RAG_GENERATION_API_KEY"),
              bootstrapProvider(environment, "GENERATION")));
    } catch (RuntimeException rejected) {
      throw invalid();
    }
  }

  private static String bootstrapProvider(ConfigurableEnvironment environment, String role) {
    String value = environment.getProperty("RAG_" + role + "_BASE_URL");
    if (value == null || value.isBlank()) {
      return "siliconflow";
    }
    if (TextIndexAnchor.isDeepSeekEndpoint(value)) {
      if (!"GENERATION".equals(role)) {
        throw invalid();
      }
      return "deepseek";
    }
    String configured =
        environment.getProperty(
            "rag.model-configuration.provider-base-url", "https://api.siliconflow.cn/v1");
    if (value.equals(configured)
        || Set.of(
                "https://api.siliconflow.cn",
                "https://api.siliconflow.cn/",
                "https://api.siliconflow.cn/v1",
                "https://api.siliconflow.cn/v1/",
                "https://api.siliconflow.com",
                "https://api.siliconflow.com/",
                "https://api.siliconflow.com/v1",
                "https://api.siliconflow.com/v1/")
            .contains(value)) {
      return "siliconflow";
    }
    throw invalid();
  }

  static boolean legacyAvailable(ConfigurableEnvironment environment) {
    if (!environment.getProperty("rag.model-configuration.enabled", Boolean.class, false)) {
      return true;
    }
    try {
      legacy(environment, environment.getProperty("rag.workspace-id", "org-main"));
      return true;
    } catch (RuntimeException missing) {
      return false;
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid managed text server configuration");
  }

  @Override
  public String toString() {
    return "ManagedTextSettings[redacted]";
  }
}
