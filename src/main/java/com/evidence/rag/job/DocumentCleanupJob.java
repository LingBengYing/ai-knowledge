package com.evidence.rag.job;

import com.evidence.rag.service.DocumentCleanupService;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** One maintenance body at a time; shutdown waits for the actual body rather than its caller. */
public final class DocumentCleanupJob implements AutoCloseable {
  private final DocumentCleanupService service;
  private final ScheduledExecutorService executor;

  public DocumentCleanupJob(DocumentCleanupService service) {
    this.service = Objects.requireNonNull(service);
    executor =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "document-cleanup");
              thread.setDaemon(true);
              return thread;
            });
    executor.scheduleWithFixedDelay(this::tick, 250, 250, TimeUnit.MILLISECONDS);
  }

  public boolean runOnce() {
    return service.runOnce();
  }

  private void tick() {
    try {
      runOnce();
    } catch (RuntimeException ignored) {
      // Durable state remains retryable; exception text may contain internal paths.
    }
  }

  @Override
  public void close() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(130, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException interrupted) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
