package com.evidence.rag.model.domain;

import com.evidence.rag.exception.ModelConfigurationInputException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Complete immutable private configuration. Keys never participate in public profile identity. */
public record TextModelConfiguration(Embedding embedding, Role rerank, Role generation) {
  public TextModelConfiguration {
    Objects.requireNonNull(embedding);
    Objects.requireNonNull(rerank);
    Objects.requireNonNull(generation);
    validateProvider(rerank.provider(), "rerank.provider", false);
    validateProvider(generation.provider(), "generation.provider", true);
  }

  public record Embedding(
      String model, String apiKey, int dimensions, String revision, String provider) {
    public Embedding(String model, String apiKey, int dimensions, String revision) {
      this(model, apiKey, dimensions, revision, "siliconflow");
    }

    public Embedding {
      validateModel(model, "embedding.model");
      validateKey(apiKey, "embedding.api_key");
      validateDimensions(dimensions);
      validateRevision(revision);
      validateProvider(provider, "embedding.provider", false);
    }

    @Override
    public String toString() {
      return "Embedding[redacted]";
    }
  }

  public record Role(String model, String apiKey, String provider) {
    public Role(String model, String apiKey) {
      this(model, apiKey, "siliconflow");
    }

    public Role {
      validateModel(model, "request");
      validateKey(apiKey, "request");
      validateProvider(provider, "request", true);
    }

    @Override
    public String toString() {
      return "Role[redacted]";
    }
  }

  public static void validateBaseVersion(long version) {
    if (version < 0 || version >= ModelConfigurationState.MAX_VERSION) {
      throw new ModelConfigurationInputException("base_version");
    }
  }

  public static void validateProvider(String provider, String field, boolean allowDeepSeek) {
    if (!"siliconflow".equals(provider) && !(allowDeepSeek && "deepseek".equals(provider))) {
      throw new ModelConfigurationInputException(field);
    }
  }

  public static void validateModel(String model, String field) {
    if (model == null || !model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}")) {
      throw new ModelConfigurationInputException(field);
    }
  }

  public static void validateKey(String key, String field) {
    if (key == null
        || !key.matches("[\\x21-\\x7E]{1,4096}")
        || List.of("replace-", "your-", "example-", "changeme").stream()
            .anyMatch(key.toLowerCase(Locale.ROOT)::startsWith)) {
      throw new ModelConfigurationInputException(field);
    }
  }

  public static void validateDimensions(int dimensions) {
    if (dimensions < 2 || dimensions > 8192) {
      throw new ModelConfigurationInputException("embedding.dimensions");
    }
  }

  public static void validateRevision(String revision) {
    if (revision == null
        || !revision.matches("[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,159}")
        || Set.of("latest", "default", "unknown").contains(revision.toLowerCase(Locale.ROOT))) {
      throw new ModelConfigurationInputException("embedding.revision");
    }
  }

  @Override
  public String toString() {
    return "TextModelConfiguration[redacted]";
  }
}
