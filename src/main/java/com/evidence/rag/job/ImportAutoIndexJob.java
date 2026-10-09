package com.evidence.rag.job;

import com.evidence.rag.service.ImportAutoIndexService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Fixed single consumer; closing the browser never owns this continuation. */
public final class ImportAutoIndexJob implements AutoCloseable {
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().name("import-auto-index-", 0).factory());

  public ImportAutoIndexJob(ImportAutoIndexService service) {
    scheduler.scheduleWithFixedDelay(
        () -> {
          try {
            service.processNext();
          } catch (RuntimeException unavailable) {
            // An unconfirmed dispatch remains durable for explicit restart recovery, never model
            // retry.
          }
        },
        100,
        250,
        TimeUnit.MILLISECONDS);
  }

  @Override
  public void close() {
    scheduler.shutdownNow();
    try {
      scheduler.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
