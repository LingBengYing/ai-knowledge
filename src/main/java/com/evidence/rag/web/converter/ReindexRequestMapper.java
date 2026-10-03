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

/** Observed publication prevents delayed requests from rebuilding a newer version by accident. */
public final class ReindexRequestMapper {
  public static final int MAX_BYTES = 128 * 1024;
  private static final JsonMapper JSON =
      JsonMapper.builder(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxDocumentLength(MAX_BYTES)
                          .maxStringLength(MAX_BYTES)
                          .maxNameLength(64)
                          .maxNestingDepth(4)
                          .maxNumberLength(32)
                          .maxTokenCount(64)
                          .build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private ReindexRequestMapper() {}

  public static String basePublicationId(byte[] bytes) {
    if (bytes != null && bytes.length > MAX_BYTES) {
      throw new ApplicationException(
          FailureKind.PAYLOAD_TOO_LARGE, "request_too_large", "重建请求超过接收限额。");
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
          || !Set.copyOf(node.propertyNames()).equals(Set.of("base_publication_id"))
          || !node.path("base_publication_id").isString()) {
        throw ModelValues.invalid();
      }
      String base = node.path("base_publication_id").stringValue();
      if (!base.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")) {
        throw ModelValues.invalid();
      }
      return base;
    } catch (Exception invalid) {
      throw ModelValues.invalid();
    }
  }
}
