package com.evidence.rag.job;

import com.evidence.rag.model.domain.LibraryOperationGate;
import com.evidence.rag.model.domain.TaskWakeup;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Fixed consumers over a durable queue, without an in-memory task backlog or busy polling. */
final class BoundedTaskJob<C> implements AutoCloseable {
  private final Supplier<Optional<C>> claim;
  private final Consumer<C> process;
  private final Predicate<C> current;
  private final Consumer<C> fail;
  private final LibraryOperationGate operations;
  private final ScheduledExecutorService scheduler;
  private final ConcurrentHashMap<Thread, C> running = new ConcurrentHashMap<>();
  private final List<Thread> consumers = new ArrayList<>();
  private volatile boolean closed;

  BoundedTaskJob(
      String name,
      int concurrency,
      LibraryOperationGate operations,
      Supplier<Optional<C>> claim,
      Consumer<C> process,
      Predicate<C> current,
      Consumer<C> fail) {
    if (concurrency < 1 || concurrency > 4)
      throw new IllegalArgumentException("Invalid concurrency");
    this.operations = operations;
    this.claim = claim;
    this.process = process;
    this.current = current;
    this.fail = fail;
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name(name + "-stale-", 0).factory());
    scheduler.scheduleWithFixedDelay(this::cancelStale, 100, 100, TimeUnit.MILLISECONDS);
    var factory = Thread.ofPlatform().name(name + "-", 0).factory();
    for (int slot = 0; slot < concurrency; slot++) {
      var consumer = factory.newThread(this::consume);
      consumers.add(consumer);
      consumer.start();
    }
  }

  /**
   * Claims back to back while work is found; when the queue is empty, waits for a committed task
   * change or a growing idle timeout (100 ms up to 1.5 s) instead of polling SQLite every 100 ms.
   */
  private void consume() {
    var idle = TaskWakeup.MIN_IDLE;
    var wakeup = TaskWakeup.global();
    while (!closed) {
      long seen = wakeup.generation();
      boolean worked = next();
      if (worked) {
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

  private boolean next() {
    if (closed) return false;
    if (operations == null) {
      return execute();
    }
    var admission = operations.tryOperation();
    if (admission.isEmpty()) return false;
    try (var ignored = admission.orElseThrow()) {
      return execute();
    }
  }

  private boolean execute() {
    C work = null;
    Thread thread = Thread.currentThread();
    try {
      if (closed) return false;
      var next = claim.get();
      if (next.isEmpty()) return false;
      work = next.orElseThrow();
      synchronized (running) {
        running.put(thread, work);
      }
      if (closed) thread.interrupt();
      process.accept(work);
      return true;
    } catch (RuntimeException failure) {
      if (work != null)
        try {
          fail.accept(work);
        } catch (RuntimeException ignored) {
          /* Keep polling. */
        }
      return work != null;
    } finally {
      synchronized (running) {
        running.remove(thread);
        Thread.interrupted();
      }
    }
  }

  private void cancelStale() {
    running.forEach(
        (thread, work) -> {
          boolean valid;
          try {
            valid = current.test(work);
          } catch (RuntimeException unavailable) {
            valid = false;
          }
          if (!valid)
            synchronized (running) {
              if (running.get(thread) == work) thread.interrupt();
            }
        });
  }

  @Override
  public void close() {
    closed = true;
    running.keySet().forEach(Thread::interrupt);
    consumers.forEach(Thread::interrupt);
    scheduler.shutdownNow();
    try {
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      for (var consumer : consumers) {
        long left = until - System.nanoTime();
        if (left > 0) consumer.join(java.time.Duration.ofNanos(left));
      }
      scheduler.awaitTermination(Math.max(1, until - System.nanoTime()), TimeUnit.NANOSECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
