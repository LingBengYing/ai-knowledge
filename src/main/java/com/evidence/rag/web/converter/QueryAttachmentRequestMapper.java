package com.evidence.rag.web.converter;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.QueryAttachment;
import com.evidence.rag.model.dto.QueryAnswerMode;
import com.evidence.rag.model.dto.QueryAttachmentCommand;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Strict, independently bounded request decoding for ephemeral query media. */
public final class QueryAttachmentRequestMapper {
  public static final int MAX_REQUEST_BYTES = 28 * 1024 * 1024;
  private static final int MAX_ATTACHMENT_BYTES = 20 * 1024 * 1024;
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

  private QueryAttachmentRequestMapper() {}

  public static QueryAttachmentCommand command(byte[] body) {
    if (body != null && body.length > MAX_REQUEST_BYTES) {
      throw tooLarge();
    }
    try {
      if (body == null || body.length == 0) {
        throw invalid();
      }
      String input =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(body))
              .toString();
      var node = JSON.readTree(input);
      if (!node.isObject()
          || !node.has("question")
          || !node.has("mode")
          || !node.has("attachments")
          || !node.path("question").isString()
          || !node.path("mode").isString()
          || !node.path("attachments").isArray()
          || !Set.of("question", "document_ids", "mode", "attachments")
              .containsAll(node.propertyNames())
          || node.path("attachments").size() > 3) {
        throw invalid();
      }
      String modeName = node.path("mode").asString();
      var mode = QueryAnswerMode.valueOf(modeName.toUpperCase(Locale.ROOT));
      if (!mode.name().toLowerCase(Locale.ROOT).equals(modeName)) {
        throw invalid();
      }
      Map<String, Object> answer = new LinkedHashMap<>();
      answer.put("question", node.path("question").asString());
      if (node.has("document_ids")) {
        if (!node.path("document_ids").isArray()) {
          throw invalid();
        }
        var ids = new ArrayList<String>();
        for (var id : node.path("document_ids")) {
          if (!id.isString()) {
            throw invalid();
          }
          ids.add(id.asString());
        }
        answer.put("document_ids", ids);
      }
      var command = AnswerRequestMapper.command(answer);
      List<QueryAttachment> attachments = new ArrayList<>();
      int remaining = MAX_ATTACHMENT_BYTES;
      for (var attachment : node.path("attachments")) {
        if (!attachment.isObject()
            || attachment.size() != 3
            || !Set.of("filename", "media_type", "content_base64")
                .equals(new HashSet<>(attachment.propertyNames()))
            || !attachment.path("filename").isString()
            || !attachment.path("media_type").isString()
            || !attachment.path("content_base64").isString()) {
          throw invalid();
        }
        String encoded = attachment.path("content_base64").asString();
        if (encoded.length() > ((long) remaining + 2) / 3 * 4) {
          throw tooLarge();
        }
        byte[] content = Base64.getDecoder().decode(encoded);
        if (content.length > remaining) {
          throw tooLarge();
        }
        if (!Base64.getEncoder().encodeToString(content).equals(encoded)) {
          throw invalid();
        }
        remaining -= content.length;
        attachments.add(
            new QueryAttachment(
                attachment.path("filename").asString(),
                attachment.path("media_type").asString(),
                content));
      }
      return new QueryAttachmentCommand(command, mode, attachments);
    } catch (ApplicationException failure) {
      throw failure;
    } catch (Exception failure) {
      throw invalid();
    }
  }

  private static ApplicationException tooLarge() {
    return new ApplicationException(
        FailureKind.PAYLOAD_TOO_LARGE, "query_request_too_large", "附件请求超过接收限额。");
  }

  private static ApplicationException invalid() {
    return new ApplicationException(FailureKind.INVALID_INPUT, "invalid_request", "附件提问请求无效。");
  }
}
