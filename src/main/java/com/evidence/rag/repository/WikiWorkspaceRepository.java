package com.evidence.rag.repository;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.WikiContent;
import com.evidence.rag.model.domain.WikiPageLifecycle;
import com.evidence.rag.model.domain.WikiPageRevision;
import com.evidence.rag.model.domain.WikiProposal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/** Organization-scoped Wiki persistence. Every call participates in the caller's transaction. */
public final class WikiWorkspaceRepository {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String CURRENT_PAGES =
      " FROM wiki_page_revisions p WHERE p.workspace_id=? AND p.version=(SELECT MAX(v.version) FROM wiki_page_revisions v WHERE v.workspace_id=p.workspace_id AND v.page_id=p.page_id) AND COALESCE((SELECT l.state FROM wiki_page_lifecycle l WHERE l.workspace_id=p.workspace_id AND l.page_id=p.page_id ORDER BY l.lifecycle_version DESC LIMIT 1),'active')=? AND (?='' OR instr(lower(json_extract(p.content_json,'$.title')),lower(?))>0)";
  private final SqliteAuthorityStore store;

  public WikiWorkspaceRepository(SqliteAuthorityStore store) {
    this.store = Objects.requireNonNull(store);
  }

  public Optional<WikiPageRevision> page(Actor actor, String pageId) {
    return store
        .rows(
            "SELECT * FROM wiki_page_revisions WHERE workspace_id=? AND page_id=? ORDER BY version DESC LIMIT 1",
            actor.workspaceId(),
            pageId)
        .stream()
        .map(WikiWorkspaceRepository::page)
        .findFirst();
  }

  public Optional<WikiPageRevision> version(Actor actor, String pageId, long version) {
    return store
        .rows(
            "SELECT * FROM wiki_page_revisions WHERE workspace_id=? AND page_id=? AND version=?",
            actor.workspaceId(),
            pageId,
            version)
        .stream()
        .map(WikiWorkspaceRepository::page)
        .findFirst();
  }

  public List<WikiPageRevision> pages(Actor actor, int offset, int limit, String query) {
    return pages(actor, offset, limit, query, "active");
  }

  public List<WikiPageRevision> pages(
      Actor actor, int offset, int limit, String query, String state) {
    pagination(offset, limit);
    String search = query == null ? "" : query;
    return store
        .rows(
            "SELECT p.*" + CURRENT_PAGES + " ORDER BY p.created_at DESC,p.page_id LIMIT ? OFFSET ?",
            actor.workspaceId(),
            state,
            search,
            search,
            limit,
            offset)
        .stream()
        .map(WikiWorkspaceRepository::page)
        .toList();
  }

  public long pageCount(Actor actor, String query) {
    return pageCount(actor, query, "active");
  }

  public long pageCount(Actor actor, String query, String state) {
    String search = query == null ? "" : query;
    return store.count(
        "SELECT COUNT(*)" + CURRENT_PAGES, actor.workspaceId(), state, search, search);
  }

  public WikiPageLifecycle lifecycle(Actor actor, String pageId) {
    return store
        .rows(
            "SELECT state,lifecycle_version FROM wiki_page_lifecycle WHERE workspace_id=? AND page_id=? ORDER BY lifecycle_version DESC LIMIT 1",
            actor.workspaceId(),
            pageId)
        .stream()
        .map(row -> new WikiPageLifecycle(text(row, "state"), number(row, "lifecycle_version")))
        .findFirst()
        .orElse(new WikiPageLifecycle("active", 0));
  }

  public void changeLifecycle(
      Actor actor,
      String pageId,
      long expectedVersion,
      long expectedLifecycleVersion,
      String state,
      long now) {
    String previous = "deleted".equals(state) ? "active" : "deleted";
    store.execute(
        "INSERT INTO wiki_page_lifecycle(workspace_id,page_id,lifecycle_version,state,content_version,actor_id,created_at) SELECT ?,?,?,?,?,?,? WHERE ?=(SELECT MAX(version) FROM wiki_page_revisions WHERE workspace_id=? AND page_id=?) AND ?=COALESCE((SELECT MAX(lifecycle_version) FROM wiki_page_lifecycle WHERE workspace_id=? AND page_id=?),0) AND ?=COALESCE((SELECT state FROM wiki_page_lifecycle WHERE workspace_id=? AND page_id=? ORDER BY lifecycle_version DESC LIMIT 1),'active')",
        actor.workspaceId(),
        pageId,
        expectedLifecycleVersion + 1,
        state,
        expectedVersion,
        actor.principalId(),
        now,
        expectedVersion,
        actor.workspaceId(),
        pageId,
        expectedLifecycleVersion,
        actor.workspaceId(),
        pageId,
        previous,
        actor.workspaceId(),
        pageId);
    if (store.count("SELECT changes()") != 1) {
      throw conflict("wiki_lifecycle_conflict");
    }
  }

  public void insertProposal(Actor actor, WikiProposal proposal) {
    if (!proposal.status().equals("pending")) {
      throw ModelValues.invalid();
    }
    if (proposal(actor, proposal.id()).isPresent()) {
      throw conflict("wiki_proposal_conflict");
    }
    store.execute(
        "INSERT INTO wiki_proposals(workspace_id,id,page_id,base_version,before_json,after_json,generation_method,model_revision,policy_revision,status,created_at,reviewed_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
        actor.workspaceId(),
        proposal.id(),
        proposal.pageId(),
        proposal.baseVersion(),
        proposal.before() == null ? null : JSON.writeValueAsString(proposal.before()),
        JSON.writeValueAsString(proposal.after()),
        proposal.generationMethod(),
        proposal.modelRevision(),
        proposal.policyRevision(),
        proposal.status(),
        proposal.createdAt(),
        proposal.reviewedAt());
  }

  public void purge(
      Actor actor, String pageId, long expectedVersion, long expectedLifecycleVersion, long now) {
    store.wikiPagePurgeScope(
        actor.workspaceId(),
        pageId,
        () -> {
          store.execute(
              "INSERT INTO wiki_page_purges(workspace_id,page_id,content_version,lifecycle_version,actor_id,created_at) SELECT ?,?,?,?,?,? WHERE ?=(SELECT MAX(version) FROM wiki_page_revisions WHERE workspace_id=? AND page_id=?) AND ?=(SELECT MAX(lifecycle_version) FROM wiki_page_lifecycle WHERE workspace_id=? AND page_id=?) AND 'deleted'=(SELECT state FROM wiki_page_lifecycle WHERE workspace_id=? AND page_id=? ORDER BY lifecycle_version DESC LIMIT 1)",
              actor.workspaceId(),
              pageId,
              expectedVersion,
              expectedLifecycleVersion,
              actor.principalId(),
              now,
              expectedVersion,
              actor.workspaceId(),
              pageId,
              expectedLifecycleVersion,
              actor.workspaceId(),
              pageId,
              actor.workspaceId(),
              pageId);
          if (store.count("SELECT changes()") != 1) {
            throw conflict("wiki_lifecycle_conflict");
          }
          for (String table :
              List.of("wiki_page_lifecycle", "wiki_proposals", "wiki_page_revisions")) {
            store.execute(
                "DELETE FROM " + table + " WHERE workspace_id=? AND page_id=?",
                actor.workspaceId(),
                pageId);
          }
          return null;
        });
  }

  public boolean sourceRemoved(Actor actor, String documentId) {
    return store.count(
            "SELECT COUNT(*) FROM document_tombstones WHERE workspace_id=? AND document_id=?",
            actor.workspaceId(),
            documentId)
        != 0;
  }

  public Optional<WikiProposal> proposal(Actor actor, String proposalId) {
    return store
        .rows(
            "SELECT * FROM wiki_proposals WHERE workspace_id=? AND id=?",
            actor.workspaceId(),
            proposalId)
        .stream()
        .map(WikiWorkspaceRepository::proposal)
        .findFirst();
  }

  public List<WikiProposal> proposals(Actor actor, int offset, int limit, String status) {
    pagination(offset, limit);
    String selected = status(status);
    return store
        .rows(
            "SELECT * FROM wiki_proposals WHERE workspace_id=? AND (?='' OR status=?) ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
            actor.workspaceId(),
            selected,
            selected,
            limit,
            offset)
        .stream()
        .map(WikiWorkspaceRepository::proposal)
        .toList();
  }

  public long proposalCount(Actor actor, String status) {
    String selected = status(status);
    return store.count(
        "SELECT COUNT(*) FROM wiki_proposals WHERE workspace_id=? AND (?='' OR status=?)",
        actor.workspaceId(),
        selected,
        selected);
  }

  public void saveRevision(Actor actor, WikiPageRevision revision, long expectedVersion) {
    if (expectedVersion < 0
        || expectedVersion == Long.MAX_VALUE
        || revision.version() != expectedVersion + 1) {
      throw ModelValues.invalid();
    }
    store.execute(
        "INSERT INTO wiki_page_revisions(workspace_id,page_id,version,content_json,model_revision,policy_revision,created_at) SELECT ?,?,?,?,?,?,? WHERE ?=COALESCE((SELECT MAX(version) FROM wiki_page_revisions WHERE workspace_id=? AND page_id=?),0)",
        actor.workspaceId(),
        revision.pageId(),
        revision.version(),
        JSON.writeValueAsString(revision.content()),
        revision.modelRevision(),
        revision.policyRevision(),
        revision.createdAt(),
        expectedVersion,
        actor.workspaceId(),
        revision.pageId());
    if (store.count("SELECT changes()") != 1) {
      throw conflict("wiki_version_conflict");
    }
  }

  public void review(Actor actor, String proposalId, String status, long reviewedAt) {
    if (status == null || !Set.of("accepted", "dismissed").contains(status)) {
      throw ModelValues.invalid();
    }
    var current = proposal(actor, proposalId).orElseThrow(ModelValues::notFound);
    if (reviewedAt < current.createdAt()) {
      throw ModelValues.invalid();
    }
    store.execute(
        "UPDATE wiki_proposals SET status=?,reviewed_at=? WHERE workspace_id=? AND id=? AND status='pending'",
        status,
        reviewedAt,
        actor.workspaceId(),
        proposalId);
    if (store.count("SELECT changes()") != 1) {
      throw conflict("wiki_proposal_conflict");
    }
  }

  private static WikiPageRevision page(Map<String, Object> row) {
    return new WikiPageRevision(
        text(row, "page_id"),
        number(row, "version"),
        content(row, "content_json"),
        text(row, "model_revision"),
        text(row, "policy_revision"),
        number(row, "created_at"));
  }

  private static WikiProposal proposal(Map<String, Object> row) {
    return new WikiProposal(
        text(row, "id"),
        text(row, "page_id"),
        number(row, "base_version"),
        row.get("before_json") == null ? null : content(row, "before_json"),
        content(row, "after_json"),
        text(row, "generation_method"),
        text(row, "model_revision"),
        text(row, "policy_revision"),
        text(row, "status"),
        number(row, "created_at"),
        row.get("reviewed_at") == null ? null : number(row, "reviewed_at"));
  }

  private static WikiContent content(Map<String, Object> row, String column) {
    return JSON.readValue(text(row, column), WikiContent.class);
  }

  private static String text(Map<String, Object> row, String column) {
    return AuthorityRows.text(row, column);
  }

  private static long number(Map<String, Object> row, String column) {
    return ((Number) row.get(column)).longValue();
  }

  private static void pagination(int offset, int limit) {
    if (offset < 0 || limit < 1 || limit > 100) {
      throw ModelValues.invalid();
    }
  }

  private static String status(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    if (!Set.of("pending", "accepted", "dismissed").contains(value)) {
      throw ModelValues.invalid();
    }
    return value;
  }

  private static ApplicationException conflict(String code) {
    return new ApplicationException(FailureKind.CONFLICT, code, "知识页或提案状态已改变，请刷新后重试。");
  }
}
