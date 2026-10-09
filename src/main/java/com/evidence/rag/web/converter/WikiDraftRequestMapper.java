package com.evidence.rag.web.converter;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.dto.WikiDraftCommand;
import com.evidence.rag.model.dto.WikiDraftUpdateCommand;
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

/** Only title/body/version are editable: client-supplied source claims are not accepted. */
public final class WikiDraftRequestMapper {
  public static final int MAX_BYTES = 512 * 1024;
  private static final JsonMapper JSON =
      JsonMapper.builder(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxDocumentLength(MAX_BYTES)
                          .maxNestingDepth(2)
                          .maxStringLength(MAX_BYTES)
                          .maxNumberLength(32)
                          .build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private WikiDraftRequestMapper() {}

  public static WikiDraftCommand create(byte[] bytes) {
    var node = object(bytes, Set.of("title", "body"));
    return new WikiDraftCommand(node.path("title").stringValue(), node.path("body").stringValue());
  }

  public static WikiDraftUpdateCommand update(byte[] bytes) {
    var node = object(bytes, Set.of("version", "title", "body"));
    if (!node.path("version").isIntegralNumber() || !node.path("version").canConvertToLong()) {
      throw WikiRequestMapper.invalid();
    }
    return new WikiDraftUpdateCommand(
        node.path("version").longValue(),
        node.path("title").stringValue(),
        node.path("body").stringValue());
  }

  public static long version(String value) {
    try {
      if (value == null || !value.matches("[0-9]{1,16}")) {
        throw WikiRequestMapper.invalid();
      }
      return Long.parseLong(value);
    } catch (NumberFormatException failure) {
      throw WikiRequestMapper.invalid();
    }
  }

  private static JsonNode object(byte[] bytes, Set<String> fields) {
    if (bytes != null && bytes.length > MAX_BYTES) {
      throw new ApplicationException(
          FailureKind.PAYLOAD_TOO_LARGE, "wiki_draft_too_large", "草稿超过接收限额。");
    }
    try {
      if (bytes == null || bytes.length == 0) {
        throw WikiRequestMapper.invalid();
      }
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      var node = JSON.readTree(text);
      if (node == null
          || !node.isObject()
          || !Set.copyOf(node.propertyNames()).equals(fields)
          || !node.path("title").isString()
          || !node.path("body").isString()) {
        throw WikiRequestMapper.invalid();
      }
      return node;
    } catch (Exception failure) {
      throw WikiRequestMapper.invalid();
    }
  }
}
