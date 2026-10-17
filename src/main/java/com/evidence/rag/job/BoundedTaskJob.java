package com.evidence.rag.job;

import com.evidence.rag.model.domain.LibraryOperationGate;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Fixed consumers over a durable queue, without an in-memory task backlog. */
final class BoundedTaskJob<C> implements AutoCloseable {
  private final Supplier<Optional<C>> claim;
  private final Consumer<C> process;
  private final Predicate<C> current;
  private final Consumer<C> fail;
  private final LibraryOperationGate operations;
  private final ScheduledExecutorService scheduler;
  private final ConcurrentHashMap<Thread, C> running = new ConcurrentHashMap<>();
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
        Executors.newScheduledThreadPool(
            concurrency + 1, Thread.ofPlatform().name(name + "-", 0).factory());
    for (int slot = 0; slot < concurrency; slot++)
      scheduler.scheduleWithFixedDelay(this::next, 100, 100, TimeUnit.MILLISECONDS);
    scheduler.scheduleWithFixedDelay(this::cancelStale, 100, 100, TimeUnit.MILLISECONDS);
  }

  private void next() {
    if (closed) return;
    if (operations == null) {
      execute();
      return;
    }
    var admission = operations.tryOperation();
    if (admission.isEmpty()) return;
    try (var ignored = admission.orElseThrow()) {
      execute();
    }
  }

  private void execute() {
    C work = null;
    Thread thread = Thread.currentThread();
    try {
      if (closed) return;
      var next = claim.get();
      if (next.isEmpty()) return;
      work = next.orElseThrow();
      synchronized (running) {
        running.put(thread, work);
      }
      if (closed) thread.interrupt();
      process.accept(work);
    } catch (RuntimeException failure) {
      if (work != null)
        try {
          fail.accept(work);
        } catch (RuntimeException ignored) {
          /* Keep polling. */
        }
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
    scheduler.shutdownNow();
    try {
      scheduler.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
