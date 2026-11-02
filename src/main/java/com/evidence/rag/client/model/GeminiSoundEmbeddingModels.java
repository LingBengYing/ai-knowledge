package com.evidence.rag.client.model;

import com.evidence.rag.client.model.OpenAiCompatibleModels.Endpoint;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** One complete PCM waveform per fixed Gemini embedContent request; no transcription input. */
public final class GeminiSoundEmbeddingModels implements SoundEmbeddingModels, AutoCloseable {
  private static final String PROTOCOL = "google-v1beta-models-embedContent-sound-text-audio-v1";
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
      GeminiProtocol.validate(
          endpoint,
          modelRevision,
          dimensions,
          decoderRevision,
          deadline,
          maxResponseBytes,
          allowLoopbackHttp,
          true);
    }

    public String revision() {
      return profile(this);
    }

    @Override
    public String toString() {
      return "Configuration[redacted]";
    }
  }

  public GeminiSoundEmbeddingModels(Configuration configuration) {
    if (configuration == null) {
      throw GeminiProtocol.invalidConfiguration();
    }
    this.configuration = configuration;
    transport =
        new ModelHttpTransport(
            configuration.deadline(), configuration.maxResponseBytes(), 2 * 1024 * 1024);
    revision = profile(configuration);
  }

  @Override
  public List<Double> embedAudio(byte[] canonicalWav) {
    if (Thread.currentThread().isInterrupted()) {
      throw new TextModels.Failure("model_interrupted");
    }
    byte[] wav = GeminiProtocol.checkedWav(canonicalWav);
    return request(GeminiProtocol.inlineData("audio/wav", wav));
  }

  @Override
  public List<Double> embedText(String fullQuestion) {
    GeminiProtocol.checkQuestion(fullQuestion);
    return request(GeminiProtocol.text(fullQuestion));
  }

  private List<Double> request(Map<String, Object> content) {
    return GeminiProtocol.embed(
        transport, configuration.endpoint(), configuration.dimensions(), content, false);
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

  private static String profile(Configuration configuration) {
    return ModelRevisions.fingerprint(
        "java-sound-embedding-v1:",
        List.of(
            PROTOCOL,
            PCM_POLICY,
            "complete-text-question-4096utf8-no-prefix-no-taskType-no-truncate-v1",
            configuration.endpoint().baseUrl().toString(),
            configuration.endpoint().model(),
            configuration.modelRevision(),
            configuration.decoderRevision(),
            Integer.toString(configuration.dimensions())));
  }
}
