package com.evidence.rag.job;

import com.evidence.rag.model.domain.IndexClaim;
import com.evidence.rag.model.domain.LibraryOperationGate;
import com.evidence.rag.service.IndexingTaskProcessor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Fixed, bounded task triggers and scheduler-owned thread lifetime; no worker or storage access.
 */
public final class IndexingJob implements AutoCloseable {
  private final Supplier<IndexingTaskProcessor> processors;
  private IndexingTaskProcessor currentProcessor;
  private final LibraryOperationGate operations;
  private final ScheduledExecutorService scheduler =
      Executors.newScheduledThreadPool(2, Thread.ofPlatform().name("indexing-job-", 0).factory());
  private final Object lock = new Object();
  private IndexClaim current;
  private Thread executing;
  private boolean closed;

  public IndexingJob(IndexingTaskProcessor processor) {
    this(processor, null);
  }

  public IndexingJob(IndexingTaskProcessor processor, LibraryOperationGate operations) {
    this(() -> processor, operations);
  }

  public static IndexingJob managed(
      Supplier<IndexingTaskProcessor> processors, LibraryOperationGate operations) {
    return new IndexingJob(processors, operations);
  }

  private IndexingJob(Supplier<IndexingTaskProcessor> processors, LibraryOperationGate operations) {
    this.operations = operations;
    this.processors = processors;
    scheduler.scheduleWithFixedDelay(this::processNext, 100, 250, TimeUnit.MILLISECONDS);
    scheduler.scheduleWithFixedDelay(this::cancelStale, 100, 100, TimeUnit.MILLISECONDS);
  }

  private void processNext() {
    if (operations == null) {
      processWithinOperation();
      return;
    }
    var reservation = operations.tryOperation();
    if (reservation.isEmpty()) {
      return;
    }
    try (var operation = reservation.orElseThrow()) {
      processWithinOperation();
    }
  }

  private void processWithinOperation() {
    IndexClaim claim = null;
    IndexingTaskProcessor processor = null;
    try {
      processor = processors.get();
      if (processor == null) {
        return;
      }
      synchronized (lock) {
        if (closed) {
          return;
        }
        var next = processor.claim();
        if (next.isEmpty()) {
          return;
        }
        claim = next.orElseThrow();
        current = claim;
        currentProcessor = processor;
        executing = Thread.currentThread();
      }
      processor.process(claim);
    } catch (RuntimeException unavailable) {
      if (processor != null) {
        processor.failUnexpected(claim);
      }
    } finally {
      synchronized (lock) {
        current = null;
        currentProcessor = null;
        executing = null;
        Thread.interrupted(); // Only this scheduler-owned thread, after worker cleanup.
      }
    }
  }

  private void cancelStale() {
    synchronized (lock) {
      if (current == null || executing == null) {
        return;
      }
      try {
        if (!currentProcessor.isCurrent(current)) {
          executing.interrupt();
        }
      } catch (RuntimeException unavailable) {
        executing.interrupt();
      }
    }
  }

  @Override
  public void close() {
    synchronized (lock) {
      closed = true;
      if (executing != null) {
        executing.interrupt();
      }
    }
    scheduler.shutdownNow();
    try {
      scheduler.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
