package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.dto.AgentMessage;
import com.evidence.rag.model.dto.AgentRunResult;
import com.evidence.rag.model.dto.KnowledgeAnswerResult;
import com.evidence.rag.repository.ConversationRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConversationServiceTest {
  @TempDir Path directory;
  private final Actor owner = new Actor("org-main", "owner");

  @Test
  void crudIsActorScopedAndDeleteDoesNotDeleteDocuments() throws Exception {
    try (var store = new SqliteAuthorityStore(directory)) {
      var service = service(store, 24000, 4);
      var first = service.create(owner, "合成对话");
      var other = new Actor("org-main", "other");
      assertTrue(service.list(other, 0, 30).items().isEmpty());
      assertThrows(ApplicationException.class, () -> service.get(other, first.id()));
      assertThrows(ApplicationException.class, () -> service.rename(other, first.id(), "覆盖"));
      assertThrows(ApplicationException.class, () -> service.delete(other, first.id()));
      assertEquals("已改名", service.rename(owner, first.id(), "已改名").title());
      var second = service.create(owner, null);
      assertEquals(1, service.list(owner, 0, 1).items().size());
      assertEquals(1, service.list(owner, 1, 1).items().size());
      service.delete(owner, first.id());
      assertThrows(ApplicationException.class, () -> service.get(owner, first.id()));
      assertEquals(second.id(), service.list(owner, 0, 30).items().getFirst().id());
      try (var connection =
              DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
          var query = connection.createStatement();
          var result = query.executeQuery("PRAGMA user_version")) {
        assertTrue(result.next());
        assertEquals(36, result.getInt(1));
      }
    }
  }

  @Test
  void originalTurnsSurviveRestartAndOrphanIsInterruptedWithoutReplay() {
    String id;
    String abandoned = UUID.randomUUID().toString();
    try (var store = new SqliteAuthorityStore(directory)) {
      var service = service(store, 24000, 4);
      id = service.create(owner, "合成历史").id();
      complete(service, id, "第一问", "完整回答 [1]");
      service.begin(owner, id, UUID.randomUUID().toString(), abandoned, "重启中的问题");
      assertEquals(
          "conversation_busy",
          assertThrows(ApplicationException.class, () -> service.delete(owner, id)).code());
      assertEquals(
          "conversation_busy",
          assertThrows(
                  ApplicationException.class,
                  () ->
                      service.begin(
                          owner,
                          id,
                          UUID.randomUUID().toString(),
                          UUID.randomUUID().toString(),
                          "并发问题"))
              .code());
    }
    try (var store = new SqliteAuthorityStore(directory)) {
      var service = service(store, 24000, 4);
      var detail = service.get(owner, id);
      assertEquals(2, detail.turns().size());
      assertEquals("第一问", detail.turns().getFirst().question());
      assertEquals("完整回答 [1]", detail.turns().getFirst().result().result().answer());
      assertEquals("failed", detail.turns().getLast().status());
      assertEquals("agent_interrupted", detail.turns().getLast().result().error().code());
      assertNull(detail.conversation().activeRunId());
      service.delete(owner, id);
    }
  }

  @Test
  void executionDetailsSurviveRestartButNeverEnterFollowupOrCompactionContext() {
    String id;
    AgentRunResult first;
    var json = new tools.jackson.databind.json.JsonMapper();
    var legacy =
        json.readValue(
            "{\"sequence\":1,\"type\":\"planning\",\"message\":\"旧记录\"}",
            AgentRunResult.Event.class);
    assertNull(legacy.details());
    var details =
        new AgentRunResult.Details(
            "completed",
            "model",
            null,
            3L,
            null,
            null,
            List.of(),
            null,
            "provider-only reasoning never reused",
            false,
            List.of("knowledge_search"));
    try (var store = new SqliteAuthorityStore(directory)) {
      var service = service(store, 100, 1);
      id = service.create(owner, "过程回读").id();
      String run = UUID.randomUUID().toString();
      service.begin(owner, id, UUID.randomUUID().toString(), run, "第一问");
      first =
          new AgentRunResult(
              run,
              "completed",
              List.of(legacy, new AgentRunResult.Event(2, "planning", "模型调用", details)),
              answer(run, "完整回答".repeat(30)).result(),
              List.of(),
              null);
      service.finish(owner, id, first);
      complete(service, id, "第二问", "简短结果");
    }
    try (var store = new SqliteAuthorityStore(directory)) {
      var service = service(store, 100, 1);
      var page = service.get(owner, id, 0, 1);
      assertEquals(first, page.turns().getFirst().result());
      assertTrue(page.hasMore());
      assertNull(page.turns().getFirst().result().events().getFirst().details());
      assertEquals(details, page.turns().getFirst().result().events().getLast().details());
      String next = begin(service, id);
      var models = mock(TextModels.class);
      when(models.compactConversation(eq(""), anyList()))
          .thenAnswer(
              invocation -> {
                List<AgentMessage> history = invocation.getArgument(1);
                assertFalse(json.writeValueAsString(history).contains("provider-only"));
                assertTrue(
                    history.stream().allMatch(message -> message.reasoningContent() == null));
                return "第一轮非证据上下文";
              });
      var context = service.prepare(owner, id, next, models, () -> true);
      assertFalse(json.writeValueAsString(context).contains("provider-only"));
      assertTrue(
          context.history().stream().allMatch(message -> message.reasoningContent() == null));
      verify(models).compactConversation(eq(""), anyList());
      assertEquals(first, service.get(owner, id).turns().getFirst().result());
    }
  }

  @Test
  void successfulCompressionKeepsOriginalsAndReusesCheckpointOnlyOnce() {
    try (var store = new SqliteAuthorityStore(directory)) {
      var service = service(store, 250, 2);
      String id = service.create(owner, "压缩").id();
      for (int i = 1; i <= 5; i++) {
        complete(service, id, "问题" + i, "回答" + i + "内容".repeat(30));
      }
      var originals = service.get(owner, id).turns();
      String run = begin(service, id);
      var models = mock(TextModels.class);
      when(models.compactConversation(eq(""), anyList()))
          .thenAnswer(
              invocation -> {
                List<AgentMessage> input = invocation.getArgument(1);
                assertEquals(6, input.size());
                assertEquals("问题1", input.getFirst().content());
                assertEquals("回答3" + "内容".repeat(30), input.getLast().content());
                return "前三轮完整要点，非原文证据。";
              });
      var context = service.prepare(owner, id, run, models, () -> true);
      assertEquals("前三轮完整要点，非原文证据。", context.summary());
      assertEquals(4, context.history().size());
      assertEquals("问题4", context.history().getFirst().content());
      assertEquals(3, service.get(owner, id).conversation().compression().coveredTurnCount());
      assertEquals("compressed", service.get(owner, id).conversation().compression().status());
      assertEquals(originals, service.get(owner, id).turns().subList(0, 5));
      assertEquals(context, service.prepare(owner, id, run, models, () -> true));
      verify(models).compactConversation(eq(""), anyList());
    }
  }

  @Test
  void failedCompressionPreservesCheckpointAndEveryOriginal() {
    try (var store = new SqliteAuthorityStore(directory)) {
      var service = service(store, 100, 1);
      String id = service.create(owner, "失败不丢失").id();
      for (int i = 0; i < 3; i++) {
        complete(service, id, "问题" + i, "内容".repeat(40));
      }
      String run = begin(service, id);
      var before = service.get(owner, id).turns();
      var models = mock(TextModels.class);
      when(models.compactConversation(eq(""), anyList()))
          .thenThrow(new TextModels.Failure("private-provider-error"));
      assertEquals(
          "agent_context_compaction_failed",
          assertThrows(
                  ApplicationException.class,
                  () -> service.prepare(owner, id, run, models, () -> true))
              .code());
      assertEquals(before, service.get(owner, id).turns());
      assertEquals(0, service.get(owner, id).conversation().compression().coveredTurnCount());
      assertEquals("failed", service.get(owner, id).conversation().compression().status());
      verify(models).compactConversation(eq(""), anyList());
    }
  }

  @Test
  void cancellationDuringCompressionCannotInstallLateSummary() {
    try (var store = new SqliteAuthorityStore(directory)) {
      var service = service(store, 100, 1);
      String id = service.create(owner, "取消").id();
      for (int i = 0; i < 3; i++) {
        complete(service, id, "问题" + i, "内容".repeat(40));
      }
      String run = begin(service, id);
      var active = new AtomicBoolean(true);
      var models = mock(TextModels.class);
      when(models.compactConversation(eq(""), anyList()))
          .thenAnswer(
              invocation -> {
                active.set(false);
                service.finish(
                    owner,
                    id,
                    new AgentRunResult(run, "cancelled", List.of(), null, List.of(), null));
                return "迟到摘要";
              });
      assertThrows(
          ApplicationException.class, () -> service.prepare(owner, id, run, models, active::get));
      assertEquals(0, service.get(owner, id).conversation().compression().coveredTurnCount());
      assertEquals("cancelled", service.get(owner, id).turns().getLast().status());
      assertNull(service.get(owner, id).conversation().activeRunId());
    }
  }

  @Test
  void persistedRequestIsIdempotentAndRejectsAnotherQuestion() {
    try (var store = new SqliteAuthorityStore(directory)) {
      var service = service(store, 24000, 4);
      String id = service.create(owner, null).id(),
          request = UUID.randomUUID().toString(),
          run = UUID.randomUUID().toString();
      service.begin(owner, id, request, run, "合成问题");
      assertEquals(run, service.request(owner, id, request, "合成问题").orElseThrow().runId());
      assertThrows(ApplicationException.class, () -> service.request(owner, id, request, "另一问题"));
      service.finish(owner, id, answer(run, "合成回答"));
      assertEquals("completed", service.request(owner, id, request, "合成问题").orElseThrow().status());
    }
  }

  @Test
  void messageCountAlsoCompactsInsteadOfDroppingManyShortTurns() {
    try (var store = new SqliteAuthorityStore(directory)) {
      var service = service(store, 24000, 4);
      String id = service.create(owner, null).id();
      for (int i = 0; i < 17; i++) {
        complete(service, id, "问" + i, "答" + i);
      }
      String run = begin(service, id);
      var models = mock(TextModels.class);
      when(models.compactConversation(eq(""), anyList())).thenReturn("前十三轮对话的摘要");
      var context = service.prepare(owner, id, run, models, () -> true);
      assertEquals(8, context.history().size());
      assertEquals(13, service.get(owner, id).conversation().compression().coveredTurnCount());
    }
  }

  @Test
  void oneLongTurnIsCompressedRatherThanPermanentlyBlockingFollowups() {
    try (var store = new SqliteAuthorityStore(directory)) {
      var service = service(store, 100, 4);
      String id = service.create(owner, null).id();
      complete(service, id, "合成长期问题", "完整长答案".repeat(200));
      String run = begin(service, id);
      var models = mock(TextModels.class);
      when(models.compactConversation(eq(""), anyList())).thenReturn("完整长答案的非证据摘要");
      var context = service.prepare(owner, id, run, models, () -> true);
      assertTrue(context.history().isEmpty());
      assertEquals(1, service.get(owner, id).conversation().compression().coveredTurnCount());
      assertEquals(
          "完整长答案".repeat(200),
          service.get(owner, id).turns().getFirst().result().result().answer());
    }
  }

  @Test
  void oversizedSingleAnswerUsesCompleteBoundedBatchesAndOneFinalCheckpoint() {
    try (var store = new SqliteAuthorityStore(directory)) {
      var service = service(store, 24000, 4);
      String id = service.create(owner, null).id();
      String original = "合成完整答案🧭".repeat(40000);
      complete(service, id, "完整长答案问题", original);
      String run = begin(service, id);
      var models = mock(TextModels.class);
      var assembled = new StringBuilder();
      var calls = new AtomicInteger();
      when(models.compactConversation(anyString(), anyList()))
          .thenAnswer(
              invocation -> {
                List<AgentMessage> batch = invocation.getArgument(1);
                assertTrue(
                    batch.stream().mapToInt(message -> message.content().length()).sum() <= 48000);
                batch.stream()
                    .filter(message -> "assistant".equals(message.role()))
                    .forEach(message -> assembled.append(message.content()));
                assertEquals(
                    0,
                    service.get(owner, id).conversation().compression().coveredTurnCount(),
                    "intermediate batches must not advance checkpoint");
                return "到第" + calls.incrementAndGet() + "批的累计摘要";
              });
      var context = service.prepare(owner, id, run, models, () -> true);
      assertEquals(original, assembled.toString());
      assertTrue(calls.get() > 1);
      assertTrue(context.history().isEmpty());
      assertEquals(1, service.get(owner, id).conversation().compression().coveredTurnCount());
      assertEquals(original, service.get(owner, id).turns().getFirst().result().result().answer());
    }
  }

  @Test
  void aLaterBatchFailureDoesNotInstallPartialHistoryOrRetry() {
    try (var store = new SqliteAuthorityStore(directory)) {
      var service = service(store, 24000, 4);
      String id = service.create(owner, null).id();
      String original = "合成完整答案".repeat(50000);
      complete(service, id, "长问题", original);
      String run = begin(service, id);
      var models = mock(TextModels.class);
      var calls = new AtomicInteger();
      when(models.compactConversation(anyString(), anyList()))
          .thenAnswer(
              invocation -> {
                if (calls.incrementAndGet() == 2) {
                  throw new TextModels.Failure("model_timeout");
                }
                return "第一批派生摘要";
              });
      assertThrows(
          ApplicationException.class, () -> service.prepare(owner, id, run, models, () -> true));
      assertEquals(2, calls.get());
      assertEquals(0, service.get(owner, id).conversation().compression().coveredTurnCount());
      assertEquals(0, service.get(owner, id).conversation().compression().summaryCharacters());
      assertEquals(original, service.get(owner, id).turns().getFirst().result().result().answer());
    }
  }

  @Test
  void detailPagesKeepExactOrderAndTotalWithoutDroppingOriginalTurns() {
    try (var store = new SqliteAuthorityStore(directory)) {
      var service = service(store, 24000, 4);
      String id = service.create(owner, null).id();
      for (int i = 0; i < 23; i++) {
        complete(service, id, "问题" + i, "回答" + i);
      }
      var first = service.get(owner, id);
      assertEquals(20, first.turns().size());
      assertEquals(23, first.conversation().turnCount());
      assertTrue(first.hasMore());
      assertEquals(0, first.offset());
      var last = service.get(owner, id, 20, 20);
      assertEquals(3, last.turns().size());
      assertEquals(21, last.turns().getFirst().ordinal());
      assertEquals(23, last.turns().getLast().ordinal());
      assertFalse(last.hasMore());
      assertThrows(ApplicationException.class, () -> service.get(owner, id, -1, 20));
    }
  }

  @Test
  void detailPagesApplyUtf8ByteBudgetEvenBeforeRequestedTurnLimit() {
    try (var store = new SqliteAuthorityStore(directory)) {
      var service = service(store, 24000, 4);
      String id = service.create(owner, null).id();
      String original = "汉".repeat(400000);
      complete(service, id, "问题一", original);
      complete(service, id, "问题二", original);
      var first = service.get(owner, id);
      assertEquals(1, first.turns().size());
      assertTrue(first.hasMore());
      assertEquals(original, first.turns().getFirst().result().result().answer());
      var last = service.get(owner, id, 1, 20);
      assertEquals(2, last.turns().getFirst().ordinal());
      assertFalse(last.hasMore());
      assertEquals(original, last.turns().getFirst().result().result().answer());
    }
  }

  private ConversationService service(SqliteAuthorityStore store, int threshold, int recent) {
    return new ConversationService(
        store, new ConversationRepository(store), owner.workspaceId(), threshold, recent);
  }

  private String begin(ConversationService service, String id) {
    String run = UUID.randomUUID().toString();
    service.begin(owner, id, UUID.randomUUID().toString(), run, "当前问题");
    return run;
  }

  private void complete(ConversationService service, String id, String question, String answer) {
    String run = UUID.randomUUID().toString();
    service.begin(owner, id, UUID.randomUUID().toString(), run, question);
    service.finish(owner, id, answer(run, answer));
  }

  private static AgentRunResult answer(String run, String answer) {
    return new AgentRunResult(
        run,
        "completed",
        List.of(),
        new KnowledgeAnswerResult(
            UUID.randomUUID().toString(), "answered", answer, null, List.of()),
        List.of(),
        null);
  }
}
