package com.evidence.rag.web.converter;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.QueryAttachment;
import com.evidence.rag.model.dto.VoiceQuestionCommand;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Strict single-audio input preparation request; it cannot select library evidence. */
public final class VoiceQuestionRequestMapper {
  public static final int MAX_REQUEST_BYTES = 28 * 1024 * 1024;
  private static final int MAX_AUDIO_BYTES = 20 * 1024 * 1024;
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

  private VoiceQuestionRequestMapper() {}

  public static VoiceQuestionCommand command(byte[] body) {
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
          || !Set.of("filename", "media_type", "content_base64")
              .equals(new HashSet<>(node.propertyNames()))
          || !node.path("filename").isString()
          || !node.path("media_type").isString()
          || !node.path("content_base64").isString()) {
        throw invalid();
      }
      String encoded = node.path("content_base64").asString();
      if (encoded.length() > ((long) MAX_AUDIO_BYTES + 2) / 3 * 4) {
        throw tooLarge();
      }
      byte[] content = Base64.getDecoder().decode(encoded);
      if (content.length > MAX_AUDIO_BYTES) {
        throw tooLarge();
      }
      if (!Base64.getEncoder().encodeToString(content).equals(encoded)) {
        throw invalid();
      }
      return new VoiceQuestionCommand(
          new QueryAttachment(
              node.path("filename").asString(), node.path("media_type").asString(), content));
    } catch (ApplicationException failure) {
      throw failure;
    } catch (Exception failure) {
      throw invalid();
    }
  }

  private static ApplicationException invalid() {
    return new ApplicationException(FailureKind.INVALID_INPUT, "invalid_request", "语音输入请求无效。");
  }

  private static ApplicationException tooLarge() {
    return new ApplicationException(
        FailureKind.PAYLOAD_TOO_LARGE, "query_request_too_large", "语音输入超过接收限额。");
  }
}
