package com.evidence.rag.client.model;

import com.evidence.rag.client.model.OpenAiCompatibleModels.Endpoint;
import com.evidence.rag.model.domain.AudioWaveform;
import com.evidence.rag.model.domain.VideoAvClip;
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

/** Separate full-question, actual MP4 and PCM vectors in one pinned multimodal space. */
public final class GeminiVideoAvEmbeddingModels implements VideoAvEmbeddingModels, AutoCloseable {
  private static final String PROTOCOL =
      "google-v1beta-models-embedContent-video-av-text-video-audio-v1";
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
      if (!fixedRevision(modelRevision)
          || !fixedRevision(decoderRevision)
          || dimensions < 2
          || dimensions > 3072
          || dimensions < 128
              && !(allowLoopbackHttp
                  && endpoint != null
                  && endpoint.baseUrl() != null
                  && "http".equals(endpoint.baseUrl().getScheme())
                  && ("127.0.0.1".equals(endpoint.baseUrl().getHost())
                      || "[::1]".equals(endpoint.baseUrl().getHost())))
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

    public String revision() {
      return profile(this);
    }

    @Override
    public String toString() {
      return "Configuration[redacted]";
    }
  }

  public GeminiVideoAvEmbeddingModels(Configuration configuration) {
    if (configuration == null) {
      throw invalidConfiguration();
    }
    this.configuration = configuration;
    transport =
        new ModelHttpTransport(
            configuration.deadline(), configuration.maxResponseBytes(), 14 * 1024 * 1024);
    revision = profile(configuration);
  }

  @Override
  public List<Double> embedAudio(AudioWaveform waveform) {
    if (Thread.currentThread().isInterrupted()) {
      throw new TextModels.Failure("model_interrupted");
    }
    if (waveform == null || !configuration.decoderRevision().equals(waveform.decoderRevision())) {
      throw new TextModels.Failure("model_invalid_input");
    }
    byte[] wav = waveform.wav();
    return request(
        Map.of(
            "parts",
            List.of(
                Map.of(
                    "inlineData",
                    Map.of(
                        "mimeType",
                        "audio/wav",
                        "data",
                        Base64.getEncoder().encodeToString(wav))))));
  }

  @Override
  public List<Double> embedVideo(VideoAvClip clip) {
    if (clip == null) {
      throw new TextModels.Failure("model_invalid_input");
    }
    return request(
        Map.of(
            "parts",
            List.of(
                Map.of(
                    "inlineData",
                    Map.of(
                        "mimeType",
                        "video/mp4",
                        "data",
                        Base64.getEncoder().encodeToString(clip.content()))))));
  }

  @Override
  public List<Double> embedText(String fullQuestion) {
    GeminiVideoAvModels.checkQuestion(fullQuestion);
    return request(Map.of("parts", List.of(Map.of("text", fullQuestion))));
  }

  private List<Double> request(Map<String, Object> content) {
    var response =
        transport.postGoogleEmbedding(
            configuration.endpoint(),
            Map.of(
                "content",
                content,
                "embedContentConfig",
                Map.of("outputDimensionality", configuration.dimensions(), "autoTruncate", false)));
    if (!Set.of("embedding", "usageMetadata").containsAll(response.propertyNames())
        || response.has("usageMetadata") && !response.path("usageMetadata").isObject()) {
      throw invalidResponse();
    }
    var embedding = response.path("embedding");
    if (!embedding.isObject()
        || !new HashSet<>(embedding.propertyNames()).equals(Set.of("values"))) {
      throw invalidResponse();
    }
    var values = embedding.path("values");
    if (!values.isArray() || values.size() != configuration.dimensions()) {
      throw invalidResponse();
    }
    var result = new ArrayList<Double>(configuration.dimensions());
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

  @Override
  public String revision() {
    return revision;
  }

  @Override
  public int dimensions() {
    return configuration.dimensions();
  }

  public String decoderRevision() {
    return configuration.decoderRevision();
  }

  @Override
  public void close() {
    transport.close();
  }

  private static boolean fixedRevision(String value) {
    return value != null
        && value.matches("[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,159}")
        && !Set.of("latest", "default", "unknown").contains(value.toLowerCase(Locale.ROOT));
  }

  private static String profile(Configuration configuration) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      for (String value :
          List.of(
              PROTOCOL,
              PCM_POLICY,
              "complete-visual-only-mp4-8mib-continuous-30s-no-offset-no-audio-extraction-v1",
              "complete-text-question-4096utf8-no-prefix-no-taskType-no-truncate-v1",
              configuration.endpoint().baseUrl().toString(),
              configuration.endpoint().model(),
              configuration.modelRevision(),
              configuration.decoderRevision(),
              Integer.toString(configuration.dimensions()))) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
      }
      return "java-video-av-embedding-v1:" + HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw invalidConfiguration();
    }
  }

  private static TextModels.Failure invalidConfiguration() {
    return new TextModels.Failure("model_invalid_configuration");
  }

  private static TextModels.Failure invalidResponse() {
    return new TextModels.Failure("model_invalid_response");
  }
}
