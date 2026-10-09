package com.evidence.rag.service;

import com.evidence.rag.client.model.AgentClient;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.EvidenceScope;
import com.evidence.rag.model.domain.KnowledgeEvidence;
import com.evidence.rag.model.domain.KnowledgeReference;
import com.evidence.rag.model.domain.KnowledgeTraceDraft;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.RetrievalSettings;
import com.evidence.rag.model.dto.AgentProtocol;
import com.evidence.rag.model.dto.AgentRunResult;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.model.dto.KnowledgeAnswerResult;
import com.evidence.rag.model.dto.KnowledgeCitation;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Optional DB-GPT task orchestration; Java owns all evidence, identities and formal citations. */
public final class KnowledgeAgentService implements AutoCloseable {
  public static final int MAX_STEPS = 8;
  private static final String PROMPT = "java-dbgpt-knowledge-agent-v1";
  private static final String REFUSAL = "当前资料不足以形成可核对的完整回答。";
  private final EvidenceService evidence;
  private final ProductHelpService retrieval;
  private final ManagedTextRuntime runtime;
  private final KnowledgeTraceService traces;
  private final AgentClient client;
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
    if (actor == null || !uuid(requestId)) throw ModelValues.invalid();
    question(question);
    if (closed.get()) throw unavailable();
    String identity = actor.workspaceId() + "\n" + actor.principalId() + "\n" + requestId;
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
    var run = new Run(actor, question);
    com.evidence.rag.model.domain.LibraryOperationGate.ReservedOperation reservation;
    try {
      reservation = evidence.operationGate().reserve();
    } catch (RuntimeException failed) {
      admission.release();
      throw failed;
    }
    runs.put(run.id, run);
    requestIds.put(identity, run.id);
    try {
      executor.execute(
          () -> {
            run.worker = Thread.currentThread();
            var deadline =
                timer.schedule(
                    () -> stop(run, "failed", "agent_timeout"), timeoutNanos, TimeUnit.NANOSECONDS);
            try (var operation = reservation.begin()) {
              active(run);
              run.snapshot = runtime.capture();
              run.settings = retrieval.settingsSnapshot();
              run.scope =
                  evidence.snapshot(actor, DocumentSelection.allDocuments(), run.snapshot.target());
              check(run);
              var proposal =
                  run.scope.publications().isEmpty()
                      ? new AgentProtocol.Proposal(true, List.of(), List.of())
                      : client.execute(
                          new AgentProtocol.RunRequest(run.id, run.question, run.callbackToken));
              finish(run, proposal);
            } catch (RuntimeException failed) {
              String code =
                  failed instanceof ApplicationException application
                      ? application.code()
                      : failed instanceof TextModels.Failure model ? model.code() : "agent_failed";
              stop(run, "failed", safeCode(code));
            } finally {
              deadline.cancel(false);
              run.worker = null;
              reservation.close();
              admission.release();
            }
          });
    } catch (RuntimeException failed) {
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
        run -> new AgentProtocol.ModelResult(run.snapshot.models().agentChat(request.messages())));
  }

  public AgentProtocol.SearchResult search(String id, String bearer, String query) {
    question(query);
    return callback(
        id,
        bearer,
        "searching",
        "正在检索资料",
        false,
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
      run.event(type, message);
    }
    try (var lease = evidence.operationGate().enter()) {
      check(run);
      T result = operation.apply(run);
      check(run);
      return result;
    } finally {
      synchronized (run) {
        run.callback = null;
      }
    }
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
      run.event("completed", "已完成资料分析");
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
    return Set.of(
                "agent_unavailable",
                "agent_invalid_response",
                "agent_timeout",
                "agent_limit_exceeded",
                "scope_changed",
                "configuration_changed",
                "evidence_changed")
            .contains(code)
        ? code
        : "agent_failed";
  }

  private static ApplicationException problem(FailureKind kind, String code) {
    return new ApplicationException(kind, code, "智能体任务未完成，请重新发起。");
  }

  private static ApplicationException invalid() {
    return problem(FailureKind.INVALID_INPUT, "agent_invalid_response");
  }

  private static ApplicationException unavailable() {
    return problem(FailureKind.UNAVAILABLE, "agent_unavailable");
  }

  private static void stop(Run run, String status, String code) {
    synchronized (run) {
      if (!run.running()) return;
      run.status = status;
      run.error = code == null ? null : new AgentRunResult.Problem(code, "智能体任务未完成，请重新发起。");
      run.event(status, "cancelled".equals(status) ? "已停止任务" : "任务未完成");
      if (run.callback != null && run.callback != Thread.currentThread()) run.callback.interrupt();
      if (run.worker != null && run.worker != Thread.currentThread()) run.worker.interrupt();
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
    final long started = System.nanoTime();
    final List<AgentRunResult.Event> events = new ArrayList<>();
    final LinkedHashMap<KnowledgeEvidence.Key, String> sourceIds = new LinkedHashMap<>();
    final LinkedHashMap<String, KnowledgeEvidence> originals = new LinkedHashMap<>();
    final Set<String> readIds = new java.util.LinkedHashSet<>();
    volatile String status = "running";
    volatile Thread worker;
    Thread callback;
    volatile TextRuntimeSnapshot snapshot;
    volatile EvidenceScope scope;
    volatile RetrievalSettings settings;
    int modelCalls;
    int toolCalls;
    KnowledgeAnswerResult result;
    List<AgentProtocol.Suggestion> suggestions = List.of();
    AgentRunResult.Problem error;

    Run(Actor actor, String question) {
      this.actor = actor;
      this.question = question;
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
    }

    synchronized AgentRunResult view() {
      return new AgentRunResult(id, status, events, result, suggestions, error);
    }
  }
}
