package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Phase 2 #9 and #10: claim order across batches and the in-process queue wake-up. */
class BatchThroughputDomainTest {
  private static FairQueue.Candidate c(String task, String batch) {
    return new FairQueue.Candidate(task, batch);
  }

  @Test
  void interactiveTasksComeFirstThenBatchesRoundRobinFromTheOneAfterTheLastServed() {
    var queue = new FairQueue();
    var heads = List.of(c("a1", "A"), c("a2", "A"), c("a3", "A"), c("b1", "B"), c("c1", "C"));
    assertEquals(
        List.of(c("i1", null), c("a1", "A"), c("b1", "B"), c("c1", "C"), c("a2", "A")),
        queue.order(List.of("i1"), heads, 5));
    queue.served(c("a1", "A"));
    assertEquals(
        List.of(c("b1", "B"), c("c1", "C"), c("a1", "A"), c("a2", "A"), c("a3", "A")),
        queue.order(List.of(), heads, 10));
    queue.served(c("b1", "B"));
    assertEquals("c1", queue.order(List.of(), heads, 1).getFirst().taskId());
    queue.served(c("c1", "C"));
    assertEquals("a1", queue.order(List.of(), heads, 1).getFirst().taskId());
    // An interactive claim does not move the batch cursor.
    queue.served(c("i1", null));
    assertEquals("a1", queue.order(List.of(), heads, 1).getFirst().taskId());
    // The last-served batch finished: the next batch after it in order still comes first.
    queue.served(c("b9", "B"));
    assertEquals(
        "c1", queue.order(List.of(), List.of(c("a1", "A"), c("c1", "C")), 1).getFirst().taskId());
    assertEquals(List.of(c("i1", null)), queue.order(List.of("i1", "i2"), heads, 1));
  }

  @Test
  void aBigBatchCannotStarveAnotherBatchOrInteractiveWork() {
    var queue = new FairQueue();
    var heads = new java.util.ArrayList<FairQueue.Candidate>();
    for (int i = 0; i < 4; i++) heads.add(c("big" + i, "big"));
    heads.add(c("small0", "small"));
    var served = new java.util.ArrayList<String>();
    for (int round = 0; round < 4; round++) {
      var first = queue.order(List.of(), heads, 1).getFirst();
      queue.served(first);
      served.add(first.batchId());
    }
    assertEquals(List.of("big", "small", "big", "small"), served);
  }

  @Test
  void wakeupShortensAnIdleWaitAndBacksOffWithoutSignals() throws Exception {
    var wakeup = new TaskWakeup();
    long seen = wakeup.generation();
    long started = System.nanoTime();
    assertFalse(wakeup.await(seen, Duration.ofMillis(50)));
    assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() >= 45);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var waiting = executor.submit(() -> wakeup.await(seen, Duration.ofSeconds(10)));
      Thread.sleep(50);
      long signalled = System.nanoTime();
      wakeup.signal();
      assertTrue(waiting.get(5, TimeUnit.SECONDS));
      assertTrue(Duration.ofNanos(System.nanoTime() - signalled).toMillis() < 1000);
    }
    assertTrue(wakeup.await(seen, Duration.ofMillis(1)), "a signal before waiting is not lost");
    var idle = TaskWakeup.MIN_IDLE;
    for (int i = 0; i < 10; i++) idle = TaskWakeup.nextIdle(idle, false);
    assertEquals(TaskWakeup.MAX_IDLE, idle);
    assertEquals(TaskWakeup.MIN_IDLE, TaskWakeup.nextIdle(idle, true));
    assertEquals(Duration.ofMillis(200), TaskWakeup.nextIdle(TaskWakeup.MIN_IDLE, false));
  }
}
