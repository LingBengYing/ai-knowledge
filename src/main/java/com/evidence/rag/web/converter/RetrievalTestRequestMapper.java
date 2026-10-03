package com.evidence.rag.web.converter;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.dto.RetrievalTestCommand;
import java.util.LinkedHashMap;
import java.util.Set;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

public final class RetrievalTestRequestMapper {
  public static final int MAX_BYTES = 131072;
  private static final JsonMapper JSON =
      JsonMapper.builder(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(8)
                          .maxStringLength(20000)
                          .maxNumberLength(20)
                          .build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private RetrievalTestRequestMapper() {}

  public static RetrievalTestCommand command(byte[] bytes) {
    try {
      if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
        throw ModelValues.invalid();
      }
      var node = JSON.readTree(bytes);
      if (!node.isObject()
          || !node.has("question")
          || !Set.of("question", "document_ids", "top_k", "rerank")
              .containsAll(node.propertyNames())) {
        throw ModelValues.invalid();
      }
      int topK = 5;
      if (node.has("top_k")) {
        var number = node.path("top_k");
        if (!number.isIntegralNumber() || !number.canConvertToInt()) {
          throw ModelValues.invalid();
        }
        topK = number.intValue();
      }
      boolean rerank = true;
      if (node.has("rerank")) {
        if (!node.path("rerank").isBoolean()) {
          throw ModelValues.invalid();
        }
        rerank = node.path("rerank").booleanValue();
      }
      var body = new LinkedHashMap<String, Object>();
      body.put("question", JSON.convertValue(node.path("question"), Object.class));
      if (node.has("document_ids")) {
        body.put("document_ids", JSON.convertValue(node.path("document_ids"), Object.class));
      }
      return new RetrievalTestCommand(AnswerRequestMapper.command(body), topK, rerank);
    } catch (RuntimeException invalid) {
      throw ModelValues.invalid();
    }
  }
}
