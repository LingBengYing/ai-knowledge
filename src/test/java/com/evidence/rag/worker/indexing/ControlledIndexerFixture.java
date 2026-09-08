package com.evidence.rag.worker.indexing;

import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.vector.MilvusRestProjection;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Test-only bridge to the controlled real executable, without widening the production Seam. */
public final class ControlledIndexerFixture {
  private ControlledIndexerFixture() {}

  public static ProcessTextIndexer indexer(
      OpenAiCompatibleModels.Configuration models,
      MilvusRestProjection.Settings projection,
      Duration timeout,
      String mode,
      Path argument) {
    return new ProcessTextIndexer(
        models,
        projection,
        timeout,
        ProcessTextIndexerTest.ProcessFixture.class.getName(),
        List.of(mode, argument.toString()));
  }
}
