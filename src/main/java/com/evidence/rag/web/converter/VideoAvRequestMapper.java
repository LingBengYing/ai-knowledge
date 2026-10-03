package com.evidence.rag.web.converter;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.VideoAvMode;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.model.dto.VideoAvAnswerCommand;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Exact independent video audiovisual input; no attachment or implicit mode conversion. */
public final class VideoAvRequestMapper {
  public static final int MAX_REQUEST_BYTES = 65536;
  private static final JsonMapper JSON =
      JsonMapper.builder(
              JsonFactory.builder()
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxDocumentLength(MAX_REQUEST_BYTES)
                          .maxStringLength(MAX_REQUEST_BYTES)
                          .maxNestingDepth(8)
                          .maxNumberLength(32)
                          .maxNameLength(64)
                          .maxTokenCount(1000)
                          .build())
                  .build())
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private VideoAvRequestMapper() {}

  public static VideoAvAnswerCommand command(byte[] body) {
    var node = parse(body, MAX_REQUEST_BYTES);
    shape(node, Set.of("question", "mode"), Set.of("question", "mode", "document_ids"));
    if (!node.path("mode").isString()) {
      throw invalid();
    }
    VideoAvMode mode;
    try {
      mode = VideoAvMode.valueOf(node.path("mode").stringValue());
    } catch (IllegalArgumentException failure) {
      throw invalid();
    }
    return new VideoAvAnswerCommand(answer(node), mode);
  }

  private static AnswerCommand answer(JsonNode node) {
    if (!node.path("question").isString()) {
      throw invalid();
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("question", node.path("question").stringValue());
    if (node.has("document_ids")) {
      if (!node.path("document_ids").isArray()) {
        throw invalid();
      }
      var ids = new ArrayList<String>();
      for (var id : node.path("document_ids")) {
        if (!id.isString()) {
          throw invalid();
        }
        ids.add(id.stringValue());
      }
      body.put("document_ids", ids);
    }
    return AnswerRequestMapper.command(body);
  }

  private static JsonNode parse(byte[] body, int maximum) {
    if (body != null && body.length > maximum) {
      throw tooLarge();
    }
    try {
      if (body == null || body.length == 0) {
        throw invalid();
      }
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(body))
              .toString();
      var node = JSON.readTree(text);
      if (node == null || !node.isObject()) {
        throw invalid();
      }
      return node;
    } catch (Exception failure) {
      throw invalid();
    }
  }

  private static void shape(JsonNode node, Set<String> required, Set<String> allowed) {
    var fields = new HashSet<>(node.propertyNames());
    if (!node.isObject() || !fields.containsAll(required) || !allowed.containsAll(fields)) {
      throw invalid();
    }
  }

  private static ApplicationException invalid() {
    return new ApplicationException(
        FailureKind.INVALID_INPUT, "invalid_request", "视频音画问答请求字段或取值无效。");
  }

  private static ApplicationException tooLarge() {
    return new ApplicationException(
        FailureKind.PAYLOAD_TOO_LARGE, "query_request_too_large", "视频音画问答请求超过接收限额。");
  }
}
