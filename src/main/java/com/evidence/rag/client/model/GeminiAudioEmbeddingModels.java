package com.evidence.rag.client.model;

import com.evidence.rag.client.model.OpenAiCompatibleModels.Endpoint;
import java.time.Duration;
import java.util.List;

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
      GeminiProtocol.validate(
          endpoint,
          modelRevision,
          dimensions,
          decoderRevision,
          deadline,
          maxResponseBytes,
          allowLoopbackHttp,
          false);
    }

    @Override
    public String toString() {
      return "Configuration[redacted]";
    }
  }

  public GeminiAudioEmbeddingModels(Configuration configuration) {
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
  public List<Double> embed(byte[] canonicalWav) {
    if (Thread.currentThread().isInterrupted()) {
      throw new TextModels.Failure("model_interrupted");
    }
    byte[] wav = GeminiProtocol.checkedWav(canonicalWav);
    return GeminiProtocol.embed(
        transport,
        configuration.endpoint(),
        configuration.dimensions(),
        GeminiProtocol.inlineData("audio/wav", wav),
        false);
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
