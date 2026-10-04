package com.evidence.rag.service;

import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.EvidenceScope;
import com.evidence.rag.model.domain.GroundingQuote;
import com.evidence.rag.model.domain.KnowledgeEvidence;
import com.evidence.rag.model.domain.KnowledgeReference;
import com.evidence.rag.model.domain.KnowledgeTraceDraft;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.model.dto.KnowledgeAnswerResult;
import com.evidence.rag.model.dto.KnowledgeCitation;
import com.evidence.rag.model.dto.KnowledgeSourceResult;
import com.evidence.rag.tool.answer.TextGrounding;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
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
import java.util.concurrent.atomic.AtomicReference;

/** One knowledge-answer Module: original proof, bounded synthesis, verification, atomic release. */
public final class KnowledgeAnswerService implements AutoCloseable {
  private static final String POLICY = "java-knowledge-answer-v1:" + TextGrounding.VERSION;
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
  private final TextGrounding grounding = new TextGrounding();

  public KnowledgeAnswerService(
      EvidenceService evidence, ProductHelpService retrieval, ManagedTextRuntime runtime,
      KnowledgeTraceService traces, Duration deadline, int maximumConcurrent) {
    if (evidence == null || retrieval == null || runtime == null || traces == null || deadline == null
        || deadline.compareTo(Duration.ofMillis(10)) < 0 || deadline.compareTo(Duration.ofMinutes(10)) > 0
        || maximumConcurrent < 1 || maximumConcurrent > 8) {
      throw ModelValues.invalid();
    }
    this.evidence = evidence;
    this.retrieval = retrieval;
    this.runtime = runtime;
    this.traces = traces;
    deadlineNanos = deadline.toNanos();
    admission = new Semaphore(maximumConcurrent);
    executor = Executors.newFixedThreadPool(maximumConcurrent, runnable -> {
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
      throw new ApplicationException(FailureKind.CAPACITY_EXCEEDED, "answer_capacity_exceeded", "问答任务已达并发上限。");
    }
    var progress = new Progress();
    var result = new CompletableFuture<KnowledgeAnswerResult>();
    pending.add(progress);
    try {
      executor.execute(() -> {
        try (var operation = reservation.begin()) {
          progress.thread.set(Thread.currentThread());
          result.complete(execute(actor, command, progress));
        } catch (RuntimeException | Error failed) {
          result.completeExceptionally(failed);
        } finally {
          progress.thread.set(null);
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
    var snapshot = runtime.capture();
    var scope = evidence.snapshot(actor, command.selection(), snapshot.target());
    Proposal proposal;
    try {
      proposal = propose(scope, command.question(), snapshot, progress);
    } catch (Rejected rejected) {
      proposal = new Proposal(null, rejected.reason, List.of());
    } catch (TextModels.Failure failed) {
      proposal = new Proposal(null, "model_failure", List.of());
    } catch (ApplicationException failed) {
      String reason = Set.of("scope_changed", "configuration_changed", "evidence_capacity_exceeded",
          "retrieval_embedding_failed", "retrieval_rerank_failed", "retrieval_search_failed",
          "evidence_changed", "processing_timeout").contains(failed.code())
          ? failed.code() : "evidence_unavailable";
      proposal = new Proposal(null, reason, List.of());
    }
    var draft = new KnowledgeTraceDraft(sha(command.question()),
        proposal.answer() == null ? null : sha(proposal.answer()),
        proposal.answer() == null ? "abstained" : "answered", proposal.reason(),
        snapshot.modelsRevision(), TextModels.SYNTHESIS_PROMPT_REVISION, POLICY, proposal.references());
    var receipt = traces.finish(scope, draft, () -> !progress.active() ? "processing_timeout"
        : runtime.isCurrent(snapshot) ? null : "configuration_changed");
    boolean answered = "answered".equals(receipt.outcome());
    return new KnowledgeAnswerResult(receipt.traceId(), receipt.outcome(),
        answered ? proposal.answer() : REFUSAL, receipt.reasonCode(),
        answered ? proposal.references().stream().map(ref -> KnowledgeCitation.from(receipt.traceId(), ref)).toList() : List.of());
  }

  private Proposal propose(
      EvidenceScope scope, String question, TextRuntimeSnapshot snapshot, Progress progress) {
    check(scope, snapshot, progress);
    if (scope.publications().isEmpty()) {
      throw new Rejected("empty_scope");
    }
    var retrieved = retrieval.retrieve(scope, question, snapshot);
    check(scope, snapshot, progress);
    if (retrieved.isEmpty()) {
      throw new Rejected("no_evidence");
    }
    var keys = retrieved.stream().map(source -> new KnowledgeEvidence.Key(source.kind(), source.physicalId())).toList();
    var sources = evidence.knowledgeEvidence(scope, keys);
    for (int i = 0; i < sources.size(); i++) {
      if (!sources.get(i).source().equals(retrieved.get(i))) {
        throw new Rejected("evidence_changed");
      }
    }
    var extracted = snapshot.models().extract(question,
        sources.stream().map(source -> new TextModels.Evidence(source.context().physicalId(), source.context().snippet())).toList());
    requireUnchanged(scope, sources, snapshot, progress);
    if (extracted == null || extracted.quotes().size() > 32
        || extracted.refused() != extracted.quotes().isEmpty()) {
      throw new Rejected("model_invalid_response");
    }
    if (extracted.refused()) {
      throw new Rejected("model_refused");
    }
    var quotes = extracted.quotes().stream().map(quote -> {
      if (quote == null) {
        throw new Rejected("invalid_quote");
      }
      return new GroundingQuote(quote.evidenceId(), quote.quote());
    }).toList();
    var proof = grounding.verifyText(question, sources.stream().map(KnowledgeEvidence::context).toList(), quotes);
    check(scope, snapshot, progress);
    if (!proof.supported() || proof.quotes().isEmpty() || proof.quotes().size() > 32) {
      throw new Rejected(proof.supported() ? "incomplete_evidence" : proof.reason());
    }
    var byPhysical = new HashMap<String, KnowledgeEvidence>();
    sources.forEach(source -> byPhysical.put(source.source().physicalId(), source));
    var proved = new LinkedHashMap<String, KnowledgeReference>();
    for (var quote : proof.quotes()) {
      var original = byPhysical.get(quote.physicalId());
      var reference = new KnowledgeReference(proved.size() + 1, original, quote.start(), quote.end());
      if (!reference.quote().equals(quote.quote())) {
        throw new Rejected("invalid_quote");
      }
      proved.put("proof-" + (proved.size() + 1), reference);
    }
    // Quotes establish facts; complete original contexts retain conditions, negations and conflicts.
    var synthesisEvidence = proved.entrySet().stream()
        .map(entry -> new TextModels.SynthesisEvidence(entry.getKey(), entry.getValue().quote(),
            entry.getValue().evidence().context().contextText())).toList();
    var synthesis = snapshot.models().synthesize(question, synthesisEvidence);
    requireUnchanged(scope, sources, snapshot, progress);
    validate(synthesis, proved.keySet());
    if (synthesis.refused()) {
      throw new Rejected("model_refused");
    }
    if (!snapshot.models().verifySynthesis(question, synthesis, synthesisEvidence)) {
      throw new Rejected("unsupported_synthesis");
    }
    requireUnchanged(scope, sources, snapshot, progress);
    var references = new LinkedHashMap<String, KnowledgeReference>();
    var paragraphs = new ArrayList<String>();
    for (var statement : synthesis.statements()) {
      var citationIds = new ArrayList<String>();
      for (String id : statement.evidenceIds()) {
        var reference = references.computeIfAbsent(id, key -> {
          var original = proved.get(key);
          return new KnowledgeReference(references.size() + 1, original.evidence(), original.start(), original.end());
        });
        citationIds.add("[" + reference.citationId() + "]");
      }
      paragraphs.add(statement.text() + " " + String.join("", citationIds));
    }
    return new Proposal(String.join("\n\n", paragraphs), null, List.copyOf(references.values()));
  }

  private void requireUnchanged(
      EvidenceScope scope, List<KnowledgeEvidence> sources, TextRuntimeSnapshot snapshot, Progress progress) {
    check(scope, snapshot, progress);
    if (!evidence.knowledgeEvidence(scope, sources.stream().map(KnowledgeEvidence::key).toList()).equals(sources)) {
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
    if (synthesis == null || synthesis.statements().size() > 8
        || synthesis.refused() != synthesis.statements().isEmpty()) {
      throw new Rejected("model_invalid_response");
    }
    var used = new HashSet<String>();
    for (var statement : synthesis.statements()) {
      if (statement == null || statement.text() == null || statement.text().isBlank()
          || statement.text().codePointCount(0, statement.text().length()) > 1024
          || statement.evidenceIds().isEmpty() || statement.evidenceIds().size() > 8
          || new HashSet<>(statement.evidenceIds()).size() != statement.evidenceIds().size()
          || !ids.containsAll(statement.evidenceIds())) {
        throw new Rejected("model_invalid_response");
      }
      used.addAll(statement.evidenceIds());
    }
    if (used.size() > 32) {
      throw new Rejected("model_invalid_response");
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
    private final AtomicReference<Thread> thread = new AtomicReference<>();

    long remaining() {
      return deadlineNanos - (System.nanoTime() - started);
    }

    boolean active() {
      return !closed.get() && !cancelled.get() && !Thread.currentThread().isInterrupted() && remaining() > 0;
    }

    void cancel() {
      cancelled.set(true);
      var running = thread.get();
      if (running != null) {
        running.interrupt();
      }
    }
  }
}
