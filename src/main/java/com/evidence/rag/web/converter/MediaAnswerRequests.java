package com.evidence.rag.web.converter;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.QueryAttachment;
import com.evidence.rag.model.dto.AnswerCommand;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Strict UTF-8 JSON object reader shared by the sound and video audiovisual answer routes; each
 * route keeps its own user-facing wording for invalid and oversized requests.
 */
final class MediaAnswerRequests {
  private static final int MAX_ATTACHMENT_BYTES = 20 * 1024 * 1024;
  private static final Set<String> ATTACHMENT_FIELDS =
      Set.of("filename", "media_type", "content_base64");
  private final JsonMapper json;
  private final String invalidMessage;
  private final String tooLargeMessage;

  MediaAnswerRequests(int maxRequestBytes, String invalidMessage, String tooLargeMessage) {
    json =
        JsonMapper.builder(
                JsonFactory.builder()
                    .streamReadConstraints(
                        StreamReadConstraints.builder()
                            .maxDocumentLength(maxRequestBytes)
                            .maxStringLength(maxRequestBytes)
                            .maxNestingDepth(8)
                            .maxNumberLength(32)
                            .maxNameLength(64)
                            .maxTokenCount(1000)
                            .build())
                    .build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    this.invalidMessage = invalidMessage;
    this.tooLargeMessage = tooLargeMessage;
  }

  JsonNode parse(byte[] body, int maximum) {
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
      var node = json.readTree(text);
      if (node == null || !node.isObject()) {
        throw invalid();
      }
      return node;
    } catch (Exception failure) {
      throw invalid();
    }
  }

  void shape(JsonNode node, Set<String> required, Set<String> allowed) {
    var fields = new HashSet<>(node.propertyNames());
    if (!node.isObject() || !fields.containsAll(required) || !allowed.containsAll(fields)) {
      throw invalid();
    }
  }

  /** The question and optional explicit document selection, validated as an ordinary answer. */
  AnswerCommand answer(JsonNode node) {
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

  /**
   * Decodes canonical base64 attachments within one shared 20 MiB budget; {@code check} vets each
   * decoded attachment before it counts against the budget.
   */
  List<QueryAttachment> attachments(JsonNode items, Consumer<QueryAttachment> check) {
    var attachments = new ArrayList<QueryAttachment>();
    int remaining = MAX_ATTACHMENT_BYTES;
    for (var item : items) {
      shape(item, ATTACHMENT_FIELDS, ATTACHMENT_FIELDS);
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
      check.accept(attachment);
      remaining -= bytes.length;
      attachments.add(attachment);
    }
    return attachments;
  }

  ApplicationException invalid() {
    return new ApplicationException(FailureKind.INVALID_INPUT, "invalid_request", invalidMessage);
  }

  ApplicationException tooLarge() {
    return new ApplicationException(
        FailureKind.PAYLOAD_TOO_LARGE, "query_request_too_large", tooLargeMessage);
  }
}
