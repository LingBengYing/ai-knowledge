package com.evidence.rag.job;

import com.evidence.rag.model.domain.IndexClaim;
import com.evidence.rag.model.domain.LibraryOperationGate;
import com.evidence.rag.service.IndexingTaskProcessor;
import java.util.Optional;
import java.util.function.Supplier;

/** Each consumer captures its own immutable managed-model processor. */
public final class IndexingJob implements AutoCloseable {
  private record Work(IndexingTaskProcessor processor, IndexClaim claim) {}

  private final BoundedTaskJob<Work> tasks;

  public IndexingJob(IndexingTaskProcessor processor) {
    this(processor, null);
  }

  public IndexingJob(IndexingTaskProcessor processor, LibraryOperationGate operations) {
    this(() -> processor, operations, 1);
  }

  public IndexingJob(
      IndexingTaskProcessor processor, LibraryOperationGate operations, int concurrency) {
    this(() -> processor, operations, concurrency);
  }

  public static IndexingJob managed(
      Supplier<IndexingTaskProcessor> processors, LibraryOperationGate operations) {
    return new IndexingJob(processors, operations, 1);
  }

  public static IndexingJob managed(
      Supplier<IndexingTaskProcessor> processors,
      LibraryOperationGate operations,
      int concurrency) {
    return new IndexingJob(processors, operations, concurrency);
  }

  private IndexingJob(
      Supplier<IndexingTaskProcessor> processors,
      LibraryOperationGate operations,
      int concurrency) {
    tasks =
        new BoundedTaskJob<>(
            "indexing-job",
            concurrency,
            operations,
            () -> {
              var processor = processors.get();
              return processor == null
                  ? Optional.empty()
                  : processor.claim().map(c -> new Work(processor, c));
            },
            work -> work.processor().process(work.claim()),
            work -> work.processor().isCurrent(work.claim()),
            work -> work.processor().failUnexpected(work.claim()));
  }

  @Override
  public void close() {
    tasks.close();
  }
}
