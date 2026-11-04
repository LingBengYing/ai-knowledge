package com.evidence.rag.client.model;

import static com.evidence.rag.client.model.ModelJson.invalidConfiguration;
import static com.evidence.rag.client.model.ModelJson.invalidResponse;

import com.evidence.rag.client.model.OpenAiCompatibleModels.Endpoint;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** One complete PCM waveform per fixed Gemini embedContent request; no transcription input. */
public final class GeminiAudioEmbeddingModels implements AudioEmbeddingModels, AutoCloseable {
  private static final String PROTOCOL = "google-v1beta-models-embedContent-inline-audio-v1";
  private static final String PCM_POLICY =
      "canonical-wav-44byte-16khz-mono-s16le-1-to-480000-samples-no-truncate-v1";
  private final Configuration configuration;
  private final ModelHttpTransport transport;
  private final String revision;

  public record Configuration(
      Endpoint endpoint,
      String modelRevision,
      int dimensions,
      String decoderRevision,
      Duration deadline,
      int maxResponseBytes,
      boolean allowLoopbackHttp) {
    public Configuration {
      if (!fixedRevision(decoderRevision)
          || !fixedRevision(modelRevision)
          || dimensions < 2
          || dimensions > 3072
          || deadline == null
          || deadline.compareTo(Duration.ofMillis(10)) < 0
          || deadline.compareTo(Duration.ofMillis(120000)) > 0
          || maxResponseBytes < 1024
          || maxResponseBytes > 4194304) {
        throw invalidConfiguration();
      }
      ModelHttpTransport.validateEndpoint(endpoint, allowLoopbackHttp);
      String path = endpoint.baseUrl().getRawPath();
      if (!(path.isEmpty() || path.equals("/"))
          || !endpoint.model().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
        throw invalidConfiguration();
      }
    }

    @Override
    public String toString() {
      return "Configuration[redacted]";
    }
  }

  public GeminiAudioEmbeddingModels(Configuration configuration) {
    if (configuration == null) {
      throw ModelJson.invalidConfiguration();
    }
    this.configuration = configuration;
    transport =
        new ModelHttpTransport(
            configuration.deadline(), configuration.maxResponseBytes(), 2 * 1024 * 1024);
    revision = profile(configuration);
  }

  @Override
  public List<Double> embed(byte[] canonicalWav) {
    if (Thread.currentThread().isInterrupted()) {
      throw new TextModels.Failure("model_interrupted");
    }
    byte[] wav = checkedWav(canonicalWav);
    int dimensions = configuration.dimensions();
    var response =
        transport.postGoogleEmbedding(
            configuration.endpoint(),
            Map.of(
                "content",
                Map.of(
                    "parts",
                    List.of(
                        Map.of(
                            "inlineData",
                            Map.of(
                                "mimeType",
                                "audio/wav",
                                "data",
                                Base64.getEncoder().encodeToString(wav))))),
                "embedContentConfig",
                Map.of("outputDimensionality", dimensions, "autoTruncate", false)));
    if (!Set.of("embedding", "usageMetadata").containsAll(response.propertyNames())) {
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
    // Exact-dimension, finite, nonzero float vector.
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
  private static byte[] checkedWav(byte[] source) {
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

  private static boolean fixedRevision(String revision) {
    return revision != null
        && revision.matches("[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,159}")
        && !Set.of("latest", "default", "unknown").contains(revision.toLowerCase(Locale.ROOT));
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
  public String decoderRevision() {
    return configuration.decoderRevision();
  }

  @Override
  public void close() {
    transport.close();
  }

  private static String profile(Configuration configuration) {
    return ModelRevisions.fingerprint(
        "java-audio-embedding-v1:",
        List.of(
            PROTOCOL,
            PCM_POLICY,
            configuration.endpoint().baseUrl().toString(),
            configuration.endpoint().model(),
            configuration.modelRevision(),
            configuration.decoderRevision(),
            Integer.toString(configuration.dimensions())));
  }
}
