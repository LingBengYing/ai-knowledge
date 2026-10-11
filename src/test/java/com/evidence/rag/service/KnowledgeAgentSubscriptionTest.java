package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.RetrievalSettings;
import com.evidence.rag.model.dto.AgentProtocol;
import com.evidence.rag.support.AnswerTestContext;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SSE observers park virtual sender threads; they must neither pin carriers nor grow unbounded. */
class KnowledgeAgentSubscriptionTest {
  private static final int OBSERVERS = 300;
  @TempDir Path directory;

  @Test
  void idleObserversDoNotStarveUnrelatedVirtualThreads() throws Exception {
    try (var fixture = new Fixture(directory)) {
      var observers = new ArrayList<Thread>();
      try {
        // Same loop shape as KnowledgeAgentController.events, spread over runs within the cap.
        while (observers.size() < OBSERVERS) {
          String id = fixture.completedRun();
          for (int i = 0;
              i < KnowledgeAgentService.MAX_SUBSCRIPTIONS && observers.size() < OBSERVERS;
              i++) {
            var subscription = fixture.agents.subscribe(fixture.context.owner, id);
            observers.add(
                Thread.ofVirtual()
                    .start(
                        () -> {
                          try (subscription) {
                            while (!subscription.closed()) {
                              subscription.next(Duration.ofSeconds(15));
                            }
                          } catch (InterruptedException ignored) {
                            // test cleanup
                          }
                        }));
          }
        }
        Thread.sleep(1000);
        long started = System.nanoTime();
        var probe = new CompletableFuture<Long>();
        Thread.ofVirtual().start(() -> probe.complete(System.nanoTime() - started));
        Long latency = null;
        try {
          latency = probe.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException starved) {
          // reported below
        }
        assertNotNull(latency, "a virtual thread did not run while observers were idle");
        assertTrue(latency < TimeUnit.MILLISECONDS.toNanos(500), "latency " + latency);
      } finally {
        observers.forEach(Thread::interrupt);
        for (var observer : observers) observer.join(5000);
      }
    }
  }

  @Test
  void subscriptionsPerRunAreCappedAndAClosedSlotIsReusable() throws Exception {
    try (var fixture = new Fixture(directory)) {
      String id = fixture.completedRun();
      var subscriptions = new ArrayList<KnowledgeAgentService.Subscription>();
      try {
        for (int i = 0; i < KnowledgeAgentService.MAX_SUBSCRIPTIONS; i++) {
          subscriptions.add(fixture.agents.subscribe(fixture.context.owner, id));
        }
        var rejected =
            assertThrows(
                ApplicationException.class,
                () -> fixture.agents.subscribe(fixture.context.owner, id));
        assertEquals(FailureKind.CAPACITY_EXCEEDED, rejected.kind());
        assertEquals("agent_capacity_exceeded", rejected.code());
        subscriptions.removeFirst().close();
        var replacement = fixture.agents.subscribe(fixture.context.owner, id);
        subscriptions.add(replacement);
        assertEquals("completed", replacement.next(Duration.ofSeconds(5)).run().status());
        // Another run has its own budget.
        fixture.agents.subscribe(fixture.context.owner, fixture.completedRun()).close();
      } finally {
        subscriptions.forEach(KnowledgeAgentService.Subscription::close);
      }
    }
  }

  private static final class Fixture implements AutoCloseable {
    final AnswerTestContext context;
    final ManagedTextRuntime runtime;
    final ProductHelpService retrieval;
    final KnowledgeAgentService agents;

    Fixture(Path directory) {
      context = new AnswerTestContext(directory, Duration.ofSeconds(10), 1);
      runtime =
          new ManagedTextRuntime(
              context.authority.store(),
              (version, configuration) ->
                  new TextRuntimeSnapshot(
                      version,
                      context.models,
                      context.projection,
                      context.target,
                      new AnswerService(
                          context.evidence,
                          context.models,
                          context.projection,
                          context.target,
                          Duration.ofSeconds(10),
                          1),
                      new IndexingTaskProcessor(
                          context.authority.indexing(),
                          context.owner.workspaceId(),
                          context.target,
                          Duration.ofSeconds(10),
                          ignored -> {
                            throw new AssertionError("No indexing in Agent task");
                          }),
                      () -> {}));
      var snapshot = runtime.prepare(1, ManagedTextTestFixture.configuration());
      try (var lease = context.authority.store().operationGate().tryMaintenance().orElseThrow()) {
        runtime.install(snapshot, lease, () -> {});
      }
      retrieval =
          new ProductHelpService(
              context.evidence, runtime, Duration.ofSeconds(10), RetrievalSettings::defaults);
      agents =
          new KnowledgeAgentService(
              context.evidence,
              retrieval,
              runtime,
              new KnowledgeTraceService(context.authority.store(), context.evidence),
              request -> new AgentProtocol.Proposal(true, List.of(), List.of()),
              Duration.ofSeconds(60),
              1);
    }

    /** An empty library completes without the external agent; observers still stay attached. */
    String completedRun() throws InterruptedException {
      String id = agents.start(context.owner, "问题", UUID.randomUUID().toString()).id();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while ("running".equals(agents.get(context.owner, id).status())) {
        assertTrue(System.nanoTime() < deadline, "run did not finish");
        Thread.sleep(5);
      }
      assertEquals("completed", agents.get(context.owner, id).status());
      return id;
    }

    @Override
    public void close() {
      agents.close();
      retrieval.close();
      runtime.close();
      context.close();
    }
  }
}
