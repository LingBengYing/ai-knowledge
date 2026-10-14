package com.evidence.rag.service;

import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.ConversationState;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.dto.AgentMessage;
import com.evidence.rag.model.dto.AgentRunResult;
import com.evidence.rag.model.dto.ConversationContext;
import com.evidence.rag.model.dto.ConversationResult;
import com.evidence.rag.repository.ConversationRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import tools.jackson.databind.json.JsonMapper;

/** Durable conversations and atomic summary checkpoints; original turns always remain available. */
public final class ConversationService {
  private final SqliteAuthorityStore store;
  private final ConversationRepository repository;
  private final String workspace;
  private final int threshold;
  private final int recent;
  private final JsonMapper json = JsonMapper.builder().build();

  public ConversationService(
      SqliteAuthorityStore store,
      ConversationRepository repository,
      String workspace,
      int threshold,
      int recent) {
    if (threshold < 100 || threshold > 200000 || recent < 1 || recent > 12) {
      throw ModelValues.invalid();
    }
    this.store = store;
    this.repository = repository;
    this.workspace = workspace;
    this.threshold = threshold;
    this.recent = recent;
    store.transaction(
        () -> {
          repository.interruptOrphans(now());
          return null;
        });
  }

  public ConversationResult.Page list(Actor actor, int offset, int limit) {
    member(actor);
    if (offset < 0 || limit < 1 || limit > 100) {
      throw ModelValues.invalid();
    }
    return store.transaction(
        () ->
            new ConversationResult.Page(
                repository.list(actor, offset, limit).stream()
                    .map(ConversationService::view)
                    .toList(),
                offset,
                limit));
  }

  public ConversationResult create(Actor actor, String title) {
    member(actor);
    String normalized = title == null ? "新对话" : title(title);
    return store.transaction(
        () -> {
          String id = UUID.randomUUID().toString();
          repository.insert(actor, id, normalized, now());
          return view(repository.find(actor, id).orElseThrow());
        });
  }

  public ConversationResult.Detail get(Actor actor, String id) {
    return get(actor, id, 0, 20);
  }

  public ConversationResult.Detail get(Actor actor, String id, int offset, int limit) {
    identity(actor, id);
    if (offset < 0 || limit < 1 || limit > 100) {
      throw ModelValues.invalid();
    }
    return store.transaction(
        () -> {
          var conversation = view(repository.find(actor, id).orElseThrow(ModelValues::notFound));
          var turns = new ArrayList<ConversationResult.Turn>();
          long bytes =
              json.writeValueAsBytes(
                      new ConversationResult.Detail(conversation, List.of(), offset, limit, true))
                  .length;
          for (var stored : repository.turnPage(actor, id, offset, limit)) {
            var turn = turnView(stored);
            long next = json.writeValueAsBytes(turn).length + 1L;
            if (!turns.isEmpty() && bytes + next > 2L * 1024 * 1024) {
              break;
            }
            turns.add(turn);
            bytes += next;
          }
          return new ConversationResult.Detail(
              conversation,
              turns,
              offset,
              limit,
              (long) offset + turns.size() < conversation.turnCount());
        });
  }

  public ConversationResult rename(Actor actor, String id, String title) {
    identity(actor, id);
    String normalized = title(title);
    return store.transaction(
        () -> {
          repository.find(actor, id).orElseThrow(ModelValues::notFound);
          repository.rename(actor, id, normalized, now());
          return view(repository.find(actor, id).orElseThrow());
        });
  }

  public void delete(Actor actor, String id) {
    identity(actor, id);
    store.transaction(
        () -> {
          var previous = repository.find(actor, id).orElseThrow(ModelValues::notFound);
          if (previous.activeRunId() != null) {
            throw busy();
          }
          repository.delete(actor, id);
          return null;
        });
  }

  public Optional<ConversationResult.Turn> request(
      Actor actor, String id, String requestId, String question) {
    identity(actor, id);
    return store.transaction(
        () -> {
          var previous = repository.request(actor, id, requestId);
          if (previous.isPresent() && !previous.get().question().equals(question)) {
            throw new ApplicationException(
                FailureKind.CONFLICT, "agent_request_conflict", "请求编号已用于其他问题。");
          }
          return previous.map(this::turnView);
        });
  }

  public void begin(Actor actor, String id, String requestId, String runId, String question) {
    identity(actor, id);
    store.transaction(
        () -> {
          var previous = repository.find(actor, id).orElseThrow(ModelValues::notFound);
          if (previous.activeRunId() != null) {
            throw busy();
          }
          repository.begin(actor, id, requestId, runId, question, now());
          return null;
        });
  }

  public void finish(Actor actor, String id, AgentRunResult result) {
    if (id == null) {
      return;
    }
    store.transaction(
        () -> {
          repository.finish(
              actor, id, result.id(), result.status(), json.writeValueAsString(result), now());
          return null;
        });
  }

  /**
   * The authoritative view of a finished agent run: its archived conversation turn when one was
   * recorded (document cleanup redacts that copy in place), otherwise the process-local result with
   * the same redaction applied once a document it references has been deleted.
   */
  public AgentRunResult archived(Actor actor, String id, AgentRunResult finished) {
    return store.transaction(
        () -> {
          if (id != null) {
            var stored = repository.runResult(actor, id, finished.id());
            if (stored.isPresent()) {
              return json.readValue(stored.get(), AgentRunResult.class);
            }
          }
          return repository
              .redactDeleted(actor.workspaceId(), json.writeValueAsString(finished))
              .map(redacted -> json.readValue(redacted, AgentRunResult.class))
              .orElse(finished);
        });
  }

  public ConversationContext prepare(
      Actor actor, String id, String runId, TextModels models, BooleanSupplier current) {
    if (id == null) {
      return ConversationContext.empty();
    }
    identity(actor, id);
    var snapshot =
        store.transaction(() -> repository.find(actor, id).orElseThrow(ModelValues::notFound));
    var turns =
        store.transaction(() -> repository.turns(actor, id)).stream()
            .filter(turn -> !"running".equals(turn.status()))
            .toList();
    long covered = snapshot.coveredTurnCount();
    var remaining =
        turns.stream().filter(turn -> turn.ordinal() > covered).map(this::turnView).toList();
    long size = snapshot.summary().length() + characters(remaining);
    if ((size <= threshold && remaining.size() <= 16) || remaining.isEmpty()) {
      return boundedContext(snapshot.summary(), messages(remaining));
    }
    int retained = Math.min(recent, remaining.size() - 1);
    // Keeping recent turns is a preference, never a permanent block on a long single turn.
    while (retained > 0
        && characters(remaining.subList(remaining.size() - retained, remaining.size()))
            > Math.min(threshold, 234144)) {
      retained--;
    }
    int prefixSize = remaining.size() - retained;
    var prefix = remaining.subList(0, prefixSize);
    store.transaction(
        () -> {
          repository.compression(actor, id, runId, "compressing");
          return null;
        });
    try {
      if (!current.getAsBoolean()) {
        throw compressionFailure();
      }
      String summary = compact(models, snapshot.summary(), prefix, current);
      var context =
          boundedContext(summary, messages(remaining.subList(prefixSize, remaining.size())));
      boolean installed =
          store.transaction(
              () ->
                  current.getAsBoolean()
                      && repository.checkpoint(
                          actor,
                          id,
                          runId,
                          snapshot.generation(),
                          prefix.getLast().ordinal(),
                          summary,
                          now()));
      if (!installed) {
        throw compressionFailure();
      }
      return context;
    } catch (RuntimeException failed) {
      store.transaction(
          () -> {
            repository.compression(actor, id, runId, "failed");
            return null;
          });
      throw compressionFailure();
    }
  }

  private static List<AgentMessage> messages(List<ConversationResult.Turn> turns) {
    var result = new ArrayList<AgentMessage>();
    for (var turn : turns) {
      if (turn.question().length() > 262144) {
        throw compressionFailure();
      }
      result.add(new AgentMessage("user", turn.question()));
      String answer = answer(turn);
      if (answer == null || answer.isBlank() || answer.length() > 262144) {
        throw compressionFailure();
      }
      result.add(new AgentMessage("assistant", answer));
    }
    return List.copyOf(result);
  }

  private static String answer(ConversationResult.Turn turn) {
    return turn.result() != null && turn.result().result() != null
        ? turn.result().result().answer()
        : "本轮未完成，状态：" + turn.status();
  }

  private static long characters(List<ConversationResult.Turn> turns) {
    return turns.stream()
        .mapToLong(turn -> (long) turn.question().length() + answer(turn).length())
        .sum();
  }

  /** Complete oversized turns are streamed through bounded batches, never silently sliced away. */
  private static String compact(
      TextModels models,
      String previous,
      List<ConversationResult.Turn> turns,
      BooleanSupplier current) {
    var batch = new ArrayList<AgentMessage>();
    int characters = 0;
    String summary = previous;
    for (var turn : turns) {
      for (String role : List.of("user", "assistant")) {
        String text = "user".equals(role) ? turn.question() : answer(turn);
        for (int start = 0; start < text.length(); ) {
          int end = Math.min(start + 24000, text.length());
          if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
          }
          String part = text.substring(start, end);
          if (!batch.isEmpty() && (batch.size() >= 32 || characters + part.length() > 48000)) {
            summary = compactBatch(models, summary, batch, current);
            batch.clear();
            characters = 0;
          }
          batch.add(new AgentMessage(role, part));
          characters += part.length();
          start = end;
        }
      }
    }
    if (!batch.isEmpty()) {
      summary = compactBatch(models, summary, batch, current);
    }
    return summary;
  }

  private static String compactBatch(
      TextModels models, String previous, List<AgentMessage> batch, BooleanSupplier current) {
    if (!current.getAsBoolean()) {
      throw compressionFailure();
    }
    String summary = models.compactConversation(previous, List.copyOf(batch));
    if (summary == null
        || summary.isBlank()
        || summary.length() > 12000
        || summary.indexOf(0) >= 0
        || !current.getAsBoolean()) {
      throw compressionFailure();
    }
    return summary;
  }

  private static ConversationContext boundedContext(String summary, List<AgentMessage> history) {
    if (history.size() > 32
        || summary.length()
                + history.stream().mapToLong(message -> message.content().length()).sum()
            > 246144) {
      // Reserve the existing 16000-character question capacity; never drop historical text.
      throw compressionFailure();
    }
    return new ConversationContext(summary, history);
  }

  private static ConversationResult view(ConversationState value) {
    return new ConversationResult(
        value.id(),
        value.title(),
        value.createdAt(),
        value.updatedAt(),
        value.turnCount(),
        value.activeRunId(),
        new ConversationResult.Compression(
            value.compressionStatus(), value.coveredTurnCount(), value.summary().length()));
  }

  private ConversationResult.Turn turnView(ConversationState.Turn value) {
    return new ConversationResult.Turn(
        value.ordinal(),
        value.runId(),
        value.question(),
        value.status(),
        value.resultJson() == null
            ? null
            : json.readValue(value.resultJson(), AgentRunResult.class),
        value.createdAt(),
        value.updatedAt());
  }

  private void member(Actor actor) {
    if (actor == null || !workspace.equals(actor.workspaceId())) {
      throw ModelValues.notFound();
    }
  }

  private void identity(Actor actor, String id) {
    member(actor);
    try {
      if (id == null || !UUID.fromString(id).toString().equals(id)) {
        throw ModelValues.invalid();
      }
    } catch (IllegalArgumentException invalid) {
      throw ModelValues.invalid();
    }
  }

  private static String title(String title) {
    if (title == null
        || title.isBlank()
        || title.length() > 200
        || title.codePoints().anyMatch(Character::isISOControl)) {
      throw ModelValues.invalid();
    }
    return title.trim();
  }

  private static long now() {
    return Instant.now().toEpochMilli();
  }

  private static ApplicationException busy() {
    return new ApplicationException(FailureKind.CONFLICT, "conversation_busy", "请先等待或停止本对话的当前任务。");
  }

  private static ApplicationException compressionFailure() {
    return new ApplicationException(
        FailureKind.UNAVAILABLE, "agent_context_compaction_failed", "对话上下文整理失败，原始消息已保留。");
  }
}
