package com.evidence.rag.web.converter;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.RetrievalSettings;
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

/** Exact settings payloads; a temporary override is complete but has no client version. */
public final class RetrievalSettingsRequestMapper {
  public static final int MAX_BYTES = 16 * 1024;
  private static final Set<String> FIELDS =
      Set.of(
          "search_method",
          "ranking_mode",
          "dense_weight",
          "top_k",
          "score_threshold_enabled",
          "score_threshold");
  private static final JsonMapper JSON =
      JsonMapper.builder(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxDocumentLength(MAX_BYTES)
                          .maxNestingDepth(4)
                          .maxStringLength(MAX_BYTES)
                          .maxNumberLength(64)
                          .build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private RetrievalSettingsRequestMapper() {}

  public static RetrievalSettings save(byte[] bytes) {
    if (bytes != null && bytes.length > MAX_BYTES) {
      throw new ApplicationException(
          FailureKind.PAYLOAD_TOO_LARGE, "retrieval_settings_too_large", "检索设置请求超过接收限额。");
    }
    try {
      if (bytes == null || bytes.length == 0) {
        throw invalid();
      }
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      var node = JSON.readTree(text);
      if (node == null || !node.isObject()) {
        throw invalid();
      }
      var fields = new java.util.HashSet<>(node.propertyNames());
      if (!fields.remove("version") || !fields.equals(FIELDS)) {
        throw invalid();
      }
      return settings(node, integer(node.path("version")));
    } catch (Exception failed) {
      throw invalid();
    }
  }

  public static RetrievalSettings override(JsonNode node, long version) {
    if (node == null || !node.isObject() || !Set.copyOf(node.propertyNames()).equals(FIELDS)) {
      throw invalid();
    }
    return settings(node, version);
  }

  private static RetrievalSettings settings(JsonNode node, long version) {
    long topK = integer(node.path("top_k"));
    if (topK < 1 || topK > 20 || !node.path("score_threshold_enabled").isBoolean()) {
      throw invalid();
    }
    return new RetrievalSettings(
        version,
        text(node.path("search_method")),
        text(node.path("ranking_mode")),
        number(node.path("dense_weight")),
        (int) topK,
        node.path("score_threshold_enabled").booleanValue(),
        number(node.path("score_threshold")));
  }

  private static String text(JsonNode value) {
    if (!value.isString()) {
      throw invalid();
    }
    return value.stringValue();
  }

  private static double number(JsonNode value) {
    if (!value.isNumber() || !Double.isFinite(value.doubleValue())) {
      throw invalid();
    }
    return value.doubleValue();
  }

  private static long integer(JsonNode value) {
    if (!value.isIntegralNumber() || !value.canConvertToLong()) {
      throw invalid();
    }
    return value.longValue();
  }

  private static ApplicationException invalid() {
    return new ApplicationException(
        FailureKind.INVALID_REQUEST, "invalid_retrieval_settings", "检索设置字段或取值无效。");
  }
}
