package com.evidence.rag.web.converter;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.dto.WikiPageLifecycleCommand;
import com.evidence.rag.model.dto.WikiProposalCommand;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Strict HTTP shapes only; source versions and locations are always chosen by the server. */
public final class WikiRequestMapper {
  public static final int MAX_BYTES = 32 * 1024;
  private static final Set<String> PROPOSAL_FIELDS =
      Set.of("base_version", "title", "kind", "document_ids", "generation_method");
  private static final JsonMapper JSON =
      JsonMapper.builder(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxDocumentLength(MAX_BYTES)
                          .maxNestingDepth(5)
                          .maxStringLength(MAX_BYTES)
                          .maxNumberLength(32)
                          .build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private WikiRequestMapper() {}

  public static WikiProposalCommand proposal(byte[] bytes) {
    var node = object(bytes);
    var fields = new HashSet<>(node.propertyNames());
    fields.remove("page_id");
    if (!fields.equals(PROPOSAL_FIELDS) || !node.path("document_ids").isArray()) {
      throw invalid();
    }
    var documents = new ArrayList<String>();
    node.path("document_ids").forEach(value -> documents.add(text(value)));
    String pageId =
        node.path("page_id").isMissingNode() || node.path("page_id").isNull()
            ? null
            : text(node.path("page_id"));
    return new WikiProposalCommand(
        pageId,
        integer(node.path("base_version")),
        text(node.path("title")),
        text(node.path("kind")),
        documents,
        text(node.path("generation_method")));
  }

  public static long accept(byte[] bytes) {
    var node = object(bytes);
    if (!Set.copyOf(node.propertyNames()).equals(Set.of("base_version"))) {
      throw invalid();
    }
    return integer(node.path("base_version"));
  }

  public static void dismiss(byte[] bytes) {
    if (!object(bytes).isEmpty()) {
      throw invalid();
    }
  }

  public static WikiPageLifecycleCommand lifecycle(byte[] bytes) {
    var node = object(bytes);
    if (!Set.copyOf(node.propertyNames()).equals(Set.of("version", "lifecycle_version"))) {
      throw invalid();
    }
    return new WikiPageLifecycleCommand(
        integer(node.path("version")), integer(node.path("lifecycle_version")));
  }

  public static String pageState(String state) {
    if (state == null) {
      return "active";
    }
    if (!Set.of("active", "deleted").contains(state)) {
      throw invalid();
    }
    return state;
  }

  public static WikiPageLifecycleCommand lifecycle(String version, String lifecycleVersion) {
    return new WikiPageLifecycleCommand(requiredLong(version), requiredLong(lifecycleVersion));
  }

  private static long requiredLong(String value) {
    try {
      if (value == null || !value.matches("[0-9]{1,16}")) {
        throw invalid();
      }
      return Long.parseLong(value);
    } catch (NumberFormatException failed) {
      throw invalid();
    }
  }

  public static void query(Map<String, String[]> parameters, Set<String> names) {
    for (var entry : parameters.entrySet()) {
      if (!names.contains(entry.getKey()) || entry.getValue().length != 1) {
        throw invalid();
      }
    }
  }

  public static int number(String value, int fallback) {
    if (value == null) {
      return fallback;
    }
    try {
      if (!value.matches("[0-9]{1,9}")) {
        throw invalid();
      }
      return Integer.parseInt(value);
    } catch (NumberFormatException failed) {
      throw invalid();
    }
  }

  private static JsonNode object(byte[] bytes) {
    if (bytes != null && bytes.length > MAX_BYTES) {
      throw new ApplicationException(
          FailureKind.PAYLOAD_TOO_LARGE, "wiki_request_too_large", "知识页请求超过接收限额。");
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
      return node;
    } catch (Exception failed) {
      throw invalid();
    }
  }

  private static String text(JsonNode node) {
    if (!node.isString()) {
      throw invalid();
    }
    return node.stringValue();
  }

  private static long integer(JsonNode node) {
    if (!node.isIntegralNumber() || !node.canConvertToLong()) {
      throw invalid();
    }
    return node.longValue();
  }

  public static ApplicationException invalid() {
    return new ApplicationException(
        FailureKind.INVALID_REQUEST, "invalid_wiki_request", "知识页请求字段或格式无效。");
  }
}
