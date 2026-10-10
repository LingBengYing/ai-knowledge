package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class WorkerInterruptTest {
  @Test
  void lateCancelOfAFinishedRequestDoesNotInterruptTheNextRequestOnTheSamePooledThread()
      throws Exception {
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      var first = new WorkerInterrupt();
      pool.submit(
              () -> {
                first.attach();
                first.detach();
              })
          .get(5, TimeUnit.SECONDS);

      var secondStarted = new CountDownLatch(1);
      var cancelDelivered = new CountDownLatch(1);
      var secondInterrupted = new AtomicBoolean();
      var second = new WorkerInterrupt();
      var running =
          pool.submit(
              () -> {
                second.attach();
                secondStarted.countDown();
                try {
                  cancelDelivered.await();
                } catch (InterruptedException interrupted) {
                  secondInterrupted.set(true);
                }
                secondInterrupted.compareAndSet(false, Thread.currentThread().isInterrupted());
                second.detach();
              });
      assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
      first.interrupt();
      cancelDelivered.countDown();
      running.get(5, TimeUnit.SECONDS);

      assertFalse(secondInterrupted.get());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void cancelWhileRunningInterruptsThatTaskAndDetachClearsTheFlag() throws Exception {
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      var work = new WorkerInterrupt();
      var started = new CountDownLatch(1);
      var sawInterrupt = new AtomicBoolean();
      var flagAfterDetach = new AtomicBoolean(true);
      var running =
          pool.submit(
              () -> {
                work.attach();
                started.countDown();
                try {
                  new CountDownLatch(1).await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                  sawInterrupt.set(true);
                  Thread.currentThread().interrupt();
                }
                work.detach();
                flagAfterDetach.set(Thread.currentThread().isInterrupted());
              });
      assertTrue(started.await(5, TimeUnit.SECONDS));
      work.interrupt();
      running.get(5, TimeUnit.SECONDS);

      assertTrue(sawInterrupt.get());
      assertFalse(flagAfterDetach.get());
    } finally {
      pool.shutdownNow();
    }
  }
}
