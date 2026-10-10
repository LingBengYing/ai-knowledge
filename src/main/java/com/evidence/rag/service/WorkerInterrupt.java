package com.evidence.rag.service;

/**
 * Lets a caller interrupt a pooled worker thread only while it is still running this task.
 *
 * <p>Once {@link #detach()} returns, a late {@link #interrupt()} cannot reach the thread, which may
 * already be running another request.
 */
final class WorkerInterrupt {
  private Thread worker;

  synchronized void attach() {
    worker = Thread.currentThread();
  }

  synchronized void interrupt() {
    if (worker != null) {
      worker.interrupt();
    }
  }

  /** Call first in the worker's finally block, before releasing admission. */
  void detach() {
    synchronized (this) {
      worker = null;
    }
    // An interrupt that landed before detach belongs to this task; do not carry it into cleanup.
    Thread.interrupted();
  }
}
