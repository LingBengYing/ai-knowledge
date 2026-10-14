package com.evidence.rag.service;

import com.evidence.rag.client.model.AgentClient;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AgentFailureCode;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.EvidenceScope;
import com.evidence.rag.model.domain.KnowledgeEvidence;
import com.evidence.rag.model.domain.KnowledgeReference;
import com.evidence.rag.model.domain.KnowledgeTraceDraft;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.RetrievalSettings;
import com.evidence.rag.model.dto.AgentModelDelta;
import com.evidence.rag.model.dto.AgentProtocol;
import com.evidence.rag.model.dto.AgentRunResult;
import com.evidence.rag.model.dto.AgentRunStreamSnapshot;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.model.dto.ConversationContext;
import com.evidence.rag.model.dto.KnowledgeAnswerResult;
import com.evidence.rag.model.dto.KnowledgeCitation;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Native-tool task orchestration; Java owns all evidence, identities and formal citations. */
public final class KnowledgeAgentService implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(KnowledgeAgentService.class);
  public static final int MAX_STEPS = 8;

  /** Observers per run; each one parks an SSE sender thread until the next snapshot. */
  static final int MAX_SUBSCRIPTIONS = 16;

  /** Upstream token deltas are coalesced so a long answer is not re-sent once per token. */
  static final long DELTA_PUBLISH_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

  private static final String PROMPT = "java-native-tool-knowledge-agent-v3-answer-focus";
  private static final String REFUSAL = "当前资料不足以形成可核对的完整回答。";
  private final EvidenceService evidence;
  private final ProductHelpService retrieval;
  private final ManagedTextRuntime runtime;
  private final KnowledgeTraceService traces;
  private final AgentClient client;
  private final ConversationService conversations;
  private final long timeoutNanos;
  private final Semaphore admission;
  private final ExecutorService executor;
  private final ScheduledExecutorService timer;
  private final ConcurrentHashMap<String, Run> runs = new ConcurrentHashMap<>();
  private final LinkedHashMap<String, String> requestIds = new LinkedHashMap<>();
  private final AtomicBoolean closed = new AtomicBoolean();

  public KnowledgeAgentService(
      EvidenceService evidence,
      ProductHelpService retrieval,
      ManagedTextRuntime runtime,
      KnowledgeTraceService traces,
      AgentClient client,
      Duration timeout,
      int maximumConcurrent) {
    this(evidence, retrieval, runtime, traces, client, timeout, maximumConcurrent, null);
  }

  public KnowledgeAgentService(
      EvidenceService evidence,
      ProductHelpService retrieval,
      ManagedTextRuntime runtime,
      KnowledgeTraceService traces,
      AgentClient client,
      Duration timeout,
      int maximumConcurrent,
      ConversationService conversations) {
    if (evidence == null
        || retrieval == null
        || runtime == null
        || traces == null
        || client == null
        || timeout == null
        || timeout.compareTo(Duration.ofMillis(10)) < 0
        || timeout.compareTo(Duration.ofMinutes(10)) > 0
        || maximumConcurrent < 1
        || maximumConcurrent > 8) {
      throw ModelValues.invalid();
    }
    this.evidence = evidence;
    this.retrieval = retrieval;
    this.runtime = runtime;
    this.traces = traces;
    this.client = client;
    this.conversations = conversations;
    timeoutNanos = timeout.toNanos();
    admission = new Semaphore(maximumConcurrent);
    executor =
        Executors.newFixedThreadPool(
            maximumConcurrent,
            body -> {
              var thread = new Thread(body, "knowledge-agent");
              thread.setDaemon(true);
              return thread;
            });
    timer =
        Executors.newSingleThreadScheduledExecutor(
            body -> {
              var thread = new Thread(body, "knowledge-agent-deadline");
              thread.setDaemon(true);
              return thread;
            });
  }

  public synchronized AgentRunResult start(Actor actor, String question, String requestId) {
    return start(actor, question, requestId, null);
  }

  public synchronized AgentRunResult start(
      Actor actor, String question, String requestId, String conversationId) {
    if (actor == null || !uuid(requestId)) throw ModelValues.invalid();
    question(question);
    if (closed.get()) throw unavailable();
    if (conversationId != null) {
      if (conversations == null) {
        throw unavailable();
      }
      var previous = conversations.request(actor, conversationId, requestId, question);
      if (previous.isPresent()) {
        var turn = previous.get();
        var running = runs.get(turn.runId());
        if (running != null) {
          return running.view();
        }
        if (turn.result() != null) {
          return turn.result();
        }
        throw problem(FailureKind.CONFLICT, "agent_busy");
      }
    }
    String identity =
        actor.workspaceId() + "\n" + actor.principalId() + "\n" + conversationId + "\n" + requestId;
    String existingId = requestIds.get(identity);
    if (existingId != null) {
      var existing = runs.get(existingId);
      if (!existing.question.equals(question))
        throw problem(FailureKind.CONFLICT, "agent_request_conflict");
      return existing.view();
    }
    // Retain a bounded, explicit process-local idempotency window; never auto-resume old work.
    long cutoff = System.nanoTime() - Duration.ofMinutes(30).toNanos();
    runs.values().removeIf(run -> !run.running() && run.started < cutoff);
    requestIds.values().removeIf(id -> !runs.containsKey(id));
    if (runs.size() >= 256 || !admission.tryAcquire()) {
      throw problem(FailureKind.CAPACITY_EXCEEDED, "agent_capacity_exceeded");
    }
    var run = new Run(actor, question, conversationId);
    com.evidence.rag.model.domain.LibraryOperationGate.ReservedOperation reservation;
    try {
      reservation = evidence.operationGate().reserve();
    } catch (RuntimeException failed) {
      admission.release();
      throw failed;
    }
    try {
      if (conversationId != null) {
        conversations.begin(actor, conversationId, requestId, run.id, question);
      }
    } catch (RuntimeException failed) {
      reservation.close();
      admission.release();
      throw failed;
    }
    runs.put(run.id, run);
    requestIds.put(identity, run.id);
    try {
      executor.execute(
          () -> {
            run.worker = Thread.currentThread();
            java.util.concurrent.ScheduledFuture<?> deadline = null;
            try (var operation = reservation.begin()) {
              active(run);
              deadline =
                  timer.schedule(
                      () -> stop(run, "failed", "agent_timeout"),
                      timeoutNanos,
                      TimeUnit.NANOSECONDS);
              run.snapshot = runtime.capture();
              run.settings = retrieval.settingsSnapshot();
              run.scope =
                  evidence.snapshot(actor, DocumentSelection.allDocuments(), run.snapshot.target());
              check(run);
              var context =
                  conversations == null
                      ? ConversationContext.empty()
                      : conversations.prepare(
                          actor,
                          run.conversationId,
                          run.id,
                          run.snapshot.models(),
                          () -> !closed.get() && run.active() && runtime.isCurrent(run.snapshot));
              check(run);
              var proposal =
                  run.scope.publications().isEmpty()
                      ? new AgentProtocol.Proposal(true, List.of(), List.of())
                      : client.execute(
                          new AgentProtocol.RunRequest(
                              run.id,
                              run.question,
                              run.callbackToken,
                              context.history(),
                              context.summary()));
              finish(run, proposal);
            } catch (RuntimeException failed) {
              String code =
                  failed instanceof ApplicationException application
                      ? application.code()
                      : failed instanceof TextModels.Failure model ? model.code() : "agent_failed";
              stop(run, "failed", safeCode(code));
            } finally {
              if (deadline != null) {
                deadline.cancel(false);
              }
              run.worker = null;
              reservation.close();
              admission.release();
            }
          });
    } catch (RuntimeException failed) {
      stop(run, "failed", "agent_unavailable");
      runs.remove(run.id);
      requestIds.remove(identity);
      admission.release();
      reservation.close();
      throw unavailable();
    }
    return run.view();
  }

  public AgentRunResult get(Actor actor, String id) {
    return authorized(actor, id).view();
  }

  /** Subscribe to cumulative state, without starting or resuming any model execution. */
  public Subscription subscribe(Actor actor, String id) {
    var run = authorized(actor, id);
    synchronized (run) {
      if (run.streamFailed) throw problem(FailureKind.UNAVAILABLE, "agent_failed");
      if (run.subscriptions.size() >= MAX_SUBSCRIPTIONS) {
        throw problem(FailureKind.CAPACITY_EXCEEDED, "agent_capacity_exceeded");
      }
      var subscription = new Subscription(run);
      run.subscriptions.add(subscription);
      subscription.offer(run.streamView());
      return subscription;
    }
  }

  /**
   * One latest immutable snapshot per subscriber; slow readers do not block the run. Waiting uses a
   * {@link ReentrantLock} rather than a monitor so an idle virtual SSE sender unmounts from its
   * carrier thread on JDK 21 instead of pinning it.
   */
  public final class Subscription implements AutoCloseable {
    private final Run run;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private AgentRunStreamSnapshot latest;
    private boolean disposed;

    private Subscription(Run run) {
      this.run = run;
    }

    private void offer(AgentRunStreamSnapshot snapshot) {
      lock.lock();
      try {
        if (!disposed) {
          latest = snapshot;
          changed.signalAll();
        }
      } finally {
        lock.unlock();
      }
    }

    public AgentRunStreamSnapshot next(Duration timeout) throws InterruptedException {
      long remaining = timeout.toNanos();
      lock.lockInterruptibly();
      try {
        while (latest == null && !disposed) {
          if (remaining <= 0) return null;
          remaining = changed.awaitNanos(remaining);
        }
        var result = latest;
        latest = null;
        return result;
      } finally {
        lock.unlock();
      }
    }

    public boolean closed() {
      lock.lock();
      try {
        return disposed;
      } finally {
        lock.unlock();
      }
    }

    @Override
    public void close() {
      synchronized (run) {
        run.subscriptions.remove(this);
      }
      lock.lock();
      try {
        disposed = true;
        latest = null;
        changed.signalAll();
      } finally {
        lock.unlock();
      }
    }
  }

  public AgentRunResult cancel(Actor actor, String id) {
    var run = authorized(actor, id);
    stop(run, "cancelled", null);
    return run.view();
  }

  public AgentProtocol.ModelResult model(
      String id, String bearer, AgentProtocol.ModelRequest request) {
    return callback(
        id,
        bearer,
        "planning",
        "正在规划与分析",
        true,
        null,
        run ->
            new AgentProtocol.ModelResult(
                run.snapshot.models().agentChat(request.messages(), run.modelListener())));
  }

  public AgentProtocol.SearchResult search(String id, String bearer, String query) {
    question(query);
    if (query.length() > 4000) throw ModelValues.invalid();
    return callback(
        id,
        bearer,
        "searching",
        "正在检索资料",
        false,
        query,
        run -> {
          var selected = retrieval.retrieve(run.scope, query, run.snapshot, run.settings);
          var originals =
              evidence.knowledgeEvidence(
                  run.scope,
                  selected.stream()
                      .map(source -> new KnowledgeEvidence.Key(source.kind(), source.physicalId()))
                      .toList());
          var sources = new ArrayList<AgentProtocol.SearchSource>();
          synchronized (run) {
            active(run);
            for (int i = 0; i < originals.size(); i++) {
              var original = originals.get(i);
              if (!original.source().equals(selected.get(i))) throw invalid();
              String sourceId = run.sourceIds.get(original.key());
              if (sourceId == null) {
                if (run.originals.size() >= 64)
                  throw problem(FailureKind.CAPACITY_EXCEEDED, "agent_limit_exceeded");
                sourceId = "source-" + (run.originals.size() + 1);
                run.sourceIds.put(original.key(), sourceId);
                run.originals.put(sourceId, original);
              } else if (!run.originals.get(sourceId).equals(original)) throw invalid();
              var source = original.source();
              String excerpt = source.text();
              if (excerpt.codePointCount(0, excerpt.length()) > 300)
                excerpt = excerpt.substring(0, excerpt.offsetByCodePoints(0, 300));
              sources.add(
                  new AgentProtocol.SearchSource(
                      sourceId,
                      source.filename(),
                      kind(original),
                      excerpt,
                      source.publication().documentId()));
            }
          }
          return new AgentProtocol.SearchResult(sources);
        });
  }

  public AgentProtocol.ReadResult read(String id, String bearer, List<String> ids) {
    new AgentProtocol.ReadRequest(ids);
    return callback(
        id,
        bearer,
        "reading",
        "正在阅读原文",
        false,
        null,
        run -> {
          var originals = new ArrayList<KnowledgeEvidence>();
          synchronized (run) {
            if (new HashSet<>(ids).size() != ids.size()) throw invalid();
            for (String sourceId : ids) {
              var original = run.originals.get(sourceId);
              if (original == null) throw invalid();
              originals.add(original);
            }
          }
          if (!evidence
              .knowledgeEvidence(run.scope, originals.stream().map(KnowledgeEvidence::key).toList())
              .equals(originals)) {
            throw problem(FailureKind.CONFLICT, "evidence_changed");
          }
          var sources = new ArrayList<AgentProtocol.ReadSource>();
          synchronized (run) {
            active(run);
            for (int i = 0; i < originals.size(); i++) {
              var original = originals.get(i);
              run.readIds.add(ids.get(i));
              sources.add(
                  new AgentProtocol.ReadSource(
                      ids.get(i),
                      original.source().filename(),
                      kind(original),
                      reference(1, original).quote(),
                      original.source().publication().documentId()));
            }
          }
          return new AgentProtocol.ReadResult(sources);
        });
  }

  private <T> T callback(
      String id,
      String bearer,
      String type,
      String message,
      boolean model,
      String query,
      Function<Run, T> operation) {
    Run run = runs.get(id);
    if (run == null
        || bearer == null
        || !MessageDigest.isEqual(
            ("Bearer " + run.callbackToken).getBytes(StandardCharsets.UTF_8),
            bearer.getBytes(StandardCharsets.UTF_8))) {
      throw problem(FailureKind.UNAUTHENTICATED, "agent_callback_denied");
    }
    synchronized (run) {
      active(run);
      if (run.snapshot == null || run.scope == null || run.callback != null) throw invalid();
      if (model ? ++run.modelCalls > MAX_STEPS : ++run.toolCalls > MAX_STEPS * 2) {
        throw problem(FailureKind.CAPACITY_EXCEEDED, "agent_limit_exceeded");
      }
      run.callback = Thread.currentThread();
      run.callbackEvent(type, message, query);
    }
    try (var lease = evidence.operationGate().enter()) {
      check(run);
      T result = operation.apply(run);
      check(run);
      synchronized (run) {
        active(run);
        run.finishCallback("completed", null, result);
      }
      return result;
    } catch (TextModels.Failure failed) {
      String code =
          model
              ? switch (failed.code()) {
                case "model_tool_required" -> "agent_model_tool_required";
                case "model_invalid_response", "model_response_too_large" -> "agent_model_invalid";
                case "model_timeout" -> "agent_model_timeout";
                case "model_http_failed",
                    "model_transport_failed",
                    "model_closed",
                    "model_agent_unavailable",
                    "model_invalid_configuration" ->
                    "agent_model_unavailable";
                default -> "agent_callback_failed";
              }
              : "agent_tool_failed";
      run.finishCallback("failed", code, null);
      throw callbackFailure(run, type, code);
    } catch (ApplicationException failed) {
      // Retrieval already classifies embedding/rerank/projection errors. Translate only those
      // known tool failures here; never mask scope/configuration changes or authentication
      // failures.
      if (!model
          && Set.of(
                  "retrieval_embedding_failed",
                  "retrieval_rerank_failed",
                  "retrieval_search_failed",
                  "retrieval_unavailable",
                  "retrieval_timeout")
              .contains(failed.code())) {
        run.finishCallback("failed", "agent_tool_failed", null);
        throw callbackFailure(run, type, "agent_tool_failed");
      }
      run.finishCallback("failed", safeCode(failed.code()), null);
      throw failed;
    } catch (RuntimeException failed) {
      run.finishCallback("failed", "agent_failed", null);
      throw failed;
    } finally {
      synchronized (run) {
        run.callback = null;
      }
    }
  }

  private static AgentRunResult.Source sourceDetails(String sourceId, KnowledgeEvidence original) {
    var source = original.source();
    return new AgentRunResult.Source(
        sourceId,
        source.publication().documentId(),
        source.publication().sourceRevisionId(),
        source.filename(),
        kind(original),
        source.page(),
        source.startUs() == null ? null : BigDecimal.valueOf(source.startUs(), 3),
        source.endUs() == null ? null : BigDecimal.valueOf(source.endUs(), 3));
  }

  private static ApplicationException callbackFailure(Run run, String type, String code) {
    LOG.warn(
        "knowledge_agent_callback_failed run_id={} stage={} code={} model_calls={} tool_calls={}",
        run.id,
        type,
        code,
        run.modelCalls,
        run.toolCalls);
    return problem(
        "agent_model_timeout".equals(code) ? FailureKind.TIMEOUT : FailureKind.UNAVAILABLE, code);
  }

  private void finish(Run run, AgentProtocol.Proposal proposal) {
    synchronized (run) {
      check(run);
      if (run.callback != null
          || proposal == null
          || proposal.statements().size() > 64
          || proposal.suggestions().size() > 8
          || proposal.refused() != proposal.statements().isEmpty()) throw invalid();
      var references = new LinkedHashMap<String, KnowledgeReference>();
      var paragraphs = new ArrayList<String>();
      for (var statement : proposal.statements()) {
        if (statement == null
            || !text(statement.text(), 16000)
            || statement.evidenceIds().isEmpty()
            || new HashSet<>(statement.evidenceIds()).size() != statement.evidenceIds().size()
            || !run.readIds.containsAll(statement.evidenceIds())) throw invalid();
        var citationIds = new ArrayList<String>();
        for (String sourceId : statement.evidenceIds()) {
          var ref =
              references.computeIfAbsent(
                  sourceId, key -> reference(references.size() + 1, run.originals.get(key)));
          citationIds.add("[" + ref.citationId() + "]");
        }
        paragraphs.add(statement.text() + " " + String.join("", citationIds));
      }
      var readOriginals = run.readIds.stream().map(run.originals::get).toList();
      if (!evidence
          .knowledgeEvidence(run.scope, readOriginals.stream().map(KnowledgeEvidence::key).toList())
          .equals(readOriginals)) {
        throw problem(FailureKind.CONFLICT, "evidence_changed");
      }
      var documents = new HashSet<String>();
      readOriginals.forEach(
          original -> documents.add(original.source().publication().documentId()));
      for (var suggestion : proposal.suggestions()) {
        if (suggestion == null
            || !text(suggestion.title(), 200)
            || !text(suggestion.reason(), 2000)
            || suggestion.documentIds().isEmpty()
            || !documents.containsAll(suggestion.documentIds())
            || new HashSet<>(suggestion.documentIds()).size() != suggestion.documentIds().size())
          throw invalid();
      }
      String answer = String.join("\n\n", paragraphs);
      var draft =
          new KnowledgeTraceDraft(
              sha(run.question),
              proposal.refused() ? null : sha(answer),
              proposal.refused() ? "abstained" : "answered",
              proposal.refused() ? "model_refused" : null,
              run.snapshot.modelsRevision(),
              PROMPT,
              PROMPT + ":retrieval-v" + run.settings.version() + ":" + run.settings.fingerprint(),
              List.copyOf(references.values()));
      var receipt =
          traces.finish(
              run.scope,
              draft,
              () ->
                  !run.active()
                      ? "agent_timeout"
                      : runtime.isCurrent(run.snapshot) ? null : "configuration_changed");
      boolean answered = "answered".equals(receipt.outcome());
      run.result =
          new KnowledgeAnswerResult(
              receipt.traceId(),
              receipt.outcome(),
              answered ? answer : REFUSAL,
              receipt.reasonCode(),
              answered
                  ? references.values().stream()
                      .map(ref -> KnowledgeCitation.from(receipt.traceId(), ref))
                      .toList()
                  : List.of());
      // A changed scope/configuration also invalidates maintenance suggestions.
      run.suggestions =
          answered || "model_refused".equals(receipt.reasonCode())
              ? proposal.suggestions()
              : List.of();
      run.status = "completed";
      run.preview = "";
      run.event("completed", "已完成资料分析");
      persistAndPublish(run);
    }
  }

  private void check(Run run) {
    active(run);
    if (!runtime.isCurrent(run.snapshot))
      throw problem(FailureKind.CONFLICT, "configuration_changed");
    evidence.knowledgeEvidence(run.scope, List.of());
  }

  private void active(Run run) {
    if (closed.get() || !run.active()) throw problem(FailureKind.TIMEOUT, "agent_timeout");
  }

  private Run authorized(Actor actor, String id) {
    var run = runs.get(id);
    if (actor == null
        || run == null
        || !run.actor.workspaceId().equals(actor.workspaceId())
        || !run.actor.principalId().equals(actor.principalId()))
      throw problem(FailureKind.NOT_FOUND, "agent_run_not_found");
    return run;
  }

  private static KnowledgeReference reference(int id, KnowledgeEvidence original) {
    return new KnowledgeReference(
        id, original, original.context().startCodePoint(), original.context().endCodePoint());
  }

  private static String kind(KnowledgeEvidence source) {
    return source.source().kind().name().toLowerCase(Locale.ROOT);
  }

  private static boolean uuid(String id) {
    try {
      return id != null && UUID.fromString(id).toString().equals(id);
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  private static void question(String text) {
    if (!text(text, 16000)) throw ModelValues.invalid();
    new AnswerCommand(text, DocumentSelection.allDocuments());
  }

  private static boolean text(String value, int max) {
    return value != null
        && !value.isBlank()
        && value.length() <= max
        && value
            .codePoints()
            .noneMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t');
  }

  private static String sha(String text) {
    return ModelValues.sha256(text.getBytes(StandardCharsets.UTF_8));
  }

  private static String safeCode(String code) {
    return AgentFailureCode.safe(code, AgentFailureCode.AGENT_FAILED);
  }

  private static ApplicationException problem(FailureKind kind, String code) {
    return new ApplicationException(kind, code, "智能体任务未完成，请查看任务原因码。");
  }

  private static ApplicationException invalid() {
    return problem(FailureKind.INVALID_INPUT, "agent_invalid_response");
  }

  private static ApplicationException unavailable() {
    return problem(FailureKind.UNAVAILABLE, "agent_unavailable");
  }

  private void stop(Run run, String status, String code) {
    synchronized (run) {
      if (!run.running()) return;
      run.status = status;
      run.preview = "";
      String safe = code == null ? null : safeCode(code);
      run.error = safe == null ? null : new AgentRunResult.Problem(safe, "智能体任务未完成，请查看任务原因码。");
      run.finishCallback(status, safe, null);
      LOG.info(
          "knowledge_agent_terminal run_id={} status={} code={} stage={} model_calls={} tool_calls={}",
          run.id,
          status,
          safe == null ? "none" : safe,
          run.events.isEmpty() ? "running" : run.events.getLast().type(),
          run.modelCalls,
          run.toolCalls);
      run.event(status, "cancelled".equals(status) ? "已停止任务" : "任务未完成");
      try {
        persistAndPublish(run);
      } finally {
        if (run.callback != null && run.callback != Thread.currentThread())
          run.callback.interrupt();
        if (run.worker != null && run.worker != Thread.currentThread()) run.worker.interrupt();
      }
    }
  }

  private void persistAndPublish(Run run) {
    try {
      persist(run);
    } catch (RuntimeException failed) {
      // Never publish a successful terminal snapshot that has not been durably committed.
      run.status = "failed";
      run.result = null;
      run.suggestions = List.of();
      run.preview = "";
      run.streamFailed = true;
      run.error = new AgentRunResult.Problem("agent_failed", "智能体任务未完成，请查看任务原因码。");
      var last = run.events.getLast();
      run.events.set(
          run.events.size() - 1, new AgentRunResult.Event(last.sequence(), "failed", "任务未完成"));
      List.copyOf(run.subscriptions).forEach(Subscription::close);
      releaseConversation(run);
      throw failed;
    }
    run.publish();
  }

  /**
   * Best effort after a failed terminal write: record the failed turn and clear the conversation's
   * active run, so the conversation is not busy until the next restart. A second failure leaves the
   * existing restart recovery in charge and is logged without exception details.
   */
  private void releaseConversation(Run run) {
    try {
      persist(run);
    } catch (RuntimeException secondary) {
      LOG.warn("knowledge_agent_conversation_release_failed run_id={}", run.id);
    }
  }

  private void persist(Run run) {
    if (conversations != null && run.conversationId != null) {
      conversations.finish(run.actor, run.conversationId, run.view());
    }
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      runs.values().forEach(run -> stop(run, "cancelled", null));
      executor.shutdown();
      timer.shutdownNow();
    }
  }

  private final class Run {
    final String id = UUID.randomUUID().toString();
    final String callbackToken =
        UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");
    final Actor actor;
    final String question;
    final String conversationId;
    final long started = System.nanoTime();
    final List<AgentRunResult.Event> events = new ArrayList<>();
    final Set<Subscription> subscriptions = new HashSet<>();
    final LinkedHashMap<KnowledgeEvidence.Key, String> sourceIds = new LinkedHashMap<>();
    final LinkedHashMap<String, KnowledgeEvidence> originals = new LinkedHashMap<>();
    final Set<String> readIds = new java.util.LinkedHashSet<>();
    volatile String status = "running";
    volatile Thread worker;
    Thread callback;
    int callbackEventIndex = -1;
    long callbackStarted;
    volatile TextRuntimeSnapshot snapshot;
    volatile EvidenceScope scope;
    volatile RetrievalSettings settings;
    int modelCalls;
    int toolCalls;
    KnowledgeAnswerResult result;
    List<AgentProtocol.Suggestion> suggestions = List.of();
    AgentRunResult.Problem error;
    String preview = "";
    String displayReasoning = "";
    long streamRevision;
    long lastPublished;
    boolean deltaPending;
    boolean flushScheduled;
    boolean streamFailed;

    Run(Actor actor, String question, String conversationId) {
      this.actor = actor;
      this.question = question;
      this.conversationId = conversationId;
      event("running", "正在启动知识助手");
    }

    boolean running() {
      return "running".equals(status);
    }

    boolean active() {
      return running()
          && System.nanoTime() - started < timeoutNanos
          && !Thread.currentThread().isInterrupted();
    }

    synchronized void event(String type, String message) {
      events.add(new AgentRunResult.Event(events.size() + 1, type, message));
      if (running()) publish();
    }

    synchronized void callbackEvent(String type, String message, String query) {
      String tool =
          switch (type) {
            case "planning" -> "model";
            case "searching" -> "knowledge_search";
            case "reading" -> "knowledge_read";
            default -> throw invalid();
          };
      callbackEventIndex = events.size();
      callbackStarted = System.nanoTime();
      events.add(
          new AgentRunResult.Event(
              events.size() + 1, type, message, AgentRunResult.Details.running(tool, query)));
      if ("model".equals(tool)) {
        preview = "";
        displayReasoning = "";
      }
      publish();
    }

    synchronized Consumer<AgentModelDelta> modelListener() {
      int eventIndex = callbackEventIndex;
      return delta -> modelDelta(eventIndex, delta);
    }

    synchronized void modelDelta(int expectedEventIndex, AgentModelDelta delta) {
      KnowledgeAgentService.this.active(this);
      if (callbackEventIndex < 0 || expectedEventIndex != callbackEventIndex || delta == null)
        throw invalid();
      var event = events.get(callbackEventIndex);
      var previous = event.details();
      if (previous == null
          || !"running".equals(previous.status())
          || !"model".equals(previous.toolName())) throw invalid();
      String reasoning = displayReasoning + delta.reasoningDelta();
      boolean truncated =
          previous.reasoningTruncated() || reasoning.codePointCount(0, reasoning.length()) > 16000;
      if (reasoning.codePointCount(0, reasoning.length()) > 16000)
        reasoning = reasoning.substring(0, reasoning.offsetByCodePoints(0, 16000));
      displayReasoning = reasoning;
      var details =
          new AgentRunResult.Details(
              previous.status(),
              previous.toolName(),
              previous.query(),
              previous.elapsedMs(),
              previous.resultCount(),
              previous.documentCount(),
              previous.sources(),
              previous.safeCode(),
              reasoning.isBlank() ? null : reasoning,
              truncated && !reasoning.isBlank(),
              previous.toolNames());
      events.set(
          callbackEventIndex,
          new AgentRunResult.Event(event.sequence(), event.type(), event.message(), details));
      preview += delta.answerDelta();
      publishDelta();
    }

    /** At most one delta snapshot per interval; a trailing flush delivers the latest state. */
    synchronized void publishDelta() {
      long wait = lastPublished + DELTA_PUBLISH_INTERVAL_NANOS - System.nanoTime();
      if (wait <= 0) {
        publish();
        return;
      }
      deltaPending = true;
      if (flushScheduled) return;
      try {
        timer.schedule(this::flushDelta, wait, TimeUnit.NANOSECONDS);
        flushScheduled = true;
      } catch (RejectedExecutionException closing) {
        publish();
      }
    }

    synchronized void flushDelta() {
      flushScheduled = false;
      if (deltaPending && running()) publish();
    }

    synchronized AgentRunStreamSnapshot streamView() {
      return new AgentRunStreamSnapshot(streamRevision, view(), preview);
    }

    synchronized void publish() {
      streamRevision++;
      lastPublished = System.nanoTime();
      deltaPending = false;
      var snapshot = streamView();
      subscriptions.forEach(subscription -> subscription.offer(snapshot));
    }

    synchronized void finishCallback(String state, String code, Object result) {
      if (callbackEventIndex < 0) return;
      var event = events.get(callbackEventIndex);
      var previous = event.details();
      if (previous == null || !"running".equals(previous.status())) return;
      var sources = new ArrayList<AgentRunResult.Source>();
      List<String> tools = List.of();
      String reasoning = previous.reasoning();
      boolean truncated = previous.reasoningTruncated();
      Integer count = null;
      Integer documents = null;
      if (result instanceof AgentProtocol.ModelResult modelResult) {
        var message = modelResult.message();
        tools = message.toolCalls().stream().map(call -> call.function().name()).toList();
        String raw = message.reasoningContent();
        if (raw != null && !raw.isBlank()) {
          truncated = raw.codePointCount(0, raw.length()) > 16000;
          reasoning = truncated ? raw.substring(0, raw.offsetByCodePoints(0, 16000)) : raw;
        }
      } else if (result instanceof AgentProtocol.SearchResult found) {
        for (var source : found.sources()) {
          sources.add(sourceDetails(source.sourceId(), originals.get(source.sourceId())));
        }
        count = sources.size();
      } else if (result instanceof AgentProtocol.ReadResult read) {
        for (var source : read.sources()) {
          sources.add(sourceDetails(source.sourceId(), originals.get(source.sourceId())));
        }
        count = sources.size();
      }
      if (count != null) {
        documents =
            (int) sources.stream().map(AgentRunResult.Source::documentId).distinct().count();
      }
      var details =
          new AgentRunResult.Details(
              state,
              previous.toolName(),
              previous.query(),
              Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - callbackStarted)),
              count,
              documents,
              sources,
              code,
              reasoning,
              truncated,
              tools);
      events.set(
          callbackEventIndex,
          new AgentRunResult.Event(event.sequence(), event.type(), event.message(), details));
      callbackEventIndex = -1;
      if (running()) publish();
    }

    synchronized AgentRunResult view() {
      return new AgentRunResult(id, status, events, result, suggestions, error);
    }
  }
}
