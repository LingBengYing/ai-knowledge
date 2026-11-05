package com.evidence.rag.worker.indexing;

import com.evidence.rag.client.model.SiliconFlowImageEmbeddingModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.model.domain.ImageVectorBuildClaim;
import com.evidence.rag.model.domain.ImageVectorReceipt;
import java.time.Duration;
import java.util.List;

/** Resource-bounded indexing subprocess Module. */
public final class ProcessImageVectorIndexer implements AutoCloseable {
  private static final ChildProcessIndexer.Spec<
          ImageVectorProtocol.Request, ImageVectorReceipt, Failure>
      SPEC =
          new ChildProcessIndexer.Spec<>(
              "image-vector",
              "-Xmx256m",
              ImageVectorWorker.class,
              Failure.class,
              Failure::new,
              ImageVectorProtocol::invalid,
              ImageVectorProtocol.MAX_OUTPUT,
              ImageVectorProtocol::writeRequest,
              ImageVectorProtocol::decode);
  private final SiliconFlowImageEmbeddingModels.Configuration models;
  private final MilvusRestProjection.Configuration projection;
  private final Duration timeout;
  private final ChildProcessIndexer<ImageVectorProtocol.Request, ImageVectorReceipt, Failure>
      process;

  public ProcessImageVectorIndexer(
      SiliconFlowImageEmbeddingModels.Configuration models,
      MilvusRestProjection.Configuration projection,
      Duration timeout) {
    this(models, projection, timeout, null, List.of());
  }

  // Trusted process fixture Adapter; same JDK, cleared environment and resource constraints.
  ProcessImageVectorIndexer(
      SiliconFlowImageEmbeddingModels.Configuration models,
      MilvusRestProjection.Configuration projection,
      Duration timeout,
      String fixtureMainClass,
      List<String> fixtureArgs) {
    ImageVectorProtocol.validateSettings(models, projection, timeout);
    this.models = models;
    this.projection = projection;
    this.timeout = timeout;
    process = new ChildProcessIndexer<>(SPEC, timeout, fixtureMainClass, fixtureArgs);
  }

  public ImageVectorReceipt index(ImageVectorBuildClaim claim) {
    return process.index(() -> ImageVectorProtocol.request(models, projection, timeout, claim));
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
