package com.evidence.rag.job;

import com.evidence.rag.model.domain.IngestionClaim;
import com.evidence.rag.model.domain.LibraryOperationGate;
import com.evidence.rag.service.IngestionTaskProcessor;

/** Bounded concurrent consumers of persistent ingestion tasks. */
public final class IngestionJob implements AutoCloseable {
  private final BoundedTaskJob<IngestionClaim> tasks;

  public IngestionJob(IngestionTaskProcessor processor) {
    this(processor, null);
  }

  public IngestionJob(IngestionTaskProcessor processor, LibraryOperationGate operations) {
    this(processor, operations, 1);
  }

  public IngestionJob(
      IngestionTaskProcessor processor, LibraryOperationGate operations, int concurrency) {
    tasks =
        new BoundedTaskJob<>(
            "ingestion-job",
            concurrency,
            operations,
            processor::claim,
            processor::process,
            processor::isCurrent,
            processor::failUnexpected);
  }

  @Override
  public void close() {
    tasks.close();
  }
}
