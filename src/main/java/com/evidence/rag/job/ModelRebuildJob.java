package com.evidence.rag.job;

import com.evidence.rag.service.ModelRebuildService;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Automatic explicit-batch execution; restart recovery never repeats provider work implicitly. */
public final class ModelRebuildJob implements AutoCloseable {
  private final ModelRebuildService service;
  private final ScheduledExecutorService executor;

  public ModelRebuildJob(ModelRebuildService service) {
    this.service = Objects.requireNonNull(service);
    executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
      var thread = new Thread(runnable, "model-index-rebuild");
      thread.setDaemon(true);
      return thread;
    });
    executor.scheduleWithFixedDelay(this::tick, 250, 250, TimeUnit.MILLISECONDS);
  }

  public boolean runOnce() {
    return service.processNext();
  }

  private void tick() {
    try {
      runOnce();
    } catch (RuntimeException unavailable) {
      // Safe persistent state is recovered on restart; no provider or private configuration logged.
    }
  }

  @Override
  public void close() {
    executor.shutdownNow();
    try {
      executor.awaitTermination(130, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    service.close();
  }
}
