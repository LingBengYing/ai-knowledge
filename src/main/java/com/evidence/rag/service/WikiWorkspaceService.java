package com.evidence.rag.service;

import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.SynopsisSourceMaterial;
import com.evidence.rag.model.domain.WikiCompilationInput;
import com.evidence.rag.model.domain.WikiContent;
import com.evidence.rag.model.domain.WikiPageRevision;
import com.evidence.rag.model.domain.WikiProposal;
import com.evidence.rag.model.dto.WikiContentResult;
import com.evidence.rag.model.dto.WikiPageLifecycleCommand;
import com.evidence.rag.model.dto.WikiPageListResult;
import com.evidence.rag.model.dto.WikiPagePurgeResult;
import com.evidence.rag.model.dto.WikiPageResult;
import com.evidence.rag.model.dto.WikiProposalCommand;
import com.evidence.rag.model.dto.WikiProposalListResult;
import com.evidence.rag.model.dto.WikiProposalResult;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.repository.SynopsisMaterialRepository;
import com.evidence.rag.repository.WikiWorkspaceRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Versioned derived knowledge with explicit review; original publications remain authoritative. */
public final class WikiWorkspaceService {
  private final SqliteAuthorityStore store;
  private final WikiWorkspaceRepository repository;
  private final SynopsisMaterialRepository materials;
  private final WikiCompilationService compiler;
  private final ManagedTextRuntime runtime;
  private final String workspaceId;

  public WikiWorkspaceService(
      SqliteAuthorityStore store,
      WikiWorkspaceRepository repository,
      SynopsisMaterialRepository materials,
      WikiCompilationService compiler,
      ManagedTextRuntime runtime,
      String workspaceId) {
    this.store = Objects.requireNonNull(store);
    this.repository = Objects.requireNonNull(repository);
    this.materials = Objects.requireNonNull(materials);
    this.compiler = Objects.requireNonNull(compiler);
    this.runtime = runtime;
    this.workspaceId = ModelValues.identifier(workspaceId, 200);
  }

  public WikiProposalResult createProposal(Actor actor, WikiProposalCommand command) {
    requireMember(actor);
    if (command == null) {
      throw ModelValues.invalid();
    }
    try (var lease = store.operationGate().enter()) {
      var prepared = store.transaction(() -> prepare(actor, command));
      TextRuntimeSnapshot snapshot = null;
      List<WikiContent.Section> sections;
      String modelRevision;
      if ("model".equals(command.generationMethod())) {
        if (runtime == null) {
          throw new ApplicationException(
              FailureKind.UNAVAILABLE, "text_configuration_required", "请先完成并应用文字模型配置。");
        }
        snapshot = runtime.capture();
        try {
          sections = compiler.compile(snapshot.models(), command.title(), prepared.inputs());
        } catch (TextModels.Failure unavailable) {
          throw new ApplicationException(
              FailureKind.UNAVAILABLE, "wiki_compilation_unavailable", "知识编译模型暂不可用，未保存或发布提案。");
        }
        modelRevision = snapshot.modelsRevision();
      } else {
        sections = compiler.extract(prepared.inputs());
        modelRevision = "extractive-v1";
      }
      if (snapshot != null && !runtime.isCurrent(snapshot)) {
        throw new ApplicationException(
            FailureKind.CONFLICT, "configuration_changed", "编译期间模型配置已改变，请重新创建提案。");
      }
      var content = new WikiContent(command.title(), command.kind(), sections);
      String policyRevision =
          WikiCompilationService.POLICY_REVISION
              + ("model".equals(command.generationMethod())
                  ? ":" + TextModels.WIKI_COMPILATION_PROMPT_REVISION
                  : "");
      var proposal =
          new WikiProposal(
              UUID.randomUUID().toString(),
              prepared.pageId(),
              command.baseVersion(),
              prepared.before(),
              content,
              command.generationMethod(),
              modelRevision,
              policyRevision,
              "pending",
              Instant.now().toEpochMilli(),
              null);
      return store.transaction(
          () -> {
            requireBase(actor, prepared.pageId(), command.baseVersion());
            for (var input : prepared.inputs()) {
              requireCurrent(actor, input.publication());
            }
            repository.insertProposal(actor, proposal);
            return proposalResult(actor, proposal);
          });
    }
  }

  public WikiPageListResult pages(Actor actor, int offset, int limit, String query) {
    return pages(actor, offset, limit, query, "active");
  }

  public WikiPageListResult pages(Actor actor, int offset, int limit, String query, String state) {
    requireMember(actor);
    pagination(offset, limit);
    String selected = state == null ? "active" : state;
    if (!Set.of("active", "deleted").contains(selected)) {
      throw ModelValues.invalid();
    }
    String q = query == null ? "" : ModelValues.bounded(query.strip(), 200);
    return store.transaction(
        () ->
            new WikiPageListResult(
                repository.pages(actor, offset, limit, q, selected).stream()
                    .map(page -> pageResult(actor, page))
                    .toList(),
                repository.pageCount(actor, q, selected),
                offset,
                limit));
  }

  public WikiPageResult delete(Actor actor, String pageId, WikiPageLifecycleCommand command) {
    return changeLifecycle(actor, pageId, command, "deleted");
  }

  public WikiPageResult restore(Actor actor, String pageId, WikiPageLifecycleCommand command) {
    return changeLifecycle(actor, pageId, command, "active");
  }

  public WikiPagePurgeResult purge(Actor actor, String pageId, WikiPageLifecycleCommand command) {
    if (actor != null && !workspaceId.equals(actor.workspaceId())) {
      throw ModelValues.notFound();
    }
    requireMember(actor);
    ModelValues.identifier(pageId, 128);
    if (command == null) {
      throw ModelValues.invalid();
    }
    return store.transaction(
        () -> {
          repository.page(actor, pageId).orElseThrow(ModelValues::notFound);
          repository.purge(
              actor,
              pageId,
              command.version(),
              command.lifecycleVersion(),
              Instant.now().toEpochMilli());
          return new WikiPagePurgeResult(pageId, "purged");
        });
  }

  private WikiPageResult changeLifecycle(
      Actor actor, String pageId, WikiPageLifecycleCommand command, String state) {
    if (actor != null && !workspaceId.equals(actor.workspaceId())) {
      throw ModelValues.notFound();
    }
    requireMember(actor);
    ModelValues.identifier(pageId, 128);
    if (command == null) {
      throw ModelValues.invalid();
    }
    return store.transaction(
        () -> {
          var page = repository.page(actor, pageId).orElseThrow(ModelValues::notFound);
          repository.changeLifecycle(
              actor,
              pageId,
              command.version(),
              command.lifecycleVersion(),
              state,
              Instant.now().toEpochMilli());
          return pageResult(actor, page);
        });
  }

  public WikiPageResult page(Actor actor, String pageId) {
    requireMember(actor);
    ModelValues.identifier(pageId, 128);
    return store.transaction(
        () -> pageResult(actor, repository.page(actor, pageId).orElseThrow(ModelValues::notFound)));
  }

  public WikiPageResult version(Actor actor, String pageId, long version) {
    requireMember(actor);
    pageIdentity(pageId, version);
    return store.transaction(
        () ->
            pageResult(
                actor,
                repository.version(actor, pageId, version).orElseThrow(ModelValues::notFound)));
  }

  public WikiProposalListResult proposals(Actor actor, int offset, int limit, String status) {
    requireMember(actor);
    pagination(offset, limit);
    String selected = status == null ? "pending" : status;
    if (!Set.of("pending", "accepted", "dismissed").contains(selected)) {
      throw ModelValues.invalid();
    }
    return store.transaction(
        () ->
            new WikiProposalListResult(
                repository.proposals(actor, offset, limit, selected).stream()
                    .map(proposal -> proposalResult(actor, proposal))
                    .toList(),
                repository.proposalCount(actor, selected),
                offset,
                limit));
  }

  public WikiProposalResult proposal(Actor actor, String id) {
    requireMember(actor);
    ModelValues.identifier(id, 128);
    return store.transaction(() -> proposalResult(actor, requireProposal(actor, id)));
  }

  public WikiPageResult accept(Actor actor, String id, long baseVersion) {
    requireMember(actor);
    ModelValues.identifier(id, 128);
    if (baseVersion < 0 || baseVersion >= 9_007_199_254_740_991L) {
      throw ModelValues.invalid();
    }
    return store.transaction(
        () -> {
          var proposal = requireProposal(actor, id);
          requirePending(proposal);
          if (proposal.baseVersion() != baseVersion) {
            throw versionConflict();
          }
          requireBase(actor, proposal.pageId(), baseVersion);
          for (var section : proposal.after().sections()) {
            for (var source : section.sources()) {
              requireCurrent(actor, source.publication());
              materials.source(actor, source.publication(), source.reference());
            }
          }
          long now = Instant.now().toEpochMilli();
          var page =
              new WikiPageRevision(
                  proposal.pageId(),
                  baseVersion + 1,
                  proposal.after(),
                  proposal.modelRevision(),
                  proposal.policyRevision(),
                  now);
          repository.saveRevision(actor, page, baseVersion);
          repository.review(actor, id, "accepted", now);
          return pageResult(actor, page);
        });
  }

  public WikiProposalResult dismiss(Actor actor, String id) {
    requireMember(actor);
    ModelValues.identifier(id, 128);
    return store.transaction(
        () -> {
          var proposal = requireProposal(actor, id);
          requirePending(proposal);
          repository.review(actor, id, "dismissed", Instant.now().toEpochMilli());
          return proposalResult(actor, requireProposal(actor, id));
        });
  }

  public SynopsisSourceMaterial source(Actor actor, String pageId, long version, String sourceId) {
    requireMember(actor);
    pageIdentity(pageId, version);
    ModelValues.identifier(sourceId, 128);
    return store.transaction(
        () -> {
          var page = repository.version(actor, pageId, version).orElseThrow(ModelValues::notFound);
          return sourceMaterial(actor, page.content(), sourceId);
        });
  }

  public SynopsisSourceMaterial proposalSource(Actor actor, String proposalId, String sourceId) {
    requireMember(actor);
    ModelValues.identifier(proposalId, 128);
    ModelValues.identifier(sourceId, 128);
    return store.transaction(
        () -> sourceMaterial(actor, requireProposal(actor, proposalId).after(), sourceId));
  }

  private Prepared prepare(Actor actor, WikiProposalCommand command) {
    String pageId = command.pageId() == null ? UUID.randomUUID().toString() : command.pageId();
    WikiContent before = requireBase(actor, pageId, command.baseVersion());
    var inputs = new ArrayList<WikiCompilationInput>();
    for (String documentId : command.documentIds()) {
      var publication = materials.publication(actor, documentId).orElseThrow(ModelValues::notFound);
      inputs.add(
          new WikiCompilationInput(publication, materials.document(actor, publication).evidence()));
    }
    return new Prepared(pageId, before, List.copyOf(inputs));
  }

  private WikiContent requireBase(Actor actor, String pageId, long expectedVersion) {
    var current = repository.page(actor, pageId);
    if (expectedVersion == 0 && current.isEmpty()) {
      return null;
    }
    if (current.isEmpty()) {
      throw ModelValues.notFound();
    }
    if (!"active".equals(repository.lifecycle(actor, pageId).state())) {
      throw new ApplicationException(FailureKind.CONFLICT, "wiki_page_deleted", "知识页已删除，请先恢复后再更新。");
    }
    if (current.get().version() != expectedVersion) {
      throw versionConflict();
    }
    return current.get().content();
  }

  private WikiProposal requireProposal(Actor actor, String id) {
    return repository.proposal(actor, id).orElseThrow(ModelValues::notFound);
  }

  private SynopsisSourceMaterial sourceMaterial(Actor actor, WikiContent content, String sourceId) {
    var source =
        content.sections().stream()
            .flatMap(section -> section.sources().stream())
            .filter(candidate -> candidate.id().equals(sourceId))
            .findFirst()
            .orElseThrow(ModelValues::notFound);
    return materials.source(actor, source.publication(), source.reference());
  }

  private WikiPageResult pageResult(Actor actor, WikiPageRevision page) {
    var content = contentResult(actor, page.content());
    var lifecycle = repository.lifecycle(actor, page.pageId());
    return new WikiPageResult(
        page.pageId(),
        page.version(),
        content,
        page.modelRevision(),
        page.policyRevision(),
        page.createdAt(),
        sourceState(content),
        lifecycle.state(),
        lifecycle.version());
  }

  private WikiProposalResult proposalResult(Actor actor, WikiProposal proposal) {
    var after = contentResult(actor, proposal.after());
    return new WikiProposalResult(
        proposal.id(),
        proposal.pageId(),
        proposal.baseVersion(),
        proposal.before() == null ? null : contentResult(actor, proposal.before()),
        after,
        proposal.generationMethod(),
        proposal.modelRevision(),
        proposal.policyRevision(),
        proposal.status(),
        proposal.createdAt(),
        proposal.reviewedAt(),
        sourceState(after));
  }

  private WikiContentResult contentResult(Actor actor, WikiContent content) {
    var current = new HashMap<PublicationVersion, Boolean>();
    var removed = new HashMap<String, Boolean>();
    return new WikiContentResult(
        content.title(),
        content.kind(),
        content.sections().stream()
            .map(
                section -> {
                  boolean sourceRemoved =
                      section.sources().stream()
                          .anyMatch(
                              source ->
                                  removed.computeIfAbsent(
                                      source.publication().documentId(),
                                      id -> repository.sourceRemoved(actor, id)));
                  return new WikiContentResult.Section(
                      section.id(),
                      sourceRemoved ? "来源资料已清理" : section.heading(),
                      sourceRemoved ? "" : section.body(),
                      section.sources().stream()
                          .map(
                              source -> {
                                var publication = source.publication();
                                var reference = source.reference();
                                boolean available =
                                    current.computeIfAbsent(
                                        publication, value -> isCurrent(actor, value));
                                return new WikiContentResult.Source(
                                    source.id(),
                                    publication.documentId(),
                                    publication.publicationId(),
                                    publication.sourceRevisionId(),
                                    publication.sourceSha256(),
                                    reference.id(),
                                    reference.sha256(),
                                    reference.kind().name().toLowerCase(Locale.ROOT),
                                    reference.time() == null ? null : reference.time().startUs(),
                                    reference.time() == null ? null : reference.time().endUs(),
                                    available);
                              })
                          .toList());
                })
            .toList());
  }

  private static String sourceState(WikiContentResult content) {
    return content.sections().stream()
            .flatMap(section -> section.sources().stream())
            .allMatch(WikiContentResult.Source::current)
        ? "current"
        : "stale";
  }

  private boolean isCurrent(Actor actor, PublicationVersion expected) {
    return materials.publication(actor, expected.documentId()).filter(expected::equals).isPresent();
  }

  private void requireCurrent(Actor actor, PublicationVersion expected) {
    if (!isCurrent(actor, expected)) {
      throw new ApplicationException(
          FailureKind.CONFLICT, "wiki_source_changed", "原资料已更新或撤回，请重新编译提案。");
    }
  }

  private void requireMember(Actor actor) {
    if (actor == null) {
      throw new ApplicationException(
          FailureKind.UNAUTHENTICATED, "authentication_required", "需要有效的组织身份。");
    }
    if (!workspaceId.equals(actor.workspaceId())) {
      throw new ApplicationException(
          FailureKind.FORBIDDEN, "wiki_workspace_forbidden", "不能访问其他组织的知识页。");
    }
  }

  private static void requirePending(WikiProposal proposal) {
    if (!"pending".equals(proposal.status())) {
      throw new ApplicationException(FailureKind.CONFLICT, "wiki_proposal_conflict", "提案已经结束审阅。");
    }
  }

  private static void pagination(int offset, int limit) {
    if (offset < 0 || limit < 1 || limit > 100) {
      throw ModelValues.invalid();
    }
  }

  private static void pageIdentity(String pageId, long version) {
    ModelValues.identifier(pageId, 128);
    if (version < 1 || version > 9_007_199_254_740_991L) {
      throw ModelValues.invalid();
    }
  }

  private static ApplicationException versionConflict() {
    return new ApplicationException(
        FailureKind.CONFLICT, "wiki_version_conflict", "知识页版本已改变，请刷新后审阅。");
  }

  private record Prepared(String pageId, WikiContent before, List<WikiCompilationInput> inputs) {}
}
