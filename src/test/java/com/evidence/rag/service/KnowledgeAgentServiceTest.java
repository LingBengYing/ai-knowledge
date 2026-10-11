package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.*;

import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.controller.KnowledgeAgentCallbackController;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.RetrievalSettings;
import com.evidence.rag.model.dto.AgentMessage;
import com.evidence.rag.model.dto.AgentModelDelta;
import com.evidence.rag.model.dto.AgentProtocol;
import com.evidence.rag.model.dto.AgentRunResult;
import com.evidence.rag.support.AnswerTestContext;
import com.evidence.rag.web.ProblemHandler;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real SQLite/source authority, test Adapter in place of the external native agent process only.
 */
class KnowledgeAgentServiceTest {
  @TempDir Path directory;

  @Test
  void streamPublishesModelPrefixesBeforeCompletionAndReconnectDoesNotReplayWork()
      throws Exception {
    try (var fixture = new Fixture(directory)) {
      fixture.context.publish("stream.txt", "合成流式资料。");
      String conversation = fixture.conversations.create(fixture.context.owner, "流式测试").id();
      var first = new CountDownLatch(1);
      var second = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      fixture.context.models.agentStreaming =
          (messages, listener) -> {
            listener.accept(new AgentModelDelta("正在查阅🌲", "资料中的"));
            first.countDown();
            await(second);
            listener.accept(new AgentModelDelta("并组织回答。", "有据结果。"));
            await(release);
            return streamingMessage("正在查阅🌲并组织回答。");
          };
      fixture.script.set(
          request -> {
            fixture.read(request);
            fixture.agents.model(
                request.runId(),
                auth(request),
                new AgentProtocol.ModelRequest(List.of(new AgentMessage("user", "资料"))));
            return answer("source-1");
          });
      String id =
          fixture
              .agents
              .start(fixture.context.owner, "请整理资料", UUID.randomUUID().toString(), conversation)
              .id();
      assertTrue(first.await(5, TimeUnit.SECONDS));
      try (var stream = fixture.agents.subscribe(fixture.context.owner, id)) {
        var initial = stream.next(Duration.ofSeconds(5));
        assertEquals("running", initial.run().status());
        assertEquals("资料中的", initial.run().preview());
        assertEquals("正在查阅🌲", initial.run().events().getLast().details().reasoning());
        assertNull(initial.run().result());
        second.countDown();
        var updated = stream.next(Duration.ofSeconds(5));
        assertTrue(updated.revision() > initial.revision());
        assertEquals("资料中的有据结果。", updated.run().preview());
        assertEquals("正在查阅🌲并组织回答。", updated.run().events().getLast().details().reasoning());
      }
      assertEquals(
          "running",
          fixture.agents.get(fixture.context.owner, id).status(),
          "Socket close is not task cancellation");
      try (var resumed = fixture.agents.subscribe(fixture.context.owner, id)) {
        assertEquals("资料中的有据结果。", resumed.next(Duration.ofSeconds(5)).run().preview());
        release.countDown();
        com.evidence.rag.model.dto.AgentRunStreamSnapshot terminal;
        do {
          terminal = resumed.next(Duration.ofSeconds(5));
          assertNotNull(terminal);
        } while ("running".equals(terminal.run().status()));
        assertEquals("completed", terminal.run().status());
        assertEquals("", terminal.run().preview());
        assertEquals("资料中的有据结果。 [1]", terminal.run().result().answer());
        assertEquals(
            "completed",
            fixture
                .conversations
                .get(fixture.context.owner, conversation)
                .turns()
                .getFirst()
                .status(),
            "Terminal publication follows durable conversation commit");
      }
      assertEquals(1, fixture.calls.get());
      assertEquals(1, fixture.context.models.calls.stream().filter("agent"::equals).count());
      try (var finished = fixture.agents.subscribe(fixture.context.owner, id)) {
        assertEquals("completed", finished.next(Duration.ofSeconds(5)).run().status());
      }
    }
  }

  @Test
  void streamingCancellationClearsPreviewAndRejectsLateDeltas() throws Exception {
    try (var fixture = new Fixture(directory)) {
      fixture.context.publish("stream.txt", "合成资料。");
      var first = new CountDownLatch(1);
      var late = new CountDownLatch(1);
      fixture.context.models.agentStreaming =
          (messages, listener) -> {
            listener.accept(new AgentModelDelta("已返回的思考", "尚未校验的回答"));
            first.countDown();
            try {
              Thread.sleep(10000);
            } catch (InterruptedException ignored) {
              Thread.interrupted();
            }
            try {
              listener.accept(new AgentModelDelta("迟到不可见", "迟到不可见"));
            } finally {
              late.countDown();
            }
            return streamingMessage("迟到不可见");
          };
      fixture.script.set(
          request -> {
            fixture.agents.model(
                request.runId(),
                auth(request),
                new AgentProtocol.ModelRequest(List.of(new AgentMessage("user", "资料"))));
            return refused();
          });
      String id = fixture.start();
      assertTrue(first.await(5, TimeUnit.SECONDS));
      assertThrows(
          ApplicationException.class,
          () -> fixture.agents.subscribe(new Actor("org-main", "another"), id));
      try (var stream = fixture.agents.subscribe(fixture.context.owner, id)) {
        assertEquals("尚未校验的回答", stream.next(Duration.ofSeconds(5)).run().preview());
        fixture.agents.cancel(fixture.context.owner, id);
        var terminal = stream.next(Duration.ofSeconds(5));
        assertEquals("cancelled", terminal.run().status());
        assertEquals("", terminal.run().preview());
        assertNull(terminal.run().result());
        assertTrue(late.await(5, TimeUnit.SECONDS));
        assertFalse(
            new JsonMapper()
                .writeValueAsString(fixture.agents.get(fixture.context.owner, id))
                .contains("迟到不可见"));
        assertNull(
            stream.next(Duration.ofMillis(20)),
            "Late provider output never publishes after terminal");
      }
    }
  }

  private static AgentMessage streamingMessage(String reasoning) {
    return new AgentMessage(
        "assistant",
        null,
        List.of(
            new AgentMessage.ToolCall(
                "call-finish", "function", new AgentMessage.FunctionCall("terminate", "{}"))),
        null,
        reasoning);
  }

  @Test
  void priorModelRoundListenerCannotWriteIntoNextRound() throws Exception {
    try (var fixture = new Fixture(directory)) {
      fixture.context.publish("stream.txt", "合成资料。");
      var previous = new AtomicReference<java.util.function.Consumer<AgentModelDelta>>();
      fixture.context.models.agentStreaming =
          (messages, listener) -> {
            if (previous.get() == null) {
              previous.set(listener);
              listener.accept(new AgentModelDelta("第一轮", ""));
              return streamingMessage("第一轮");
            }
            listener.accept(new AgentModelDelta("第二轮", ""));
            assertThrows(
                ApplicationException.class,
                () -> previous.get().accept(new AgentModelDelta("旧轮迟到", "不能混入")));
            return streamingMessage("第二轮");
          };
      fixture.script.set(
          request -> {
            var input = new AgentProtocol.ModelRequest(List.of(new AgentMessage("user", "资料")));
            fixture.agents.model(request.runId(), auth(request), input);
            fixture.agents.model(request.runId(), auth(request), input);
            return refused();
          });
      var result = fixture.await(fixture.start());
      assertEquals("completed", result.status());
      assertEquals("第一轮", result.events().get(1).details().reasoning());
      assertEquals("第二轮", result.events().get(2).details().reasoning());
      assertFalse(new JsonMapper().writeValueAsString(result).contains("旧轮迟到"));
    }
  }

  @Test
  void persistenceFailureClosesStreamWithoutPublishingSuccessfulTerminal() throws Exception {
    try (var fixture = new Fixture(directory, Duration.ofSeconds(10), true)) {
      fixture.context.publish("stream.txt", "合成资料。");
      String conversation = fixture.conversations.create(fixture.context.owner, "持久化故障").id();
      var ready = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      fixture.script.set(
          request -> {
            fixture.read(request);
            ready.countDown();
            await(release);
            return answer("source-1");
          });
      String id =
          fixture
              .agents
              .start(fixture.context.owner, "资料", UUID.randomUUID().toString(), conversation)
              .id();
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      try (var stream = fixture.agents.subscribe(fixture.context.owner, id)) {
        assertEquals("running", stream.next(Duration.ofSeconds(5)).run().status());
        release.countDown();
        var failed = fixture.await(id);
        assertEquals("failed", failed.status());
        assertNull(failed.result());
        assertEquals("agent_failed", failed.error().code());
        assertTrue(stream.closed());
        assertNull(stream.next(Duration.ofMillis(20)));
        assertThrows(
            ApplicationException.class, () -> fixture.agents.subscribe(fixture.context.owner, id));
        assertEquals(
            "running",
            fixture
                .conversations
                .get(fixture.context.owner, conversation)
                .turns()
                .getFirst()
                .status(),
            "Failed transaction must not invent a durable success");
      }
    }
  }

  @Test
  void failedTerminalWriteStillReleasesConversationForNextQuestionAndDelete() throws Exception {
    try (var fixture = new Fixture(directory, Duration.ofSeconds(10), 1)) {
      fixture.context.publish("stream.txt", "合成资料。");
      String conversation = fixture.conversations.create(fixture.context.owner, "释放").id();
      fixture.script.set(
          request -> {
            fixture.read(request);
            return answer("source-1");
          });
      String id =
          fixture
              .agents
              .start(fixture.context.owner, "资料", UUID.randomUUID().toString(), conversation)
              .id();
      var failed = fixture.await(id);
      assertEquals("failed", failed.status());
      assertEquals("agent_failed", failed.error().code());
      var detail = fixture.conversations.get(fixture.context.owner, conversation);
      assertNull(detail.conversation().activeRunId(), "conversation must not stay busy");
      var turn = detail.turns().getFirst();
      assertEquals("failed", turn.status());
      assertNull(turn.result().result(), "the uncommitted answer is never recorded as a success");
      assertEquals("agent_failed", turn.result().error().code());
      var next =
          fixture.await(
              fixture
                  .agents
                  .start(fixture.context.owner, "下一问", UUID.randomUUID().toString(), conversation)
                  .id());
      assertEquals("completed", next.status());
      assertEquals(
          List.of("failed", "completed"),
          fixture.conversations.get(fixture.context.owner, conversation).turns().stream()
              .map(com.evidence.rag.model.dto.ConversationResult.Turn::status)
              .toList());
      fixture.conversations.delete(fixture.context.owner, conversation);
    }
  }

  @Test
  void documentCleanupRedactsArchivedAgentTurnThatCitedIt() throws Exception {
    try (var fixture = new Fixture(directory)) {
      String documentId = fixture.context.publish("secret.txt", "合成机密句子需要被清除。");
      String conversation = fixture.conversations.create(fixture.context.owner, "引用").id();
      fixture.script.set(
          request -> {
            fixture.read(request);
            return answer("source-1");
          });
      var answered =
          fixture.await(
              fixture
                  .agents
                  .start(fixture.context.owner, "资料", UUID.randomUUID().toString(), conversation)
                  .id());
      assertEquals("answered", answered.result().status());
      String retained =
          "SELECT COUNT(*) FROM knowledge_conversation_turns WHERE result_json LIKE '%合成机密句子%' OR result_json LIKE '%secret.txt%'";
      assertEquals(1, fixture.context.scalar(retained));
      var store = fixture.context.authority.store();
      var cleanup =
          new DocumentCleanupService(
              store,
              new com.evidence.rag.repository.DocumentCleanupRepository(store),
              new com.evidence.rag.repository.DocumentLifecycleRepository(store),
              new com.evidence.rag.repository.ManagementRepository(store),
              new com.evidence.rag.security.authorization.DocumentPermissionPolicy(),
              attempts ->
                  new com.evidence.rag.client.vector.ProjectionCleanup.Result(
                      "completed", "completed", "completed", null));
      cleanup.request(fixture.context.owner, documentId);
      assertTrue(cleanup.runOnce());
      assertEquals(0, fixture.context.scalar(retained));
      var turn = fixture.conversations.get(fixture.context.owner, conversation).turns().getFirst();
      assertEquals("资料", turn.question());
      assertEquals("completed", turn.status());
      assertEquals(
          com.evidence.rag.repository.ConversationRepository.REDACTED_ANSWER,
          turn.result().result().answer());
      assertEquals("abstained", turn.result().result().status());
      assertTrue(turn.result().result().citations().isEmpty());
    }
  }

  @Test
  void rapidModelDeltasAreCoalescedAndTheLatestPreviewIsStillDelivered() throws Exception {
    try (var fixture = new Fixture(directory)) {
      fixture.context.publish("stream.txt", "合成资料。");
      var subscribed = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      int deltas = 500;
      fixture.context.models.agentStreaming =
          (messages, listener) -> {
            await(subscribed);
            for (int i = 0; i < deltas; i++) listener.accept(new AgentModelDelta("", "字"));
            await(release);
            return streamingMessage("思考");
          };
      fixture.script.set(
          request -> {
            fixture.agents.model(
                request.runId(),
                auth(request),
                new AgentProtocol.ModelRequest(List.of(new AgentMessage("user", "资料"))));
            return refused();
          });
      String id = fixture.start();
      try (var stream = fixture.agents.subscribe(fixture.context.owner, id)) {
        long first = stream.next(Duration.ofSeconds(5)).revision();
        subscribed.countDown();
        com.evidence.rag.model.dto.AgentRunStreamSnapshot latest;
        do {
          latest = stream.next(Duration.ofSeconds(5));
          assertNotNull(latest, "the trailing flush must deliver the last preview");
        } while (latest.run().preview().length() < deltas);
        assertEquals("字".repeat(deltas), latest.run().preview());
        assertEquals("running", latest.run().status(), "delivered before the model call ends");
        assertTrue(
            latest.revision() - first < deltas / 10,
            "published " + (latest.revision() - first) + " snapshots for " + deltas + " deltas");
        release.countDown();
        assertEquals("completed", fixture.await(id).status());
      }
    }
  }

  @Test
  void actualServletSseFlushesReasoningAndAnswerWhileProviderStillBlocked() throws Exception {
    try (var fixture = new Fixture(directory.resolve("authority"));
        var client = java.net.http.HttpClient.newHttpClient()) {
      fixture.context.publish("stream.txt", "合成资料。");
      var emit = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      fixture.context.models.agentStreaming =
          (messages, listener) -> {
            await(emit);
            listener.accept(new AgentModelDelta("实际 HTTP 首段思考🌲", "实际 HTTP 回答前缀"));
            await(release);
            return streamingMessage("实际 HTTP 首段思考🌲");
          };
      fixture.script.set(
          request -> {
            fixture.agents.model(
                request.runId(),
                auth(request),
                new AgentProtocol.ModelRequest(List.of(new AgentMessage("user", "资料"))));
            return refused();
          });
      var application =
          new org.springframework.boot.SpringApplication(com.evidence.rag.RagApplication.class);
      application.addInitializers(
          context ->
              context.getBeanFactory().registerSingleton("streamingTestAgent", fixture.agents));
      try (var web =
          application.run(
              "--server.port=0",
              "--server.address=127.0.0.1",
              "--rag.environment=test",
              "--rag.auth-mode=development_headers",
              "--rag.knowledge-agent.enabled=false",
              "--rag.data-directory=" + directory.resolve("web"))) {
        String id = fixture.start();
        var uri =
            java.net.URI.create(
                "http://127.0.0.1:"
                    + web.getEnvironment().getProperty("local.server.port")
                    + "/v1/knowledge-agent/runs/"
                    + id
                    + "/events");
        assertEquals(
            422,
            client
                .send(
                    java.net.http.HttpRequest.newBuilder(uri).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString())
                .statusCode());
        assertEquals(
            404,
            client
                .send(
                    java.net.http.HttpRequest.newBuilder(uri)
                        .header("X-Workspace-Id", "org-main")
                        .header("X-Principal-Id", "another")
                        .GET()
                        .build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString())
                .statusCode());
        var response =
            client.send(
                java.net.http.HttpRequest.newBuilder(uri)
                    .header("X-Workspace-Id", "org-main")
                    .header("X-Principal-Id", "owner")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        assertTrue(
            response
                .headers()
                .firstValue("content-type")
                .orElseThrow()
                .startsWith("text/event-stream"));
        assertEquals("no", response.headers().firstValue("x-accel-buffering").orElseThrow());
        try (var reader =
                new java.io.BufferedReader(
                    new java.io.InputStreamReader(
                        response.body(), java.nio.charset.StandardCharsets.UTF_8));
            var readers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
          var initial = readers.submit(() -> readSseFrame(reader)).get(5, TimeUnit.SECONDS);
          assertTrue(initial.contains("event:snapshot"));
          emit.countDown();
          String prefix;
          do {
            prefix = readers.submit(() -> readSseFrame(reader)).get(5, TimeUnit.SECONDS);
          } while (!prefix.contains("实际 HTTP 回答前缀"));
          assertTrue(prefix.contains("实际 HTTP 首段思考🌲"));
          assertEquals(
              1L,
              release.getCount(),
              "The model round has not ended when bytes reach the HTTP reader");
          release.countDown();
          String terminal;
          do {
            terminal = readers.submit(() -> readSseFrame(reader)).get(5, TimeUnit.SECONDS);
          } while (!terminal.contains("event:terminal"));
          assertTrue(terminal.contains("\"preview\":\"\""));
          assertEquals("completed", fixture.agents.get(fixture.context.owner, id).status());
        } finally {
          release.countDown();
          emit.countDown();
        }
      } finally {
        release.countDown();
        emit.countDown();
      }
    }
  }

  private static String readSseFrame(java.io.BufferedReader reader) throws java.io.IOException {
    var frame = new StringBuilder();
    for (String line; (line = reader.readLine()) != null; ) {
      if (line.isEmpty() && !frame.isEmpty()) return frame.toString();
      frame.append(line).append('\n');
    }
    throw new java.io.EOFException("SSE ended before the expected frame");
  }

  @Test
  void conversationPersistsAnswerAndPassesPriorDialogueWithoutReusingEvidenceAuthority()
      throws Exception {
    try (var fixture = new Fixture(directory)) {
      fixture.context.publish("manual.txt", "合成资料说明。");
      String conversationId = fixture.conversations.create(fixture.context.owner, "多轮问答").id();
      fixture.script.set(
          request -> {
            assertTrue(request.history().isEmpty());
            fixture.read(request);
            return answer("source-1");
          });
      String firstRequest = UUID.randomUUID().toString();
      var first =
          fixture.await(
              fixture
                  .agents
                  .start(fixture.context.owner, "第一问", firstRequest, conversationId)
                  .id());
      assertEquals("answered", first.result().status());
      assertEquals(
          first,
          fixture
              .conversations
              .get(fixture.context.owner, conversationId)
              .turns()
              .getFirst()
              .result());
      fixture.script.set(
          request -> {
            assertEquals("第一问", request.history().getFirst().content());
            assertEquals(first.result().answer(), request.history().getLast().content());
            assertTrue(request.summary().isEmpty());
            // A historical source-1 has no authority in this new run until searched and read again.
            return answer("source-1");
          });
      var second =
          fixture.await(
              fixture
                  .agents
                  .start(
                      fixture.context.owner, "继续追问", UUID.randomUUID().toString(), conversationId)
                  .id());
      assertEquals("agent_invalid_response", second.error().code());
      assertEquals(
          "failed",
          fixture
              .conversations
              .get(fixture.context.owner, conversationId)
              .turns()
              .getLast()
              .status());
      assertNull(
          fixture
              .conversations
              .get(fixture.context.owner, conversationId)
              .conversation()
              .activeRunId());
      assertEquals(
          first, fixture.agents.start(fixture.context.owner, "第一问", firstRequest, conversationId));
      assertEquals(2, fixture.calls.get());
      fixture.conversations.delete(fixture.context.owner, conversationId);
      assertEquals(
          first.result().citations().getFirst(),
          fixture.traces.source(fixture.context.owner, first.result().answerId(), 1).citation(),
          "Deleting a conversation must not delete original documents or the citation authority");
    }
  }

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
      var searchDetails = complete.events().get(1).details();
      assertEquals("completed", searchDetails.status());
      assertEquals("knowledge_search", searchDetails.toolName());
      assertEquals("灯塔指南", searchDetails.query());
      assertEquals(1, searchDetails.resultCount());
      assertEquals(1, searchDetails.documentCount());
      assertTrue(searchDetails.elapsedMs() >= 0);
      var source = searchDetails.sources().getFirst();
      assertEquals("source-1", source.sourceId());
      assertEquals(doc, source.documentId());
      assertEquals(citation.revisionId(), source.revisionId());
      assertEquals("manual.txt", source.title());
      assertEquals("document_text", source.kind());
      assertEquals(citation.page(), source.page());
      assertNull(source.startMs());
      assertEquals(searchDetails.sources(), complete.events().get(3).details().sources());
      assertEquals("knowledge_read", complete.events().get(3).details().toolName());
      assertFalse(new JsonMapper().writeValueAsString(searchDetails).contains("点击新建项目"));
    }
  }

  @Test
  void modelEventShowsOnlyReturnedReasoningAndSelectedToolsWithoutChangingNativeMessage()
      throws Exception {
    try (var fixture = new Fixture(directory)) {
      fixture.context.publish("manual.txt", "合成原件正文不应写入事件。");
      String raw = "🌲".repeat(16001);
      var nativeMessage =
          new AgentMessage(
              "assistant",
              null,
              List.of(
                  new AgentMessage.ToolCall(
                      "call-search",
                      "function",
                      new AgentMessage.FunctionCall("knowledge_search", "{\"query\":\"资料\"}"))),
              null,
              raw);
      fixture.context.models.agent = messages -> nativeMessage;
      fixture.script.set(
          request -> {
            var returned =
                fixture.agents.model(
                    request.runId(),
                    auth(request),
                    new AgentProtocol.ModelRequest(List.of(new AgentMessage("user", "资料"))));
            assertEquals(raw, returned.message().reasoningContent());
            fixture.read(request);
            return answer("source-1");
          });
      var run = fixture.await(fixture.start());
      var event = run.events().get(1);
      assertEquals("planning", event.type());
      assertEquals("completed", event.details().status());
      assertEquals("model", event.details().toolName());
      assertEquals(List.of("knowledge_search"), event.details().toolNames());
      assertEquals("🌲".repeat(16000), event.details().reasoning());
      assertTrue(event.details().reasoningTruncated());
      assertNull(event.details().query());
      assertNull(event.details().resultCount());
      assertTrue(event.details().sources().isEmpty());
      assertFalse(event.toString().contains("🌲"));
      assertFalse(event.details().toString().contains("🌲"));
      assertEquals(1, fixture.context.models.calls.stream().filter("agent"::equals).count());
      fixture.context.models.agent =
          messages -> new AgentMessage("assistant", null, nativeMessage.toolCalls(), null);
      fixture.script.set(
          request -> {
            fixture.agents.model(
                request.runId(),
                auth(request),
                new AgentProtocol.ModelRequest(List.of(new AgentMessage("user", "资料"))));
            fixture.read(request);
            return answer("source-1");
          });
      assertNull(fixture.await(fixture.start()).events().get(1).details().reasoning());
    }
  }

  @Test
  void cancellationSealsRunningEventAndLateModelOutputCannotOverwriteIt() throws Exception {
    try (var fixture = new Fixture(directory)) {
      fixture.context.publish("manual.txt", "合成资料。");
      var entered = new CountDownLatch(1);
      var returned = new CountDownLatch(1);
      fixture.context.models.agent =
          messages -> {
            entered.countDown();
            try {
              Thread.sleep(10000);
            } catch (InterruptedException ignored) {
              Thread.interrupted();
            }
            returned.countDown();
            return new AgentMessage(
                "assistant",
                null,
                List.of(
                    new AgentMessage.ToolCall(
                        "call-late",
                        "function",
                        new AgentMessage.FunctionCall("knowledge_search", "{\"query\":\"资料\"}"))),
                null,
                "late provider reasoning must not appear");
          };
      fixture.script.set(
          request -> {
            fixture.agents.model(
                request.runId(),
                auth(request),
                new AgentProtocol.ModelRequest(List.of(new AgentMessage("user", "资料"))));
            return refused();
          });
      String id = fixture.start();
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      var started = fixture.agents.get(fixture.context.owner, id).events().get(1);
      assertEquals("running", started.details().status());
      assertNull(started.details().elapsedMs());
      var stopped = fixture.agents.cancel(fixture.context.owner, id);
      assertEquals("cancelled", stopped.events().get(1).details().status());
      assertEquals(started.sequence(), stopped.events().get(1).sequence());
      assertTrue(returned.await(5, TimeUnit.SECONDS));
      assertEquals(stopped, fixture.agents.get(fixture.context.owner, id));
      assertNull(stopped.events().get(1).details().reasoning());
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
  void modelCallbackPreservesSafeFailureAtHttpBoundaryAndTaskWithoutRetry() throws Exception {
    try (var fixture = new Fixture(directory)) {
      fixture.context.publish("manual.txt", "合成资料原文。");
      var mvc =
          MockMvcBuilders.standaloneSetup(new KnowledgeAgentCallbackController(fixture.agents))
              .setControllerAdvice(new ProblemHandler())
              .build();
      var mappings =
          Map.of(
              "model_tool_required", "agent_model_tool_required",
              "model_invalid_response", "agent_model_invalid",
              "model_response_too_large", "agent_model_invalid",
              "model_http_failed", "agent_model_unavailable",
              "model_transport_failed", "agent_model_unavailable",
              "model_timeout", "agent_model_timeout",
              "private-provider-secret", "agent_callback_failed");
      for (var mapping : mappings.entrySet()) {
        var received = new AtomicReference<MockHttpServletResponse>();
        fixture.context.models.calls.clear();
        fixture.context.models.agent =
            messages -> {
              throw new TextModels.Failure(mapping.getKey());
            };
        fixture.script.set(
            request -> {
              try {
                var response =
                    mvc.perform(
                            MockMvcRequestBuilders.post(
                                    "/internal/knowledge-agent/runs/" + request.runId() + "/model")
                                .with(
                                    servlet -> {
                                      servlet.setRemoteAddr("127.0.0.1");
                                      return servlet;
                                    })
                                .header("Authorization", auth(request))
                                .contentType("application/json")
                                .content(
                                    "{\"messages\":[{\"role\":\"user\",\"content\":\"synthetic question\"}]}"))
                        .andReturn()
                        .getResponse();
                received.set(response);
                var body = new JsonMapper().readTree(response.getContentAsString());
                // Simulate only the sidecar's fixed-code return, never a successful proposal.
                throw new TextModels.Failure(body.path("error_code").asString());
              } catch (TextModels.Failure failure) {
                throw failure;
              } catch (Exception failure) {
                throw new IllegalStateException("synthetic callback test failed", failure);
              }
            });
        var run = fixture.await(fixture.start());
        var response = received.get();
        assertNotNull(response);
        assertEquals(
            mapping.getValue().equals("agent_model_timeout") ? 408 : 503, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/problem+json"));
        assertFalse(response.getContentAsString().contains("private-provider-secret"));
        assertEquals(mapping.getValue(), run.error().code());
        assertEquals("failed", run.events().get(1).details().status());
        assertEquals(mapping.getValue(), run.events().get(1).details().safeCode());
        assertTrue(run.events().get(1).details().elapsedMs() >= 0);
        assertEquals(List.of("agent"), fixture.context.models.calls);
        assertEquals(
            List.of("running", "planning", "failed"),
            run.events().stream().map(AgentRunResult.Event::type).toList());
        assertNull(run.result());
      }
      assertEquals(0, fixture.context.scalar("SELECT COUNT(*) FROM knowledge_answer_traces"));
    }
  }

  @Test
  void retrievalModelFailureIsAReadOnlyToolFailureNotAGenerationProtocolFailure() throws Exception {
    try (var fixture = new Fixture(directory)) {
      fixture.context.publish("manual.txt", "合成资料原文。");
      fixture.context.models.embedding =
          values -> {
            throw new TextModels.Failure("model_invalid_response");
          };
      fixture.script.set(
          request -> {
            fixture.agents.search(request.runId(), auth(request), "资料");
            return refused();
          });
      var run = fixture.await(fixture.start());
      assertEquals("agent_tool_failed", run.error().code());
      assertEquals(List.of("embed"), fixture.context.models.calls);
      assertNull(run.result());
      assertEquals("failed", run.events().get(1).details().status());
      assertEquals("资料", run.events().get(1).details().query());
      assertEquals("agent_tool_failed", run.events().get(1).details().safeCode());
      assertTrue(run.events().get(1).details().sources().isEmpty());
      assertEquals(0, fixture.context.scalar("SELECT COUNT(*) FROM knowledge_answer_traces"));
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
    final ConversationService conversations;

    Fixture(Path directory) {
      this(directory, Duration.ofSeconds(10));
    }

    Fixture(Path directory, Duration timeout) {
      this(directory, timeout, false);
    }

    Fixture(Path directory, Duration timeout, boolean failPersistence) {
      this(directory, timeout, failPersistence ? Integer.MAX_VALUE : 0);
    }

    /** The first {@code persistenceFailures} conversation terminal writes fail. */
    Fixture(Path directory, Duration timeout, int persistenceFailures) {
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
          new ProductHelpService(context.evidence, runtime, Duration.ofSeconds(10), settings::get);
      traces = new KnowledgeTraceService(context.authority.store(), context.evidence);
      var conversationService =
          new ConversationService(
              context.authority.store(),
              new com.evidence.rag.repository.ConversationRepository(context.authority.store()),
              context.owner.workspaceId(),
              24000,
              4);
      conversations =
          persistenceFailures > 0
              ? org.mockito.Mockito.spy(conversationService)
              : conversationService;
      var failedWrites = new AtomicInteger();
      if (persistenceFailures > 0)
        org.mockito.Mockito.doAnswer(
                invocation -> {
                  if (failedWrites.getAndIncrement() < persistenceFailures)
                    throw new IllegalStateException("synthetic persistence failure");
                  return invocation.callRealMethod();
                })
            .when(conversations)
            .finish(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
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
              1,
              conversations);
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
