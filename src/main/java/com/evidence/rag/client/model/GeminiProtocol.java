package com.evidence.rag.client.model;

import static com.evidence.rag.client.model.ModelJson.invalidConfiguration;
import static com.evidence.rag.client.model.ModelJson.invalidInput;
import static com.evidence.rag.client.model.ModelJson.invalidResponse;

import com.evidence.rag.client.model.OpenAiCompatibleModels.Endpoint;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Shared rules of the fixed Gemini Adapters: pinned configuration, the complete question, strict
 * JSON shapes and the embedContent request and vector.
 */
final class GeminiProtocol {
  private GeminiProtocol() {}

  /**
   * Validates one pinned embedding configuration; {@code loopbackOnlySmallDimensions} admits fewer
   * than 128 dimensions only for an explicit loopback HTTP test endpoint.
   */
  static void validate(
      Endpoint endpoint,
      String modelRevision,
      int dimensions,
      String decoderRevision,
      Duration deadline,
      int maxResponseBytes,
      boolean allowLoopbackHttp,
      boolean loopbackOnlySmallDimensions) {
    if (!fixedRevision(decoderRevision)
        || dimensions < 2
        || dimensions > 3072
        || loopbackOnlySmallDimensions
            && dimensions < 128
            && !(allowLoopbackHttp
                && endpoint != null
                && endpoint.baseUrl() != null
                && "http".equals(endpoint.baseUrl().getScheme())
                && ("127.0.0.1".equals(endpoint.baseUrl().getHost())
                    || "[::1]".equals(endpoint.baseUrl().getHost())))) {
      throw invalidConfiguration();
    }
    validateConfiguration(endpoint, modelRevision, deadline, maxResponseBytes, allowLoopbackHttp);
  }

  static Map<String, Object> inlineData(String mimeType, byte[] data) {
    return Map.of(
        "parts",
        List.of(
            Map.of(
                "inlineData",
                Map.of("mimeType", mimeType, "data", Base64.getEncoder().encodeToString(data)))));
  }

  static Map<String, Object> text(String text) {
    return Map.of("parts", List.of(Map.of("text", text)));
  }

  /**
   * Posts one content and returns its exact-dimension, finite, nonzero float vector; {@code
   * objectUsage} additionally requires any usage metadata to be an object.
   */
  static List<Double> embed(
      ModelHttpTransport transport,
      Endpoint endpoint,
      int dimensions,
      Map<String, Object> content,
      boolean objectUsage) {
    var response =
        transport.postGoogleEmbedding(
            endpoint,
            Map.of(
                "content",
                content,
                "embedContentConfig",
                Map.of("outputDimensionality", dimensions, "autoTruncate", false)));
    if (!Set.of("embedding", "usageMetadata").containsAll(response.propertyNames())
        || objectUsage
            && response.has("usageMetadata")
            && !response.path("usageMetadata").isObject()) {
      throw invalidResponse();
    }
    var embedding = response.path("embedding");
    if (!embedding.isObject()
        || !new HashSet<>(embedding.propertyNames()).equals(Set.of("values"))) {
      throw invalidResponse();
    }
    var values = embedding.path("values");
    if (!values.isArray() || values.size() != dimensions) {
      throw invalidResponse();
    }
    var result = new ArrayList<Double>(dimensions);
    boolean nonzero = false;
    for (var value : values) {
      if (!value.isNumber()) {
        throw invalidResponse();
      }
      double coordinate = value.doubleValue();
      if (!Double.isFinite(coordinate) || Math.abs(coordinate) > Float.MAX_VALUE) {
        throw invalidResponse();
      }
      float canonical = (float) coordinate;
      nonzero |= canonical != 0;
      result.add(canonical == 0 ? 0.0 : (double) canonical);
    }
    if (!nonzero) {
      throw invalidResponse();
    }
    return List.copyOf(result);
  }

  /** A defensive copy of one canonical 16 kHz mono s16le WAV with no ancillary bytes. */
  static byte[] checkedWav(byte[] source) {
    if (source == null || source.length < 46 || source.length > 960044 || source.length % 2 != 0) {
      throw new TextModels.Failure("model_invalid_input");
    }
    byte[] wav = source.clone();
    var header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
    // RIFF/WAVE, the exact PCM fmt chunk, and one complete data chunk; no ancillary bytes.
    if (header.getInt(0) != 0x46464952
        || header.getInt(4) != wav.length - 8
        || header.getInt(8) != 0x45564157
        || header.getInt(12) != 0x20746d66
        || header.getInt(16) != 16
        || header.getShort(20) != 1
        || header.getShort(22) != 1
        || header.getInt(24) != 16000
        || header.getInt(28) != 32000
        || header.getShort(32) != 2
        || header.getShort(34) != 16
        || header.getInt(36) != 0x61746164
        || header.getInt(40) != wav.length - 44) {
      throw new TextModels.Failure("model_invalid_input");
    }
    return wav;
  }

  /** A closed JSON schema object whose every property is required. */
  static Map<String, Object> objectSchema(Map<String, Object> properties) {
    return Map.of(
        "type",
        "object",
        "properties",
        properties,
        "required",
        properties.keySet().stream().sorted().toList(),
        "additionalProperties",
        false);
  }

  static void checkQuestion(String question) {
    if (question == null
        || question.isBlank()
        || question.getBytes(StandardCharsets.UTF_8).length > 4096
        || question
            .codePoints()
            .anyMatch(
                c ->
                    (c < 32 && c != '\n' && c != '\t') || c == 127 || c >= 0xD800 && c <= 0xDFFF)) {
      throw invalidInput();
    }
  }

  static void exact(JsonNode node, Set<String> fields) {
    allowed(node, fields, fields);
  }

  static void allowed(JsonNode node, Set<String> required, Set<String> allowed) {
    if (!node.isObject()
        || !node.propertyNames().containsAll(required)
        || !allowed.containsAll(node.propertyNames())) {
      throw invalidResponse();
    }
  }

  static void validateConfiguration(
      Endpoint endpoint, String revision, Duration deadline, int maxResponseBytes, boolean local) {
    if (!fixedRevision(revision)
        || deadline == null
        || deadline.compareTo(Duration.ofMillis(10)) < 0
        || deadline.compareTo(Duration.ofMillis(120000)) > 0
        || maxResponseBytes < 1024
        || maxResponseBytes > 4194304) {
      throw invalidConfiguration();
    }
    ModelHttpTransport.validateEndpoint(endpoint, local);
    String path = endpoint.baseUrl().getRawPath();
    if (!(path.isEmpty() || path.equals("/"))
        || !endpoint.model().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
      throw invalidConfiguration();
    }
  }

  static boolean fixedRevision(String revision) {
    return revision != null
        && revision.matches("[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,159}")
        && !Set.of("latest", "default", "unknown").contains(revision.toLowerCase(Locale.ROOT));
  }
}
