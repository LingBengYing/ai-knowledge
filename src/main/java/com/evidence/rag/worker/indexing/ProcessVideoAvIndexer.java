package com.evidence.rag.worker.indexing;

import com.evidence.rag.client.model.GeminiVideoAvEmbeddingModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.model.domain.VideoAvBuildClaim;
import com.evidence.rag.model.domain.VideoAvReceipt;
import java.time.Duration;
import java.util.List;

/** Resource-bounded indexing subprocess Module. */
public final class ProcessVideoAvIndexer implements AutoCloseable {
  private static final ChildProcessIndexer.Spec<
          VideoAvIndexProtocol.Request, VideoAvReceipt, Failure>
      SPEC =
          new ChildProcessIndexer.Spec<>(
              "video-av-index",
              "-Xmx512m",
              VideoAvIndexWorker.class,
              Failure.class,
              Failure::new,
              VideoAvIndexProtocol::invalid,
              VideoAvIndexProtocol.MAX_OUTPUT,
              VideoAvIndexProtocol::writeRequest,
              VideoAvIndexProtocol::decode);
  private final GeminiVideoAvEmbeddingModels.Configuration models;
  private final MilvusRestProjection.Settings visualProjection;
  private final MilvusRestProjection.Settings audioProjection;
  private final Duration timeout;
  private final ChildProcessIndexer<VideoAvIndexProtocol.Request, VideoAvReceipt, Failure> process;

  public ProcessVideoAvIndexer(
      GeminiVideoAvEmbeddingModels.Configuration models,
      MilvusRestProjection.Settings visualProjection,
      MilvusRestProjection.Settings audioProjection,
      Duration timeout) {
    this(models, visualProjection, audioProjection, timeout, null, List.of());
  }

  // Trusted process fixture Adapter; same JDK, cleared environment and resource constraints.
  ProcessVideoAvIndexer(
      GeminiVideoAvEmbeddingModels.Configuration models,
      MilvusRestProjection.Settings visualProjection,
      MilvusRestProjection.Settings audioProjection,
      Duration timeout,
      String fixtureMainClass,
      List<String> fixtureArgs) {
    VideoAvIndexProtocol.validateSettings(models, visualProjection, audioProjection, timeout);
    this.models = models;
    this.visualProjection = visualProjection;
    this.audioProjection = audioProjection;
    this.timeout = timeout;
    process = new ChildProcessIndexer<>(SPEC, timeout, fixtureMainClass, fixtureArgs);
  }

  /** Independently verifies every actual material/vector binding before authority publication. */
  public static void verify(VideoAvBuildClaim claim, VideoAvReceipt receipt) {
    VideoAvIndexProtocol.verify(claim, receipt);
  }

  public VideoAvReceipt index(VideoAvBuildClaim claim) {
    return process.index(
        () ->
            VideoAvIndexProtocol.request(
                models, visualProjection, audioProjection, timeout, claim));
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
