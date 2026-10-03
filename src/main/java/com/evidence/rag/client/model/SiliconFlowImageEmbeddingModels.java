package com.evidence.rag.client.model;

import com.evidence.rag.client.model.OpenAiCompatibleModels.Endpoint;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.tool.parser.ImageInput;
import com.evidence.rag.tool.parser.TextParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Single-original-image SiliconFlow embedding Adapter; construction never contacts a provider. */
public final class SiliconFlowImageEmbeddingModels implements ImageEmbeddingModels, AutoCloseable {
  private static final int MAX_REQUEST_BYTES = 14 * 1024 * 1024;
  private static final String PROTOCOL = "siliconflow-embeddings-single-image-v1";
  private static final String ORIGINAL_POLICY = "raw-canonical-base64-png-jpeg-v1";

  public record Configuration(
      Endpoint endpoint,
      String modelRevision,
      int dimensions,
      Duration deadline,
      int maxResponseBytes,
      boolean allowLoopbackHttp) {
    public Configuration {
      if (modelRevision == null
          || !modelRevision.matches("[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,159}")
          || Set.of("latest", "default", "unknown").contains(modelRevision.toLowerCase(Locale.ROOT))
          || dimensions < 2
          || dimensions > 8192
          || deadline == null
          || deadline.compareTo(Duration.ofMillis(10)) < 0
          || deadline.compareTo(Duration.ofMillis(120000)) > 0
          || maxResponseBytes < 1024
          || maxResponseBytes > 4 * 1024 * 1024) {
        throw new TextModels.Failure("model_invalid_configuration");
      }
      ModelHttpTransport.validateEndpoint(endpoint, allowLoopbackHttp);
    }

    @Override
    public String toString() {
      return "Configuration[redacted]";
    }
  }

  private final Configuration configuration;
  private final ModelHttpTransport transport;
  private final String revision;

  public SiliconFlowImageEmbeddingModels(Configuration configuration) {
    if (configuration == null) {
      throw new TextModels.Failure("model_invalid_configuration");
    }
    this.configuration = configuration;
    transport =
        new ModelHttpTransport(
            configuration.deadline(), configuration.maxResponseBytes(), MAX_REQUEST_BYTES);
    revision = revisionOf(configuration);
  }

  @Override
  public List<Double> embed(VisualImage image) {
    if (Thread.currentThread().isInterrupted()) {
      throw new TextModels.Failure("model_interrupted");
    }
    if (image == null) {
      throw new TextModels.Failure("model_invalid_input");
    }
    byte[] original = image.content();
    try {
      ImageInput.validateEnvelope(
          "image." + (image.mediaType().equals("image/png") ? "png" : "jpeg"),
          image.mediaType(),
          original);
    } catch (TextParser.Failure invalid) {
      throw new TextModels.Failure("model_invalid_input");
    }
    var response =
        transport.post(
            configuration.endpoint(),
            "embeddings",
            Map.of(
                "model", configuration.endpoint().model(),
                "input", Map.of("image", Base64.getEncoder().encodeToString(original)),
                "encoding_format", "float",
                "dimensions", configuration.dimensions()));
    if (!Set.of("object", "model", "data", "usage").containsAll(response.propertyNames())
        || !equalText(response.path("object"), "list")
        || !equalText(response.path("model"), configuration.endpoint().model())) {
      throw invalidResponse();
    }
    var data = response.path("data");
    if (!data.isArray() || data.size() != 1) {
      throw invalidResponse();
    }
    var row = data.get(0);
    if (!row.isObject()
        || !new HashSet<>(row.propertyNames()).equals(Set.of("object", "index", "embedding"))
        || !equalText(row.path("object"), "embedding")
        || !row.path("index").isIntegralNumber()
        || !row.path("index").canConvertToInt()
        || row.path("index").intValue() != 0) {
      throw invalidResponse();
    }
    var values = row.path("embedding");
    if (!values.isArray() || values.size() != configuration.dimensions()) {
      throw invalidResponse();
    }
    var vector = new ArrayList<Double>(configuration.dimensions());
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
      vector.add(canonical == 0 ? 0.0 : (double) canonical);
    }
    if (!nonzero) {
      throw invalidResponse();
    }
    return List.copyOf(vector);
  }

  @Override
  public String revision() {
    return revision;
  }

  @Override
  public int dimensions() {
    return configuration.dimensions();
  }

  @Override
  public void close() {
    transport.close();
  }

  private static boolean equalText(JsonNode node, String expected) {
    return node.isString() && expected.equals(node.stringValue());
  }

  private static String revisionOf(Configuration configuration) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      for (String value :
          List.of(
              PROTOCOL,
              ORIGINAL_POLICY,
              configuration.endpoint().baseUrl().toString(),
              configuration.endpoint().model(),
              configuration.modelRevision(),
              Integer.toString(configuration.dimensions()))) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
      }
      return "java-image-embedding-v1:" + HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new TextModels.Failure("model_invalid_configuration");
    }
  }

  private static TextModels.Failure invalidResponse() {
    return new TextModels.Failure("model_invalid_response");
  }
}
