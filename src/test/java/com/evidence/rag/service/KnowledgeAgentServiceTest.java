package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.*;

import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.RetrievalSettings;
import com.evidence.rag.model.dto.AgentProtocol;
import com.evidence.rag.model.dto.AgentRunResult;
import com.evidence.rag.support.AnswerTestContext;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real SQLite/source authority, test Adapter in place of the external DB-GPT process only. */
class KnowledgeAgentServiceTest {
  @TempDir Path directory;

  @Test
  void multiRoundSearchReadUsesSnapshotTopKAndProducesDurableServerCitationsAndSuggestions()
      throws Exception {
    try (var fixture = new Fixture(directory)) {
      String doc = fixture.context.publish("manual.txt", "合成灯塔指南：在项目页面点击新建项目。");
      fixture.context.publish("other.txt", "另一份合成灯塔资料。");
      fixture.context.models.ranking =
          texts ->
              java.util.stream.IntStream.range(0, texts.size())
                  .mapToObj(
                      i -> new TextModels.Ranked(i, texts.get(i).contains("点击新建项目") ? 100 : 1))
                  .toList();
      fixture.settings.set(new RetrievalSettings(1, "hybrid", "rerank", 0.5, 1, false, 0.5));
      fixture.script.set(
          request -> {
            var first = fixture.agents.search(request.runId(), auth(request), "灯塔指南");
            assertEquals(1, first.sources().size());
            fixture.settings.set(new RetrievalSettings(2, "hybrid", "rerank", 0.5, 20, true, 999));
            var second = fixture.agents.search(request.runId(), auth(request), "项目页面");
            assertEquals(
                first.sources(),
                second.sources(),
                "Task keeps one retrieval snapshot and stable source ids");
            assertEquals(doc, second.sources().getFirst().documentId());
            var read = fixture.agents.read(request.runId(), auth(request), List.of("source-1"));
            assertTrue(read.sources().getFirst().text().contains("点击新建项目"));
            return new AgentProtocol.Proposal(
                false,
                List.of(new AgentProtocol.Statement("在项目页面点击新建项目。", List.of("source-1"))),
                List.of(new AgentProtocol.Suggestion("补充操作指南", "将当前资料整理成专题页。", List.of(doc))));
          });
      String requestId = UUID.randomUUID().toString();
      var start = fixture.agents.start(fixture.context.owner, "整理灯塔指南", requestId);
      var complete = fixture.await(start.id());
      assertEquals("completed", complete.status(), () -> String.valueOf(complete.error()));
      assertEquals("answered", complete.result().status());
      assertEquals(1, complete.result().citations().size());
      var citation = complete.result().citations().getFirst();
      assertEquals(doc, citation.documentId());
      assertEquals(
          citation,
          fixture.traces.source(fixture.context.owner, complete.result().answerId(), 1).citation());
      assertEquals(List.of(doc), complete.suggestions().getFirst().documentIds());
      assertEquals(
          List.of("running", "searching", "searching", "reading", "completed"),
          complete.events().stream().map(AgentRunResult.Event::type).toList());
      assertEquals(complete, fixture.agents.start(fixture.context.owner, "整理灯塔指南", requestId));
      assertEquals(1, fixture.calls.get(), "Same request id must never consume another model run");
      assertEquals(
          "agent_request_conflict",
          assertThrows(
                  ApplicationException.class,
                  () -> fixture.agents.start(fixture.context.owner, "换一个问题", requestId))
              .code());
      assertEquals(1, fixture.context.scalar("SELECT COUNT(*) FROM knowledge_answer_traces"));
    }
  }

  @Test
  void fabricatedAndUnreadReferencesNeverBecomeFormalAnswers() throws Exception {
    try (var fixture = new Fixture(directory)) {
      fixture.context.publish("manual.txt", "合成资料原文。");
      for (String source : List.of("source-1", "source-999")) {
        fixture.script.set(
            request -> {
              fixture.agents.search(request.runId(), auth(request), "资料");
              return answer(source);
            });
        var run = fixture.await(fixture.start());
        assertEquals("failed", run.status());
        assertEquals("agent_invalid_response", run.error().code());
        assertNull(run.result());
      }
      assertEquals(0, fixture.context.scalar("SELECT COUNT(*) FROM knowledge_answer_traces"));
    }
  }

  @Test
  void suggestionMustReferenceAnOriginalReadInTheCurrentTask() throws Exception {
    try (var fixture = new Fixture(directory)) {
      fixture.context.publish("manual.txt", "合成资料原文。");
      fixture.script.set(
          request -> {
            fixture.read(request);
            return new AgentProtocol.Proposal(
                false,
                answer("source-1").statements(),
                List.of(new AgentProtocol.Suggestion("建议", "理由", List.of("foreign-document"))));
          });
      assertEquals("agent_invalid_response", fixture.await(fixture.start()).error().code());
      assertEquals(0, fixture.context.scalar("SELECT COUNT(*) FROM knowledge_answer_traces"));
    }
  }

  @Test
  void identityCapabilitiesAndMaintenanceAreBoundToActualRunningTask() throws Exception {
    try (var fixture = new Fixture(directory)) {
      fixture.context.publish("manual.txt", "合成资料原文。");
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var request = new AtomicReference<AgentProtocol.RunRequest>();
      fixture.script.set(
          value -> {
            request.set(value);
            entered.countDown();
            await(release);
            return refused();
          });
      String id = fixture.start();
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertTrue(fixture.context.authority.store().operationGate().tryMaintenance().isEmpty());
      Actor otherOrg = new Actor("different-org", "owner");
      assertEquals(
          "agent_run_not_found",
          assertThrows(ApplicationException.class, () -> fixture.agents.get(otherOrg, id)).code());
      assertThrows(ApplicationException.class, () -> fixture.agents.cancel(otherOrg, id));
      Actor otherPrincipal = new Actor(fixture.context.owner.workspaceId(), "another-user");
      assertEquals(
          "agent_run_not_found",
          assertThrows(ApplicationException.class, () -> fixture.agents.get(otherPrincipal, id))
              .code());
      assertThrows(ApplicationException.class, () -> fixture.agents.cancel(otherPrincipal, id));
      assertEquals(
          "agent_callback_denied",
          assertThrows(
                  ApplicationException.class,
                  () -> fixture.agents.search(id, "Bearer invalid", "资料"))
              .code());
      assertEquals(
          "agent_capacity_exceeded",
          assertThrows(ApplicationException.class, fixture::start).code());
      release.countDown();
      assertEquals("completed", fixture.await(id).status());
      assertThrows(
          ApplicationException.class, () -> fixture.agents.search(id, auth(request.get()), "资料"));
    }
  }

  @Test
  void cancellationStopsFurtherCallbacksAndRejectsLateAgentResultWithoutTrace() throws Exception {
    try (var fixture = new Fixture(directory)) {
      fixture.context.publish("manual.txt", "合成资料原文。");
      var entered = new CountDownLatch(1);
      var returning = new CountDownLatch(1);
      var request = new AtomicReference<AgentProtocol.RunRequest>();
      fixture.script.set(
          value -> {
            request.set(value);
            fixture.read(value);
            entered.countDown();
            try {
              Thread.sleep(10000);
            } catch (InterruptedException ignored) {
              Thread.interrupted();
            }
            returning.countDown();
            return answer("source-1");
          });
      String id = fixture.start();
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertEquals("cancelled", fixture.agents.cancel(fixture.context.owner, id).status());
      assertTrue(returning.await(5, TimeUnit.SECONDS));
      assertThrows(
          ApplicationException.class,
          () -> fixture.agents.read(id, auth(request.get()), List.of("source-1")));
      assertEquals("cancelled", fixture.agents.get(fixture.context.owner, id).status());
      assertEquals(0, fixture.context.scalar("SELECT COUNT(*) FROM knowledge_answer_traces"));
    }
  }

  @Test
  void sidecarFailureDoesNotFallbackToExistingAnswerPipelineOrLeakDiagnostic() throws Exception {
    try (var fixture = new Fixture(directory)) {
      fixture.context.publish("manual.txt", "合成资料原文。");
      fixture.script.set(
          request -> {
            throw new IllegalStateException("secret-token-provider-body");
          });
      var result = fixture.await(fixture.start());
      assertEquals("failed", result.status());
      assertEquals("agent_failed", result.error().code());
      assertFalse(result.error().message().contains("secret"));
      assertTrue(fixture.context.models.calls.isEmpty());
      assertEquals(0, fixture.context.scalar("SELECT COUNT(*) FROM knowledge_answer_traces"));
    }
  }

  @Test
  void runtimeChangesDuringTaskFailClosed() throws Exception {
    try (var fixture = new Fixture(directory)) {
      fixture.context.publish("manual.txt", "合成资料原文。");
      fixture.script.set(
          request -> {
            fixture.read(request);
            fixture.context.models.modelRevision = "changed-model-revision";
            return answer("source-1");
          });
      var result = fixture.await(fixture.start());
      assertEquals("failed", result.status());
      assertEquals("configuration_changed", result.error().code());
      assertNull(result.result());
    }
  }

  @Test
  void taskPreservesEnumeratedSidecarReasonsAndLogsOnlySafeTaskMetadata() throws Exception {
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(KnowledgeAgentService.class);
    var appender =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try (var fixture = new Fixture(directory)) {
      fixture.context.publish("manual.txt", "合成资料原文。");
      for (String code :
          List.of(
              "agent_callback_failed",
              "agent_callback_invalid",
              "agent_model_invalid",
              "agent_invalid_action",
              "agent_invalid_tool_input",
              "agent_tool_failed",
              "agent_invalid_result",
              "agent_step_limit",
              "agent_execution_failed")) {
        fixture.script.set(
            request -> {
              throw new TextModels.Failure(code);
            });
        var run = fixture.await(fixture.start());
        assertEquals("failed", run.status());
        assertEquals(code, run.error().code());
        assertNull(run.result());
        assertTrue(
            appender.list.stream()
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .anyMatch(
                    message ->
                        message.contains("run_id=" + run.id())
                            && message.contains("code=" + code)
                            && message.contains("stage=running")));
      }
      fixture.script.set(
          request -> {
            throw new TextModels.Failure("private-provider-secret");
          });
      assertEquals("agent_failed", fixture.await(fixture.start()).error().code());
      assertTrue(
          appender.list.stream()
              .allMatch(
                  event ->
                      !event.getFormattedMessage().contains("private-provider-secret")
                          && event.getThrowableProxy() == null));
      assertTrue(fixture.context.models.calls.isEmpty());
      assertEquals(0, fixture.context.scalar("SELECT COUNT(*) FROM knowledge_answer_traces"));
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void deadlineAndToolBudgetAreEnforcedByJavaIndependentlyOfSidecar() throws Exception {
    try (var fixture = new Fixture(directory, Duration.ofMillis(150))) {
      fixture.context.publish("manual.txt", "合成资料原文。");
      fixture.script.set(
          request -> {
            try {
              Thread.sleep(10000);
            } catch (InterruptedException ignored) {
              Thread.interrupted();
            }
            return refused();
          });
      var result = fixture.await(fixture.start());
      assertEquals("failed", result.status());
      assertEquals("agent_timeout", result.error().code());
    }
    try (var fixture = new Fixture(directory.resolve("budget"))) {
      fixture.context.publish("manual.txt", "合成资料原文。");
      fixture.script.set(
          request -> {
            for (int i = 0; i <= KnowledgeAgentService.MAX_STEPS * 2; i++)
              fixture.agents.search(request.runId(), auth(request), "资料");
            return refused();
          });
      assertEquals("agent_limit_exceeded", fixture.await(fixture.start()).error().code());
    }
  }

  @Test
  void closeBeforeReservedWorkerStartsReleasesLibraryAndNeverCallsAgent() throws Exception {
    try (var fixture = new Fixture(directory)) {
      var field = KnowledgeAgentService.class.getDeclaredField("executor");
      field.setAccessible(true);
      var executor = (java.util.concurrent.ExecutorService) field.get(fixture.agents);
      var occupied = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      executor.execute(
          () -> {
            occupied.countDown();
            await(release);
          });
      assertTrue(occupied.await(5, TimeUnit.SECONDS));
      String id = fixture.start();
      assertFalse(fixture.context.authority.store().operationGate().isIdle());
      fixture.agents.close();
      release.countDown();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
      assertEquals("cancelled", fixture.agents.get(fixture.context.owner, id).status());
      assertEquals(0, fixture.calls.get());
      assertTrue(
          fixture.context.authority.store().operationGate().isIdle(),
          "Closing before the reserved body starts must not strand the library operation lease");
    }
  }

  private static String auth(AgentProtocol.RunRequest request) {
    return "Bearer " + request.callbackToken();
  }

  private static AgentProtocol.Proposal answer(String source) {
    return new AgentProtocol.Proposal(
        false, List.of(new AgentProtocol.Statement("资料中的有据结果。", List.of(source))), List.of());
  }

  private static AgentProtocol.Proposal refused() {
    return new AgentProtocol.Proposal(true, List.of(), List.of());
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private static final class Fixture implements AutoCloseable {
    final AnswerTestContext context;
    final AtomicReference<RetrievalSettings> settings =
        new AtomicReference<>(RetrievalSettings.defaults());
    final AtomicReference<Function<AgentProtocol.RunRequest, AgentProtocol.Proposal>> script =
        new AtomicReference<>(request -> refused());
    final AtomicInteger calls = new AtomicInteger();
    final ManagedTextRuntime runtime;
    final ProductHelpService retrieval;
    final KnowledgeTraceService traces;
    final KnowledgeAgentService agents;

    Fixture(Path directory) {
      this(directory, Duration.ofSeconds(10));
    }

    Fixture(Path directory, Duration timeout) {
      context = new AnswerTestContext(directory, Duration.ofSeconds(10), 1);
      runtime =
          new ManagedTextRuntime(
              context.authority.store(),
              (version, configuration) -> {
                var answers =
                    new AnswerService(
                        context.evidence,
                        context.models,
                        context.projection,
                        context.target,
                        Duration.ofSeconds(10),
                        1);
                var indexing =
                    new IndexingTaskProcessor(
                        context.authority.indexing(),
                        context.owner.workspaceId(),
                        context.target,
                        Duration.ofSeconds(10),
                        ignored -> {
                          throw new AssertionError("No indexing in Agent task");
                        });
                return new TextRuntimeSnapshot(
                    version,
                    context.models,
                    context.projection,
                    context.target,
                    answers,
                    indexing,
                    () -> {});
              });
      var snapshot = runtime.prepare(1, ManagedTextTestFixture.configuration());
      try (var lease = context.authority.store().operationGate().tryMaintenance().orElseThrow()) {
        runtime.install(snapshot, lease, () -> {});
      }
      retrieval =
          new ProductHelpService(
              context.evidence, runtime, Duration.ofSeconds(10), 1, settings::get);
      traces = new KnowledgeTraceService(context.authority.store(), context.evidence);
      agents =
          new KnowledgeAgentService(
              context.evidence,
              retrieval,
              runtime,
              traces,
              request -> {
                calls.incrementAndGet();
                return script.get().apply(request);
              },
              timeout,
              1);
    }

    void read(AgentProtocol.RunRequest request) {
      var result = agents.search(request.runId(), auth(request), "资料");
      agents.read(
          request.runId(),
          auth(request),
          result.sources().stream().map(AgentProtocol.SearchSource::sourceId).toList());
    }

    String start() {
      return agents.start(context.owner, "请整理资料", UUID.randomUUID().toString()).id();
    }

    AgentRunResult await(String id) throws Exception {
      long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
      AgentRunResult result;
      do {
        result = agents.get(context.owner, id);
        if (!"running".equals(result.status())) return result;
        Thread.sleep(10);
      } while (System.nanoTime() < deadline);
      fail("Agent did not reach terminal state");
      return result;
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
