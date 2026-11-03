package com.evidence.rag.job;

import com.evidence.rag.model.domain.TaskWakeup;
import com.evidence.rag.service.ImportAutoIndexService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * One corpus dispatcher plus bounded non-corpus workers; closing the browser never owns this
 * continuation. Corpus dispatch only enqueues durable indexing tasks. The non-corpus workers only
 * settle legacy sound/video_av requests (those libraries were removed 2026-11-03).
 */
public final class ImportAutoIndexJob implements AutoCloseable {
  private final List<Thread> workers = new ArrayList<>();
  private volatile boolean closed;

  public ImportAutoIndexJob(ImportAutoIndexService service, int mediaConcurrency) {
    if (mediaConcurrency < 1 || mediaConcurrency > 4)
      throw new IllegalArgumentException("Invalid media index concurrency");
    var factory = Thread.ofPlatform().name("import-auto-index-", 0).factory();
    workers.add(factory.newThread(() -> drain(service::processNextCorpus)));
    var media = Thread.ofPlatform().name("import-media-index-", 0).factory();
    for (int slot = 0; slot < mediaConcurrency; slot++) {
      workers.add(media.newThread(() -> drain(service::processNextMedia)));
    }
    workers.forEach(Thread::start);
  }

  private void drain(Supplier<ImportAutoIndexService.Dispatch> next) {
    var wakeup = TaskWakeup.global();
    var idle = TaskWakeup.MIN_IDLE;
    while (!closed) {
      long seen = wakeup.generation();
      ImportAutoIndexService.Dispatch outcome;
      try {
        outcome = next.get();
      } catch (RuntimeException unavailable) {
        // An unconfirmed dispatch remains durable for explicit restart recovery, never model retry.
        outcome = ImportAutoIndexService.Dispatch.IDLE;
      }
      if (outcome == ImportAutoIndexService.Dispatch.DISPATCHED) {
        idle = TaskWakeup.MIN_IDLE;
        continue;
      }
      try {
        idle = TaskWakeup.nextIdle(idle, wakeup.await(seen, idle));
      } catch (InterruptedException interrupted) {
        if (closed) return;
        Thread.interrupted();
      }
    }
  }

  @Override
  public void close() {
    closed = true;
    workers.forEach(Thread::interrupt);
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    try {
      for (var worker : workers) {
        long left = until - System.nanoTime();
        if (left > 0) worker.join(java.time.Duration.ofNanos(left));
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
