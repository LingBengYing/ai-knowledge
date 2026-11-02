package com.evidence.rag.worker.indexing;

import com.evidence.rag.client.model.GeminiSoundEmbeddingModels;
import com.evidence.rag.client.model.GeminiSoundModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.model.domain.SoundBuildClaim;
import com.evidence.rag.model.domain.SoundReceipt;
import java.time.Duration;
import java.util.List;

/** Resource-bounded indexing subprocess Module. */
public final class ProcessSoundIndexer implements AutoCloseable {
  private static final ChildProcessIndexer.Spec<SoundIndexProtocol.Request, SoundReceipt, Failure>
      SPEC =
          new ChildProcessIndexer.Spec<>(
              "sound-index",
              "-Xmx256m",
              SoundIndexWorker.class,
              Failure.class,
              Failure::new,
              SoundIndexProtocol::invalid,
              SoundIndexProtocol.MAX_OUTPUT,
              SoundIndexProtocol::writeRequest,
              SoundIndexProtocol::decode);
  private final GeminiSoundModels.Configuration sounds;
  private final GeminiSoundEmbeddingModels.Configuration models;
  private final MilvusRestProjection.Settings projection;
  private final Duration timeout;
  private final ChildProcessIndexer<SoundIndexProtocol.Request, SoundReceipt, Failure> process;

  public ProcessSoundIndexer(
      GeminiSoundModels.Configuration sounds,
      GeminiSoundEmbeddingModels.Configuration models,
      MilvusRestProjection.Settings projection,
      Duration timeout) {
    this(sounds, models, projection, timeout, null, List.of());
  }

  // Trusted process fixture Adapter; same JDK, cleared environment and resource constraints.
  ProcessSoundIndexer(
      GeminiSoundModels.Configuration sounds,
      GeminiSoundEmbeddingModels.Configuration models,
      MilvusRestProjection.Settings projection,
      Duration timeout,
      String fixtureMainClass,
      List<String> fixtureArgs) {
    SoundIndexProtocol.validateSettings(sounds, models, projection, timeout);
    this.sounds = sounds;
    this.models = models;
    this.projection = projection;
    this.timeout = timeout;
    process = new ChildProcessIndexer<>(SPEC, timeout, fixtureMainClass, fixtureArgs);
  }

  public SoundReceipt index(SoundBuildClaim claim) {
    return process.index(
        () -> SoundIndexProtocol.request(sounds, models, projection, timeout, claim));
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
