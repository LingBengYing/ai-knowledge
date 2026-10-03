package com.evidence.rag.job;

import com.evidence.rag.model.domain.LibraryOperationGate;
import com.evidence.rag.model.domain.SynopsisClaim;
import com.evidence.rag.service.SynopsisTaskProcessor;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** One active synopsis claim, with scheduler-owned cancellation and shutdown. */
public final class SynopsisJob implements AutoCloseable {
  private final SynopsisTaskProcessor processor;
  private final LibraryOperationGate operations;
  private final ScheduledExecutorService scheduler =
      Executors.newScheduledThreadPool(2, Thread.ofPlatform().name("synopsis-job-", 0).factory());
  private final Object lock = new Object();
  private SynopsisClaim current;
  private Thread executing;
  private boolean closed;

  public SynopsisJob(SynopsisTaskProcessor processor) {
    this(processor, null);
  }

  public SynopsisJob(SynopsisTaskProcessor processor, LibraryOperationGate operations) {
    this.operations = operations;
    this.processor = Objects.requireNonNull(processor);
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
    SynopsisClaim claim = null;
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
        Thread.interrupted();
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
      if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Synopsis processing did not stop");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Synopsis shutdown was interrupted");
    }
  }
}
