package com.evidence.rag.worker.indexing;

import com.evidence.rag.client.model.GeminiAudioEmbeddingModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.model.domain.AudioVectorBuildClaim;
import com.evidence.rag.model.domain.AudioVectorReceipt;
import java.time.Duration;
import java.util.List;

/** Resource-bounded indexing subprocess Module. */
public final class ProcessAudioVectorIndexer implements AutoCloseable {
  private static final ChildProcessIndexer.Spec<
          AudioVectorProtocol.Request, AudioVectorReceipt, Failure>
      SPEC =
          new ChildProcessIndexer.Spec<>(
              "audio-vector",
              "-Xmx256m",
              AudioVectorWorker.class,
              Failure.class,
              Failure::new,
              AudioVectorProtocol::invalid,
              AudioVectorProtocol.MAX_OUTPUT,
              AudioVectorProtocol::writeRequest,
              AudioVectorProtocol::decode);
  private final GeminiAudioEmbeddingModels.Configuration models;
  private final MilvusRestProjection.Configuration projection;
  private final Duration timeout;
  private final ChildProcessIndexer<AudioVectorProtocol.Request, AudioVectorReceipt, Failure>
      process;

  public ProcessAudioVectorIndexer(
      GeminiAudioEmbeddingModels.Configuration models,
      MilvusRestProjection.Configuration projection,
      Duration timeout) {
    this(models, projection, timeout, null, List.of());
  }

  // Trusted process fixture Adapter; same JDK, cleared environment and resource constraints.
  ProcessAudioVectorIndexer(
      GeminiAudioEmbeddingModels.Configuration models,
      MilvusRestProjection.Configuration projection,
      Duration timeout,
      String fixtureMainClass,
      List<String> fixtureArgs) {
    AudioVectorProtocol.validateSettings(models, projection, timeout);
    this.models = models;
    this.projection = projection;
    this.timeout = timeout;
    process = new ChildProcessIndexer<>(SPEC, timeout, fixtureMainClass, fixtureArgs);
  }

  public AudioVectorReceipt index(AudioVectorBuildClaim claim) {
    return process.index(() -> AudioVectorProtocol.request(models, projection, timeout, claim));
  }

  @Override
  public void close() {
    process.close();
  }

  public static final class Failure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;

    public Failure(String code) {
      super("索引进程未完成安全校验。", null, false, true);
      this.code = code;
    }

    public String code() {
      return code;
    }
  }
}
