package com.evidence.rag.client.model;

import com.evidence.rag.client.model.OpenAiCompatibleModels.Endpoint;
import com.evidence.rag.model.domain.AudioWaveform;
import com.evidence.rag.model.domain.VideoAvClip;
import java.time.Duration;
import java.util.List;
import java.util.Map;

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

  public GeminiVideoAvEmbeddingModels(Configuration configuration) {
    if (configuration == null) {
      throw ModelJson.invalidConfiguration();
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
    return request(GeminiProtocol.inlineData("audio/wav", waveform.wav()));
  }

  @Override
  public List<Double> embedVideo(VideoAvClip clip) {
    if (clip == null) {
      throw new TextModels.Failure("model_invalid_input");
    }
    return request(GeminiProtocol.inlineData("video/mp4", clip.content()));
  }

  @Override
  public List<Double> embedText(String fullQuestion) {
    GeminiProtocol.checkQuestion(fullQuestion);
    return request(GeminiProtocol.text(fullQuestion));
  }

  private List<Double> request(Map<String, Object> content) {
    return GeminiProtocol.embed(
        transport, configuration.endpoint(), configuration.dimensions(), content, true);
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
        "java-video-av-embedding-v1:",
        List.of(
            PROTOCOL,
            PCM_POLICY,
            "complete-visual-only-mp4-8mib-continuous-30s-no-offset-no-audio-extraction-v1",
            "complete-text-question-4096utf8-no-prefix-no-taskType-no-truncate-v1",
            configuration.endpoint().baseUrl().toString(),
            configuration.endpoint().model(),
            configuration.modelRevision(),
            configuration.decoderRevision(),
            Integer.toString(configuration.dimensions())));
  }
}
