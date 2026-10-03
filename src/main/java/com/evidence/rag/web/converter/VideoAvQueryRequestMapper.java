package com.evidence.rag.web.converter;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.QueryAttachment;
import com.evidence.rag.model.domain.VideoAvMode;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.model.dto.VideoAvAnswerCommand;
import com.evidence.rag.model.dto.VideoAvQueryCommand;
import com.evidence.rag.service.VideoAvCompilationService;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
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

/** Exact complete video references, independently admitted without decoding or storing them. */
public final class VideoAvQueryRequestMapper {
  public static final int MAX_REQUEST_BYTES = 28 * 1024 * 1024;
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

  private VideoAvQueryRequestMapper() {}

  public static VideoAvQueryCommand attached(byte[] body) {
    var node = parse(body, MAX_REQUEST_BYTES);
    shape(
        node,
        Set.of("question", "mode", "attachments"),
        Set.of("question", "mode", "attachments", "document_ids"));
    if (!node.path("mode").isString()
        || !node.path("attachments").isArray()
        || node.path("attachments").isEmpty()
        || node.path("attachments").size() > 3) {
      throw invalid();
    }
    VideoAvMode mode;
    try {
      mode = VideoAvMode.valueOf(node.path("mode").stringValue());
    } catch (IllegalArgumentException failure) {
      throw invalid();
    }
    var command = new VideoAvAnswerCommand(answer(node), mode);
    var attachments = new ArrayList<QueryAttachment>();
    int remaining = 20 * 1024 * 1024;
    try {
      for (var item : node.path("attachments")) {
        shape(
            item,
            Set.of("filename", "media_type", "content_base64"),
            Set.of("filename", "media_type", "content_base64"));
        if (!item.path("filename").isString()
            || !item.path("media_type").isString()
            || !item.path("content_base64").isString()) {
          throw invalid();
        }
        String encoded = item.path("content_base64").stringValue();
        if (encoded.length() > ((long) remaining + 2) / 3 * 4) {
          throw tooLarge();
        }
        byte[] bytes = Base64.getDecoder().decode(encoded);
        if (bytes.length > remaining) {
          throw tooLarge();
        }
        if (!Base64.getEncoder().encodeToString(bytes).equals(encoded)) {
          throw invalid();
        }
        var attachment =
            new QueryAttachment(
                item.path("filename").stringValue(), item.path("media_type").stringValue(), bytes);
        VideoAvCompilationService.validateQueryInput(attachment);
        remaining -= bytes.length;
        attachments.add(attachment);
      }
      return new VideoAvQueryCommand(command, attachments);
    } catch (ApplicationException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw invalid();
    }
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
        FailureKind.INVALID_INPUT, "invalid_request", "视频音画参考问答请求字段或取值无效。");
  }

  private static ApplicationException tooLarge() {
    return new ApplicationException(
        FailureKind.PAYLOAD_TOO_LARGE, "query_request_too_large", "视频音画参考问答请求超过接收限额。");
  }
}
