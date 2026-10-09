package com.evidence.rag.repository;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.WikiDraft;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Organization-scoped draft persistence; caller holds the Store transaction. */
public final class WikiDraftRepository {
  private final SqliteAuthorityStore store;

  public WikiDraftRepository(SqliteAuthorityStore store) {
    this.store = Objects.requireNonNull(store);
  }

  public List<WikiDraft> list(Actor actor, int offset, int limit) {
    return store
        .rows(
            "SELECT * FROM wiki_drafts WHERE workspace_id=? ORDER BY updated_at DESC,id LIMIT ? OFFSET ?",
            actor.workspaceId(),
            limit,
            offset)
        .stream()
        .map(WikiDraftRepository::draft)
        .toList();
  }

  public long count(Actor actor) {
    return store.count(
        "SELECT COUNT(*) FROM wiki_drafts WHERE workspace_id=?", actor.workspaceId());
  }

  public Optional<WikiDraft> find(Actor actor, String id) {
    return store
        .rows("SELECT * FROM wiki_drafts WHERE workspace_id=? AND id=?", actor.workspaceId(), id)
        .stream()
        .map(WikiDraftRepository::draft)
        .findFirst();
  }

  public void insert(Actor actor, WikiDraft draft) {
    store.execute(
        "INSERT INTO wiki_drafts(workspace_id,id,title,body,version,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
        actor.workspaceId(),
        draft.id(),
        draft.title(),
        draft.body(),
        draft.version(),
        draft.createdAt(),
        draft.updatedAt());
  }

  public void update(Actor actor, WikiDraft draft, long expectedVersion) {
    store.execute(
        "UPDATE wiki_drafts SET title=?,body=?,version=?,updated_at=? WHERE workspace_id=? AND id=? AND version=?",
        draft.title(),
        draft.body(),
        draft.version(),
        draft.updatedAt(),
        actor.workspaceId(),
        draft.id(),
        expectedVersion);
    requireChanged();
  }

  public void delete(Actor actor, String id, long expectedVersion) {
    store.execute(
        "DELETE FROM wiki_drafts WHERE workspace_id=? AND id=? AND version=?",
        actor.workspaceId(),
        id,
        expectedVersion);
    requireChanged();
  }

  private void requireChanged() {
    if (store.count("SELECT changes()") != 1) {
      throw new ApplicationException(
          FailureKind.CONFLICT, "wiki_draft_version_conflict", "草稿已被更新，请刷新后再操作。");
    }
  }

  private static WikiDraft draft(Map<String, Object> row) {
    return new WikiDraft(
        AuthorityRows.text(row, "id"),
        AuthorityRows.text(row, "title"),
        AuthorityRows.text(row, "body"),
        AuthorityRows.number(row, "version"),
        AuthorityRows.number(row, "created_at"),
        AuthorityRows.number(row, "updated_at"));
  }
}
