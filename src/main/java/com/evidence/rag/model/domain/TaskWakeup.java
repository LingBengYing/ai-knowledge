package com.evidence.rag.model.domain;

import java.time.Duration;

/**
 * In-process wake-up for durable queue consumers. The authority store signals after committing a
 * transaction that changed a task table; idle consumers wait for that signal with a bounded,
 * growing idle wait instead of opening a SQLite transaction every 100 ms. The signal only shortens
 * a wait: consumers always claim from SQLite, so a lost or spurious signal never loses or invents
 * work.
 */
public final class TaskWakeup {
  /** First idle wait after an empty claim. */
  public static final Duration MIN_IDLE = Duration.ofMillis(100);

  /**
   * Longest idle wait: work that no commit signals (another process, a gate) waits at most this.
   */
  public static final Duration MAX_IDLE = Duration.ofMillis(1500);

  private static final TaskWakeup GLOBAL = new TaskWakeup();

  private long generation;

  public static TaskWakeup global() {
    return GLOBAL;
  }

  public synchronized void signal() {
    generation++;
    notifyAll();
  }

  public synchronized long generation() {
    return generation;
  }

  /**
   * Waits until a signal newer than {@code seen} or until {@code timeout}; returns whether a signal
   * arrived.
   */
  public synchronized boolean await(long seen, Duration timeout) throws InterruptedException {
    long until = System.nanoTime() + timeout.toNanos();
    while (generation == seen) {
      long left = until - System.nanoTime();
      if (left <= 0) {
        return false;
      }
      long millis = Math.max(1, left / 1_000_000);
      wait(millis);
    }
    return true;
  }

  /** The next idle wait: reset after a signal, otherwise doubled up to {@link #MAX_IDLE}. */
  public static Duration nextIdle(Duration current, boolean signalled) {
    if (signalled) {
      return MIN_IDLE;
    }
    var doubled = current.multipliedBy(2);
    return doubled.compareTo(MAX_IDLE) > 0 ? MAX_IDLE : doubled;
  }
}
