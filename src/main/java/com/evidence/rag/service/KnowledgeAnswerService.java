package com.evidence.rag.service;

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
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.model.dto.KnowledgeAnswerResult;
import com.evidence.rag.model.dto.KnowledgeCitation;
import com.evidence.rag.model.dto.KnowledgeSourceResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Full-workspace retrieval, one original-backed synthesis and server-owned source locators. */
public final class KnowledgeAnswerService implements AutoCloseable {
  private static final String POLICY = "java-shared-workspace-knowledge-answer-v3";
  private static final String REFUSAL = "当前资料不足以形成可核对的完整回答。";
  private final EvidenceService evidence;
  private final ProductHelpService retrieval;
  private final ManagedTextRuntime runtime;
  private final KnowledgeTraceService traces;
  private final long deadlineNanos;
  private final Semaphore admission;
  private final ExecutorService executor;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Set<Progress> pending = ConcurrentHashMap.newKeySet();

  public KnowledgeAnswerService(
      EvidenceService evidence,
      ProductHelpService retrieval,
      ManagedTextRuntime runtime,
      KnowledgeTraceService traces,
      Duration deadline,
      int maximumConcurrent) {
    if (evidence == null
        || retrieval == null
        || runtime == null
        || traces == null
        || deadline == null
        || deadline.compareTo(Duration.ofMillis(10)) < 0
        || deadline.compareTo(Duration.ofMinutes(10)) > 0
        || maximumConcurrent < 1
        || maximumConcurrent > 8) {
      throw ModelValues.invalid();
    }
    this.evidence = evidence;
    this.retrieval = retrieval;
    this.runtime = runtime;
    this.traces = traces;
    deadlineNanos = deadline.toNanos();
    admission = new Semaphore(maximumConcurrent);
    executor =
        Executors.newFixedThreadPool(
            maximumConcurrent,
            runnable -> {
              var thread = new Thread(runnable, "knowledge-answer");
              thread.setDaemon(true);
              return thread;
            });
  }

  public KnowledgeAnswerResult answer(Actor actor, AnswerCommand command) {
    if (actor == null || command == null) {
      throw ModelValues.invalid();
    }
    if (closed.get()) {
      throw unavailable();
    }
    var reservation = evidence.operationGate().reserve();
    if (!admission.tryAcquire()) {
      reservation.close();
      throw new ApplicationException(
          FailureKind.CAPACITY_EXCEEDED, "answer_capacity_exceeded", "问答任务已达并发上限。");
    }
    var progress = new Progress();
    var result = new CompletableFuture<KnowledgeAnswerResult>();
    pending.add(progress);
    try {
      executor.execute(
          () -> {
            try (var operation = reservation.begin()) {
              progress.worker.attach();
              result.complete(execute(actor, command, progress));
            } catch (RuntimeException | Error failed) {
              result.completeExceptionally(failed);
            } finally {
              progress.worker.detach();
              pending.remove(progress);
              admission.release();
              reservation.close();
            }
          });
    } catch (RuntimeException failed) {
      pending.remove(progress);
      admission.release();
      reservation.close();
      throw unavailable();
    }
    try {
      return result.get(Math.max(0, progress.remaining()), TimeUnit.NANOSECONDS);
    } catch (InterruptedException interrupted) {
      progress.cancel();
      Thread.currentThread().interrupt();
      throw timeout();
    } catch (TimeoutException expired) {
      progress.cancel();
      throw timeout();
    } catch (ExecutionException failed) {
      if (failed.getCause() instanceof ApplicationException problem) {
        throw problem;
      }
      throw unavailable();
    }
  }

  private KnowledgeAnswerResult execute(Actor actor, AnswerCommand command, Progress progress) {
    var retrievalSettings = retrieval.settingsSnapshot();
    var snapshot = runtime.capture();
    var scope = evidence.snapshot(actor, DocumentSelection.allDocuments(), snapshot.target());
    Proposal proposal;
    try {
      proposal = propose(scope, command.question(), snapshot, progress, retrievalSettings);
    } catch (Rejected rejected) {
      proposal = new Proposal(null, rejected.reason, List.of());
    } catch (TextModels.Failure failed) {
      proposal = new Proposal(null, "model_failure", List.of());
    } catch (ApplicationException failed) {
      String reason =
          Set.of(
                      "scope_changed",
                      "configuration_changed",
                      "evidence_capacity_exceeded",
                      "retrieval_embedding_failed",
                      "retrieval_rerank_failed",
                      "retrieval_search_failed",
                      "evidence_changed",
                      "processing_timeout")
                  .contains(failed.code())
              ? failed.code()
              : "evidence_unavailable";
      proposal = new Proposal(null, reason, List.of());
    }
    var draft =
        new KnowledgeTraceDraft(
            sha(command.question()),
            proposal.answer() == null ? null : sha(proposal.answer()),
            proposal.answer() == null ? "abstained" : "answered",
            proposal.reason(),
            snapshot.modelsRevision(),
            TextModels.KNOWLEDGE_ANSWER_PROMPT_REVISION,
            POLICY
                + ":retrieval-v"
                + retrievalSettings.version()
                + ":"
                + retrievalSettings.fingerprint(),
            proposal.references());
    var receipt =
        traces.finish(
            scope,
            draft,
            () ->
                !progress.active()
                    ? "processing_timeout"
                    : runtime.isCurrent(snapshot) ? null : "configuration_changed");
    boolean answered = "answered".equals(receipt.outcome());
    return new KnowledgeAnswerResult(
        receipt.traceId(),
        receipt.outcome(),
        answered ? proposal.answer() : REFUSAL,
        receipt.reasonCode(),
        answered
            ? proposal.references().stream()
                .map(ref -> KnowledgeCitation.from(receipt.traceId(), ref))
                .toList()
            : List.of());
  }

  private Proposal propose(
      EvidenceScope scope,
      String question,
      TextRuntimeSnapshot snapshot,
      Progress progress,
      RetrievalSettings retrievalSettings) {
    check(scope, snapshot, progress);
    if (scope.publications().isEmpty()) {
      throw new Rejected("empty_scope");
    }
    var retrieved = retrieval.retrieve(scope, question, snapshot, retrievalSettings);
    check(scope, snapshot, progress);
    if (retrieved.isEmpty()) {
      throw new Rejected("no_evidence");
    }
    var keys =
        retrieved.stream()
            .map(source -> new KnowledgeEvidence.Key(source.kind(), source.physicalId()))
            .toList();
    var sources = evidence.knowledgeEvidence(scope, keys);
    for (int i = 0; i < sources.size(); i++) {
      if (!sources.get(i).source().equals(retrieved.get(i))) {
        throw new Rejected("evidence_changed");
      }
    }
    var originals = new LinkedHashMap<String, KnowledgeReference>();
    for (var source : sources) {
      originals.put(
          "source-" + (originals.size() + 1),
          new KnowledgeReference(
              originals.size() + 1,
              source,
              source.context().startCodePoint(),
              source.context().endCodePoint()));
    }
    var synthesisEvidence =
        originals.entrySet().stream()
            .map(
                entry ->
                    new TextModels.SynthesisEvidence(
                        entry.getKey(), entry.getValue().quote(), entry.getValue().quote()))
            .toList();
    var synthesis = snapshot.models().answerKnowledge(question, synthesisEvidence);
    requireUnchanged(scope, sources, snapshot, progress);
    validate(synthesis, originals.keySet());
    if (synthesis.refused()) {
      throw new Rejected("model_refused");
    }
    var references = new LinkedHashMap<String, KnowledgeReference>();
    var paragraphs = new ArrayList<String>();
    for (var statement : synthesis.statements()) {
      var citationIds = new ArrayList<String>();
      for (String id : statement.evidenceIds()) {
        var reference =
            references.computeIfAbsent(
                id,
                key -> {
                  var original = originals.get(key);
                  return new KnowledgeReference(
                      references.size() + 1, original.evidence(), original.start(), original.end());
                });
        citationIds.add("[" + reference.citationId() + "]");
      }
      paragraphs.add(statement.text() + " " + String.join("", citationIds));
    }
    return new Proposal(String.join("\n\n", paragraphs), null, List.copyOf(references.values()));
  }

  private void requireUnchanged(
      EvidenceScope scope,
      List<KnowledgeEvidence> sources,
      TextRuntimeSnapshot snapshot,
      Progress progress) {
    check(scope, snapshot, progress);
    if (!evidence
        .knowledgeEvidence(scope, sources.stream().map(KnowledgeEvidence::key).toList())
        .equals(sources)) {
      throw new Rejected("evidence_changed");
    }
  }

  private void check(EvidenceScope scope, TextRuntimeSnapshot snapshot, Progress progress) {
    if (!progress.active()) {
      throw new Rejected("processing_timeout");
    }
    if (!runtime.isCurrent(snapshot)) {
      throw new Rejected("configuration_changed");
    }
    evidence.knowledgeEvidence(scope, List.of());
  }

  private static void validate(TextModels.Synthesis synthesis, Set<String> ids) {
    if (synthesis == null || synthesis.refused() != synthesis.statements().isEmpty()) {
      throw new Rejected("model_invalid_response");
    }
    for (var statement : synthesis.statements()) {
      if (statement == null
          || statement.text() == null
          || statement.text().isBlank()
          || statement.evidenceIds().isEmpty()
          || new HashSet<>(statement.evidenceIds()).size() != statement.evidenceIds().size()
          || !ids.containsAll(statement.evidenceIds())) {
        throw new Rejected("model_invalid_response");
      }
    }
  }

  public KnowledgeSourceResult source(Actor actor, String id, int ordinal) {
    return traces.source(actor, id, ordinal);
  }

  private static String sha(String text) {
    return ModelValues.sha256(text.getBytes(StandardCharsets.UTF_8));
  }

  private static ApplicationException unavailable() {
    return new ApplicationException(FailureKind.UNAVAILABLE, "answers_unavailable", "知识问答暂不可用。");
  }

  private static ApplicationException timeout() {
    return new ApplicationException(FailureKind.TIMEOUT, "processing_timeout", "知识问答超过处理期限。");
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      pending.forEach(Progress::cancel);
      executor.shutdown();
    }
  }

  private record Proposal(String answer, String reason, List<KnowledgeReference> references) {}

  private static final class Rejected extends RuntimeException {
    private final String reason;

    private Rejected(String reason) {
      super("Knowledge answer rejected", null, false, false);
      this.reason = reason;
    }
  }

  private final class Progress {
    private final long started = System.nanoTime();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final WorkerInterrupt worker = new WorkerInterrupt();

    long remaining() {
      return deadlineNanos - (System.nanoTime() - started);
    }

    boolean active() {
      return !closed.get()
          && !cancelled.get()
          && !Thread.currentThread().isInterrupted()
          && remaining() > 0;
    }

    void cancel() {
      cancelled.set(true);
      worker.interrupt();
    }
  }
}
