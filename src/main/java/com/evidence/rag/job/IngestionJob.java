package com.evidence.rag.job;

import com.evidence.rag.model.domain.IngestionClaim;
import com.evidence.rag.model.domain.LibraryOperationGate;
import com.evidence.rag.service.IngestionTaskProcessor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Fixed, bounded task triggers and scheduler-owned thread lifetime; no worker or storage access.
 */
public final class IngestionJob implements AutoCloseable {
  private final IngestionTaskProcessor processor;
  private final LibraryOperationGate operations;
  private final ScheduledExecutorService scheduler =
      Executors.newScheduledThreadPool(2, Thread.ofPlatform().name("ingestion-job-", 0).factory());
  private final Object lock = new Object();
  private IngestionClaim current;
  private Thread executing;
  private boolean closed;

  public IngestionJob(IngestionTaskProcessor processor) {
    this(processor, null);
  }

  public IngestionJob(IngestionTaskProcessor processor, LibraryOperationGate operations) {
    this.operations = operations;
    this.processor = processor;
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
    IngestionClaim claim = null;
    try {
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
        executing = Thread.currentThread();
      }
      processor.process(claim);
    } catch (RuntimeException unavailable) {
      processor.failUnexpected(claim);
    } finally {
      synchronized (lock) {
        current = null;
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
        if (!processor.isCurrent(current)) {
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
