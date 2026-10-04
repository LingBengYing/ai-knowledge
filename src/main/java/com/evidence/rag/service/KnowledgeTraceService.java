package com.evidence.rag.service;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.EvidenceScope;
import com.evidence.rag.model.domain.KnowledgeCitationLocator;
import com.evidence.rag.model.domain.KnowledgeEvidence;
import com.evidence.rag.model.domain.KnowledgeReference;
import com.evidence.rag.model.domain.KnowledgeTraceDraft;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.dto.KnowledgeCitation;
import com.evidence.rag.model.dto.KnowledgeSourceResult;
import com.evidence.rag.model.dto.TraceReceipt;
import com.evidence.rag.repository.KnowledgeAnswerRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** Final full-scope decision and durable original-source reads; never calls a model. */
public final class KnowledgeTraceService {
  private final SqliteAuthorityStore store;
  private final EvidenceService evidence;
  private final KnowledgeAnswerRepository repository;

  public KnowledgeTraceService(SqliteAuthorityStore store, EvidenceService evidence) {
    this.store = store;
    this.evidence = evidence;
    repository = new KnowledgeAnswerRepository(store);
  }

  public TraceReceipt finish(
      EvidenceScope scope, KnowledgeTraceDraft draft, Supplier<String> ineligibleReason) {
    return store.transaction(() -> {
      KnowledgeTraceDraft decision = draft;
      String firstReason = ineligibleReason.get();
      if (firstReason != null) {
        decision = refused(draft, firstReason);
      } else {
        try {
          evidence.requireKnowledgeScopeInTransaction(scope);
          if ("answered".equals(draft.status())) {
            var keys = draft.references().stream().map(ref -> ref.evidence().key()).distinct().toList();
            var sources = evidence.knowledgeEvidenceInTransaction(scope, keys);
            var byKey = new HashMap<KnowledgeEvidence.Key, KnowledgeEvidence>();
            sources.forEach(source -> byKey.put(source.key(), source));
            for (var reference : draft.references()) {
              if (!reference.evidence().equals(byKey.get(reference.evidence().key()))) {
                throw ModelValues.invalid();
              }
            }
          }
        } catch (ApplicationException changed) {
          decision = refused(draft, "scope_changed");
        }
      }
      String lastReason = ineligibleReason.get();
      if (lastReason != null) {
        decision = refused(draft, lastReason);
      }
      String id = UUID.randomUUID().toString();
      repository.insert(id, scope, decision, Instant.now().toString());
      return new TraceReceipt(id, decision.status(), decision.reason());
    });
  }

  public KnowledgeSourceResult source(Actor actor, String id, int ordinal) {
    ModelValues.identifier(id, 128);
    if (actor == null || ordinal < 1 || ordinal > 32) {
      throw ModelValues.invalid();
    }
    return store.transaction(() -> {
      var scope = repository.scope(actor, id);
      var locator = repository.citation(id, ordinal);
      var material = evidence.knowledgeEvidenceInTransaction(scope, List.of(locator.key()));
      if (material.size() != 1) {
        throw ModelValues.notFound();
      }
      var reference = new KnowledgeReference(ordinal, material.getFirst(), locator.start(), locator.end());
      if (!KnowledgeCitationLocator.from(reference).equals(locator)) {
        throw ModelValues.notFound();
      }
      return new KnowledgeSourceResult(id, KnowledgeCitation.from(id, reference));
    });
  }

  private static KnowledgeTraceDraft refused(KnowledgeTraceDraft draft, String reason) {
    return new KnowledgeTraceDraft(draft.questionSha256(), null, "abstained", reason,
        draft.modelRevision(), draft.promptRevision(), draft.policyRevision(), List.of());
  }
}
