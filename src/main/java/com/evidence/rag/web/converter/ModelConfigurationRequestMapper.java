package com.evidence.rag.web.converter;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.exception.ModelConfigurationInputException;
import com.evidence.rag.model.domain.ModelConfigurationState;
import com.evidence.rag.model.domain.TextModelRole;
import com.evidence.rag.model.dto.SaveModelConfigurationCommand;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class ModelConfigurationRequestMapper {
  public static final int MAX_BYTES = 128 * 1024;
  private static final JsonMapper JSON =
      JsonMapper.builder(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxDocumentLength(MAX_BYTES)
                          .maxStringLength(4096)
                          .maxNameLength(64)
                          .maxNestingDepth(4)
                          .maxNumberLength(32)
                          .maxTokenCount(128)
                          .build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private ModelConfigurationRequestMapper() {}

  public record TestCommand(long version, TextModelRole role) {}

  public static SaveModelConfigurationCommand save(byte[] bytes) {
    var body = parse(bytes);
    exact(body, Set.of("base_version", "embedding", "rerank", "generation"));
    var embedding = body.path("embedding");
    roleShape(embedding, Set.of("model", "dimensions", "revision"));
    long dimensions = integer(embedding.path("dimensions"), "embedding.dimensions");
    if (dimensions > 8192) {
      throw invalid("embedding.dimensions");
    }
    return new SaveModelConfigurationCommand(
        integer(body.path("base_version"), "base_version"),
        new SaveModelConfigurationCommand.EmbeddingInput(
            text(embedding.path("model"), "embedding.model"),
            (int) dimensions,
            text(embedding.path("revision"), "embedding.revision"),
            key(embedding, "embedding"),
            provider(embedding, "embedding")),
        role(body.path("rerank"), "rerank"),
        role(body.path("generation"), "generation"));
  }

  public static TestCommand test(byte[] bytes) {
    var body = parse(bytes);
    exact(body, Set.of("version", "role"));
    return new TestCommand(
        integer(body.path("version"), "version"),
        TextModelRole.parse(text(body.path("role"), "role")));
  }

  public static long activate(byte[] bytes) {
    var body = parse(bytes);
    exact(body, Set.of("version"));
    return integer(body.path("version"), "version");
  }

  private static SaveModelConfigurationCommand.RoleInput role(JsonNode value, String role) {
    roleShape(value, Set.of("model"));
    return new SaveModelConfigurationCommand.RoleInput(
        text(value.path("model"), role + ".model"), key(value, role), provider(value, role));
  }

  private static String key(JsonNode value, String role) {
    return value.has("api_key") ? text(value.path("api_key"), role + ".api_key") : null;
  }

  private static String provider(JsonNode value, String role) {
    return value.has("provider") ? text(value.path("provider"), role + ".provider") : null;
  }

  private static void roleShape(JsonNode value, Set<String> required) {
    if (!value.isObject()) {
      throw invalid("request");
    }
    var names = new java.util.HashSet<>(value.propertyNames());
    names.remove("api_key");
    names.remove("provider");
    if (!names.equals(required)) {
      throw invalid("request");
    }
  }

  private static JsonNode parse(byte[] bytes) {
    if (bytes != null && bytes.length > MAX_BYTES) {
      throw new ApplicationException(
          FailureKind.PAYLOAD_TOO_LARGE, "model_configuration_too_large", "模型配置请求超过接收限额。");
    }
    try {
      if (bytes == null || bytes.length == 0) {
        throw invalid("request");
      }
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      var body = JSON.readTree(text);
      if (body == null || !body.isObject()) {
        throw invalid("request");
      }
      return body;
    } catch (Exception invalid) {
      throw invalid("request");
    }
  }

  private static void exact(JsonNode value, Set<String> fields) {
    if (!value.isObject() || !Set.copyOf(value.propertyNames()).equals(fields)) {
      throw invalid("request");
    }
  }

  private static String text(JsonNode value, String field) {
    if (!value.isString()) {
      throw invalid(field);
    }
    return value.stringValue();
  }

  private static long integer(JsonNode value, String field) {
    if (!value.isIntegralNumber()
        || !value.canConvertToLong()
        || value.longValue() < 0
        || value.longValue() > ModelConfigurationState.MAX_VERSION) {
      throw invalid(field);
    }
    return value.longValue();
  }

  private static ModelConfigurationInputException invalid(String field) {
    return new ModelConfigurationInputException(field);
  }
}
