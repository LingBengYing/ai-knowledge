package com.evidence.rag.service;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AnswerEligibility;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.EvidenceScope;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.PublishedEvidence;
import com.evidence.rag.model.domain.SourceEvidence;
import com.evidence.rag.model.domain.TraceDraft;
import com.evidence.rag.model.dto.TraceReceipt;
import com.evidence.rag.model.entity.TraceCitationEntity;
import com.evidence.rag.repository.EvidenceRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** Current-ACL evidence authority and atomic final trace decision. Remote work is excluded. */
@Service
public final class EvidenceService {
  private static final long MAX_PAGE_BYTES = 8L * 1024 * 1024;
  private final SqliteAuthorityStore store;
  private final EvidenceRepository evidence;
  private final ManagementRepository management;
  private final DocumentPermissionPolicy permissions;

  public EvidenceService(
      SqliteAuthorityStore store,
      EvidenceRepository evidence,
      ManagementRepository management,
      DocumentPermissionPolicy permissions) {
    this.store = store;
    this.evidence = evidence;
    this.management = management;
    this.permissions = permissions;
  }

  public EvidenceScope snapshot(Actor actor, DocumentSelection selection, IndexTarget target) {
    if (actor == null || selection == null || target == null) {
      throw ModelValues.invalid();
    }
    return store.transaction(
        () -> {
          var publications = evidence.findActivePublications(actor, selection);
          if (publications.size() > 128) {
            throw new ApplicationException(
                FailureKind.CAPACITY_EXCEEDED,
                "scope_capacity_exceeded",
                "当前授权范围超过处理上限，请明确缩小资料范围。");
          }
          if (!selection.all() && publications.size() != selection.documentIds().size()) {
            throw ModelValues.notFound();
          }
          for (var publication : publications) {
            permissions.require(management.currentRole(actor, publication.documentId()), false);
            if (!publication.target().equals(target)) {
              throw ModelValues.notFound();
            }
          }
          return new EvidenceScope(actor, selection, publications);
        });
  }

  public List<PublishedEvidence> hydrate(EvidenceScope snapshot, List<String> physicalIds) {
    requireScope(snapshot);
    var ids = candidateIds(physicalIds);
    return store.transaction(
        () -> {
          if (!current(snapshot)) {
            throw changed();
          }
          return hydrated(snapshot, ids);
        });
  }

  public TraceReceipt finish(
      EvidenceScope snapshot, TraceDraft draft, Supplier<AnswerEligibility> eligibility) {
    requireScope(snapshot);
    if (draft == null || eligibility == null) {
      throw ModelValues.invalid();
    }
    return store.transaction(
        () -> {
          // Check after waiting for the shared monitor, not merely before entering this
          // transaction.
          AnswerEligibility firstCheck = eligibility.get();
          if (firstCheck == null) {
            throw ModelValues.invalid();
          }
          var historical =
              evidence.findHistoricalPublications(
                  snapshot.actor().workspaceId(),
                  snapshot.publications().stream().map(PublicationVersion::publicationId).toList());
          if (!new HashSet<>(historical).equals(new HashSet<>(snapshot.publications()))) {
            throw ModelValues.invalid();
          }
          TraceDraft decision = eligibleDraft(draft, firstCheck);
          if (firstCheck == AnswerEligibility.ELIGIBLE && !current(snapshot)) {
            decision = refused(draft, "scope_changed");
          }
          var citations = new ArrayList<TraceCitationEntity>();
          if ("answered".equals(decision.outcome())) {
            var ids =
                decision.evidence().stream()
                    .map(item -> item.physicalSegmentId())
                    .distinct()
                    .toList();
            var byId = new HashMap<String, PublishedEvidence>();
            for (var item : hydrated(snapshot, ids)) {
              byId.put(item.physicalSegmentId(), item);
            }
            for (var locator : decision.evidence()) {
              var source =
                  new SourceEvidence(
                      byId.get(locator.physicalSegmentId()), locator.start(), locator.end());
              var item = source.evidence();
              citations.add(
                  new TraceCitationEntity(
                      locator,
                      item.publication().publicationId(),
                      item.segment().segmentId(),
                      item.segment().page(),
                      item.segment().textSha256(),
                      item.pageSha256(),
                      quoteHash(source)));
            }
          }
          AnswerEligibility lastCheck = eligibility.get();
          if (lastCheck == null) {
            throw ModelValues.invalid();
          }
          if (firstCheck == AnswerEligibility.ELIGIBLE && lastCheck != AnswerEligibility.ELIGIBLE) {
            decision = eligibleDraft(draft, lastCheck);
            citations.clear();
          }
          String traceId = UUID.randomUUID().toString();
          evidence.insertTrace(traceId, snapshot, decision, citations, Instant.now().toString());
          return new TraceReceipt(traceId, decision.outcome(), decision.reasonCode());
        });
  }

  public SourceEvidence source(Actor actor, String traceId, int citationOrdinal) {
    if (actor == null) {
      throw ModelValues.invalid();
    }
    ModelValues.identifier(traceId, 128);
    if (citationOrdinal < 1 || citationOrdinal > 32) {
      throw ModelValues.notFound();
    }
    return store.transaction(
        () -> {
          var scope = evidence.findTraceScope(actor, traceId);
          if (scope == null || !current(scope)) {
            throw ModelValues.notFound();
          }
          var saved = evidence.findTraceCitation(actor, traceId, citationOrdinal);
          if (saved == null) {
            throw ModelValues.notFound();
          }
          var item = hydrated(scope, List.of(saved.evidence().physicalSegmentId())).getFirst();
          var source = new SourceEvidence(item, saved.evidence().start(), saved.evidence().end());
          if (!item.publication().publicationId().equals(saved.publicationId())
              || !item.segment().segmentId().equals(saved.sourceSegmentId())
              || item.segment().page() != saved.page()
              || !item.segment().textSha256().equals(saved.textSha256())
              || !item.pageSha256().equals(saved.pageSha256())
              || !quoteHash(source).equals(saved.quoteSha256())) {
            throw ModelValues.notFound();
          }
          return source;
        });
  }

  private boolean current(EvidenceScope scope) {
    var selected =
        DocumentSelection.selected(
            scope.publications().stream().map(PublicationVersion::documentId).toList());
    var current = evidence.findActivePublications(scope.actor(), selected);
    if (!new HashSet<>(current).equals(new HashSet<>(scope.publications()))) {
      return false;
    }
    for (var publication : scope.publications()) {
      if (!permissions.canRead(management.currentRole(scope.actor(), publication.documentId()))) {
        return false;
      }
    }
    return true;
  }

  private List<PublishedEvidence> hydrated(EvidenceScope scope, List<String> ids) {
    if (evidence.publishedPageBytes(scope, ids) > MAX_PAGE_BYTES) {
      throw new ApplicationException(
          FailureKind.CAPACITY_EXCEEDED, "evidence_capacity_exceeded", "引用资料页超过处理上限，请缩小资料范围。");
    }
    var items = evidence.findPublishedEvidence(scope, ids);
    if (items.size() != ids.size()) {
      throw ModelValues.invalid();
    }
    var byId = new HashMap<String, PublishedEvidence>();
    for (var item : items) {
      if (!scope.publications().contains(item.publication())
          || byId.put(item.physicalSegmentId(), item) != null) {
        throw ModelValues.invalid();
      }
    }
    return ids.stream().map(byId::get).toList();
  }

  private static List<String> candidateIds(List<String> physicalIds) {
    if (physicalIds == null || physicalIds.size() > 64) {
      throw ModelValues.invalid();
    }
    var seen = new HashSet<String>();
    for (String id : physicalIds) {
      ModelValues.identifier(id, 128);
      if (!seen.add(id)) {
        throw ModelValues.invalid();
      }
    }
    return List.copyOf(physicalIds);
  }

  private static void requireScope(EvidenceScope scope) {
    if (scope == null) {
      throw ModelValues.invalid();
    }
  }

  private static TraceDraft refused(TraceDraft draft, String reason) {
    return new TraceDraft(
        draft.questionSha256(),
        null,
        "abstained",
        reason,
        draft.modelRevision(),
        draft.promptRevision(),
        draft.policyRevision(),
        List.of());
  }

  private static TraceDraft eligibleDraft(TraceDraft draft, AnswerEligibility eligibility) {
    return switch (eligibility) {
      case ELIGIBLE -> draft;
      case PROCESSING_TIMEOUT -> refused(draft, "processing_timeout");
      case CONFIGURATION_CHANGED -> refused(draft, "configuration_changed");
    };
  }

  private static String quoteHash(SourceEvidence source) {
    String page = source.evidence().page().text();
    String quote =
        page.substring(
            page.offsetByCodePoints(0, source.start()), page.offsetByCodePoints(0, source.end()));
    return ModelValues.sha256(quote.getBytes(StandardCharsets.UTF_8));
  }

  private static ApplicationException changed() {
    return new ApplicationException(FailureKind.CONFLICT, "scope_changed", "资料授权或已发布证据发生变化，请重新查询。");
  }
}
