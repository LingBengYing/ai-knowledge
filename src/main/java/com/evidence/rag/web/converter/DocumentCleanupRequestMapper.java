package com.evidence.rag.web.converter;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.ModelValues;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Narrow control input; authorization and maintenance admission stay in the Service. */
public final class DocumentCleanupRequestMapper {
  public static final int MAX_REQUEST_BYTES = 128 * 1024;
  private static final JsonMapper JSON =
      JsonMapper.builder(
              JsonFactory.builder()
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxDocumentLength(MAX_REQUEST_BYTES)
                          .maxStringLength(MAX_REQUEST_BYTES)
                          .maxNestingDepth(4)
                          .maxNumberLength(32)
                          .maxNameLength(64)
                          .maxTokenCount(512)
                          .build())
                  .build())
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private DocumentCleanupRequestMapper() {}

  public static List<String> documentIds(byte[] body) {
    if (body != null && body.length > MAX_REQUEST_BYTES) {
      throw new ApplicationException(
          FailureKind.PAYLOAD_TOO_LARGE, "cleanup_request_too_large", "清理请求超过接收限额。");
    }
    try {
      if (body == null || body.length == 0) {
        throw ModelValues.invalid();
      }
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(body))
              .toString();
      var node = JSON.readTree(text);
      if (node == null
          || !node.isObject()
          || !new HashSet<>(node.propertyNames()).equals(Set.of("document_ids"))
          || !node.path("document_ids").isArray()) {
        throw ModelValues.invalid();
      }
      var ids = new ArrayList<String>();
      for (var value : node.path("document_ids")) {
        if (!value.isString()) {
          throw ModelValues.invalid();
        }
        ids.add(value.asString());
      }
      return documentIds(Map.of("document_ids", ids));
    } catch (Exception invalid) {
      throw ModelValues.invalid();
    }
  }

  public static String documentId(String value) {
    if (value == null || !value.matches("[A-Za-z0-9_-]{1,100}")) {
      throw ModelValues.invalid();
    }
    return value;
  }

  public static List<String> documentIds(Map<String, Object> body) {
    if (body == null
        || !body.keySet().equals(Set.of("document_ids"))
        || !(body.get("document_ids") instanceof List<?> values)
        || values.isEmpty()
        || values.size() > 100) {
      throw ModelValues.invalid();
    }
    var ids = new ArrayList<String>();
    var seen = new HashSet<String>();
    for (Object value : values) {
      if (!(value instanceof String id) || !seen.add(documentId(id))) {
        throw ModelValues.invalid();
      }
      ids.add(id);
    }
    return List.copyOf(ids);
  }

  public static int[] page(Map<String, String[]> values) {
    if (values == null || !Set.of("page", "page_size").containsAll(values.keySet())) {
      throw ModelValues.invalid();
    }
    return new int[] {
      integer(values.get("page"), 1, Integer.MAX_VALUE), integer(values.get("page_size"), 20, 100)
    };
  }

  private static int integer(String[] values, int fallback, int max) {
    if (values == null) {
      return fallback;
    }
    if (values.length != 1 || values[0] == null || !values[0].matches("[1-9][0-9]*")) {
      throw ModelValues.invalid();
    }
    try {
      int value = Integer.parseInt(values[0]);
      if (value > max) {
        throw ModelValues.invalid();
      }
      return value;
    } catch (NumberFormatException invalid) {
      throw ModelValues.invalid();
    }
  }
}
