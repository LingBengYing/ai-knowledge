package com.evidence.rag.service;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.dto.WikiMaintenanceResult;
import com.evidence.rag.model.dto.WikiSourceChangesResult;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.repository.SynopsisMaterialRepository;
import com.evidence.rag.repository.WikiMaintenanceRepository;
import com.evidence.rag.repository.WikiWorkspaceRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Set;

/** Read-only source impact and maintenance rules. Generation still uses reviewed Wiki proposals. */
public final class WikiMaintenanceService {
  private final SqliteAuthorityStore store;
  private final WikiMaintenanceRepository repository;
  private final WikiWorkspaceRepository wiki;
  private final SynopsisMaterialRepository materials;
  private final String workspaceId;

  public WikiMaintenanceService(
      SqliteAuthorityStore store,
      WikiMaintenanceRepository repository,
      WikiWorkspaceRepository wiki,
      SynopsisMaterialRepository materials,
      String workspaceId) {
    this.store = store;
    this.repository = repository;
    this.wiki = wiki;
    this.materials = materials;
    this.workspaceId = workspaceId;
  }

  public WikiMaintenanceResult list(Actor actor, int offset, int limit, String kind) {
    member(actor);
    String selected = kind == null ? "all" : kind;
    if (offset < 0
        || limit < 1
        || limit > 100
        || !Set.of(
                "all",
                "parse_failed",
                "index_failed",
                "empty_text",
                "duplicate_source",
                "stale_knowledge")
            .contains(selected)) {
      throw ModelValues.invalid();
    }
    return store.transaction(
        () -> {
          var snapshot = repository.list(actor, offset, limit, selected);
          long total =
              "all".equals(selected)
                  ? snapshot.counts().values().stream().mapToLong(Long::longValue).sum()
                  : snapshot.counts().get(selected);
          return new WikiMaintenanceResult(
              snapshot.observations().stream()
                  .map(
                      item ->
                          new WikiMaintenanceResult.Item(
                              item.kind() + ":" + item.targetId(),
                              item.kind(),
                              item.targetType(),
                              item.targetId(),
                              item.title(),
                              item.revisionId(),
                              item.errorCode(),
                              item.relatedDocumentId()))
                  .toList(),
              total,
              offset,
              limit,
              snapshot.counts());
        });
  }

  public WikiSourceChangesResult sourceChanges(Actor actor, String pageId) {
    member(actor);
    ModelValues.identifier(pageId, 128);
    return store.transaction(
        () -> {
          var page = wiki.page(actor, pageId).orElseThrow(ModelValues::notFound);
          var originals = new LinkedHashMap<String, PublicationVersion>();
          page.content()
              .sections()
              .forEach(
                  section ->
                      section
                          .sources()
                          .forEach(
                              source ->
                                  originals.putIfAbsent(
                                      source.publication().documentId(), source.publication())));
          var sources = new ArrayList<WikiSourceChangesResult.Source>();
          for (var previous : originals.values()) {
            var current = materials.publication(actor, previous.documentId());
            String status =
                current.isEmpty()
                    ? "unavailable"
                    : previous.equals(current.get()) ? "current" : "updated";
            sources.add(
                new WikiSourceChangesResult.Source(
                    previous.documentId(),
                    repository.displayName(actor, previous.documentId()),
                    status,
                    preview(actor, previous),
                    current.map(value -> preview(actor, value)).orElse(null)));
          }
          boolean canRefresh =
              "active".equals(wiki.lifecycle(actor, pageId).state())
                  && sources.stream().anyMatch(source -> "updated".equals(source.status()))
                  && sources.stream().noneMatch(source -> "unavailable".equals(source.status()));
          return new WikiSourceChangesResult(
              pageId, page.version(), page.content().title(), canRefresh, sources);
        });
  }

  private WikiSourceChangesResult.Version preview(Actor actor, PublicationVersion publication) {
    var preview = repository.preview(actor, publication);
    return new WikiSourceChangesResult.Version(
        publication.publicationId(),
        publication.sourceRevisionId(),
        publication.sourceSha256(),
        preview.characters(),
        preview.parts(),
        preview.excerpt(),
        preview.truncated());
  }

  private void member(Actor actor) {
    if (actor == null) {
      throw new ApplicationException(
          FailureKind.UNAUTHENTICATED, "authentication_required", "需要有效的组织身份。");
    }
    if (!workspaceId.equals(actor.workspaceId())) {
      throw new ApplicationException(
          FailureKind.FORBIDDEN, "wiki_workspace_forbidden", "不能访问其他组织的知识页。");
    }
  }
}
