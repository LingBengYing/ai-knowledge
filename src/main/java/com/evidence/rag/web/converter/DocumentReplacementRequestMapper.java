package com.evidence.rag.web.converter;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.ModelValues;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

public final class DocumentReplacementRequestMapper {
  public static final int MAX_BYTES = 128 * 1024;
  private static final JsonMapper JSON =
      JsonMapper.builder(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxDocumentLength(MAX_BYTES)
                          .maxStringLength(128)
                          .maxNameLength(64)
                          .maxNestingDepth(2)
                          .maxTokenCount(32)
                          .build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private DocumentReplacementRequestMapper() {}

  public record IndexCommand(String candidateRevisionId, String baseRevisionId) {}

  public static IndexCommand index(byte[] bytes) {
    if (bytes != null && bytes.length > MAX_BYTES) {
      throw new ApplicationException(
          FailureKind.PAYLOAD_TOO_LARGE, "request_too_large", "版本更新请求超过接收限额。");
    }
    try {
      if (bytes == null || bytes.length == 0) {
        throw ModelValues.invalid();
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
          || !Set.copyOf(node.propertyNames())
              .equals(Set.of("candidate_revision_id", "base_revision_id"))
          || !node.path("candidate_revision_id").isString()
          || !node.path("base_revision_id").isString()) {
        throw ModelValues.invalid();
      }
      return new IndexCommand(
          ModelValues.identifier(node.path("candidate_revision_id").stringValue(), 128),
          ModelValues.identifier(node.path("base_revision_id").stringValue(), 128));
    } catch (Exception invalid) {
      throw ModelValues.invalid();
    }
  }
}
