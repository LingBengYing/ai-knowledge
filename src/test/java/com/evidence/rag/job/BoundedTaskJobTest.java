package com.evidence.rag.job;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class BoundedTaskJobTest {
  @Test
  void delayedCancellationOfPreviousClaimCannotInterruptNextTaskOnSameConsumer() throws Exception {
    var queue = new ConcurrentLinkedQueue<Integer>();
    queue.add(1);
    queue.add(2);
    var firstStarted = new CountDownLatch(1);
    var firstRelease = new CountDownLatch(1);
    var checkEntered = new CountDownLatch(1);
    var checkRelease = new CountDownLatch(1);
    var secondStarted = new CountDownLatch(1);
    var secondRelease = new CountDownLatch(1);
    var secondDone = new CountDownLatch(1);
    var secondInterrupted = new AtomicBoolean();
    try (var job =
        new BoundedTaskJob<Integer>(
            "batch-cancellation",
            1,
            null,
            () -> Optional.ofNullable(queue.poll()),
            value -> {
              try {
                if (value == 1) {
                  firstStarted.countDown();
                  firstRelease.await();
                } else {
                  secondStarted.countDown();
                  try {
                    secondRelease.await();
                  } catch (InterruptedException error) {
                    secondInterrupted.set(true);
                  } finally {
                    secondDone.countDown();
                  }
                }
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
            },
            value -> {
              if (value == 1) {
                checkEntered.countDown();
                try {
                  checkRelease.await();
                } catch (InterruptedException error) {
                  Thread.currentThread().interrupt();
                }
                return false;
              }
              return true;
            },
            value -> {})) {
      assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
      assertTrue(checkEntered.await(5, TimeUnit.SECONDS));
      firstRelease.countDown();
      assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
      checkRelease.countDown();
      Thread.sleep(200);
      assertFalse(secondInterrupted.get());
      secondRelease.countDown();
      assertTrue(secondDone.await(5, TimeUnit.SECONDS));
    } finally {
      firstRelease.countDown();
      checkRelease.countDown();
      secondRelease.countDown();
    }
  }
}
