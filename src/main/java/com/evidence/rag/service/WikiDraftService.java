package com.evidence.rag.service;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.WikiDraft;
import com.evidence.rag.model.dto.WikiDraftCommand;
import com.evidence.rag.model.dto.WikiDraftListResult;
import com.evidence.rag.model.dto.WikiDraftResult;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.repository.WikiDraftRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** User-authored notes are explicitly unverified, never inserted into source indexes. */
public final class WikiDraftService {
  private final SqliteAuthorityStore store;
  private final WikiDraftRepository repository;
  private final String workspaceId;

  public WikiDraftService(
      SqliteAuthorityStore store, WikiDraftRepository repository, String workspaceId) {
    this.store = Objects.requireNonNull(store);
    this.repository = Objects.requireNonNull(repository);
    this.workspaceId = ModelValues.identifier(workspaceId, 200);
  }

  public WikiDraftListResult list(Actor actor, int offset, int limit) {
    requireMember(actor);
    if (offset < 0 || limit < 1 || limit > 100) {
      throw ModelValues.invalid();
    }
    return store.transaction(
        () ->
            new WikiDraftListResult(
                repository.list(actor, offset, limit).stream()
                    .map(WikiDraftService::result)
                    .toList(),
                repository.count(actor),
                offset,
                limit));
  }

  public WikiDraftResult get(Actor actor, String id) {
    requireMember(actor);
    ModelValues.identifier(id, 128);
    return store.transaction(
        () -> result(repository.find(actor, id).orElseThrow(ModelValues::notFound)));
  }

  public WikiDraftResult create(Actor actor, WikiDraftCommand command) {
    requireMember(actor);
    if (command == null) {
      throw ModelValues.invalid();
    }
    long now = Instant.now().toEpochMilli();
    var draft =
        new WikiDraft(UUID.randomUUID().toString(), command.title(), command.body(), 1, now, now);
    return store.transaction(
        () -> {
          repository.insert(actor, draft);
          return result(draft);
        });
  }

  public WikiDraftResult update(Actor actor, String id, long version, WikiDraftCommand command) {
    requireIdentity(actor, id, version);
    if (command == null) {
      throw ModelValues.invalid();
    }
    return store.transaction(
        () -> {
          var previous = repository.find(actor, id).orElseThrow(ModelValues::notFound);
          var draft =
              new WikiDraft(
                  id,
                  command.title(),
                  command.body(),
                  version + 1,
                  previous.createdAt(),
                  Math.max(previous.updatedAt(), Instant.now().toEpochMilli()));
          repository.update(actor, draft, version);
          return result(draft);
        });
  }

  public void delete(Actor actor, String id, long version) {
    requireIdentity(actor, id, version);
    store.transaction(
        () -> {
          repository.find(actor, id).orElseThrow(ModelValues::notFound);
          repository.delete(actor, id, version);
          return null;
        });
  }

  private void requireIdentity(Actor actor, String id, long version) {
    requireMember(actor);
    ModelValues.identifier(id, 128);
    if (version < 1 || version >= 9_007_199_254_740_991L) {
      throw ModelValues.invalid();
    }
  }

  private void requireMember(Actor actor) {
    if (actor == null || !workspaceId.equals(actor.workspaceId())) {
      throw new ApplicationException(
          FailureKind.FORBIDDEN, "wiki_workspace_forbidden", "不能访问其他组织的草稿。");
    }
  }

  private static WikiDraftResult result(WikiDraft draft) {
    return new WikiDraftResult(
        draft.id(),
        draft.title(),
        draft.body(),
        draft.version(),
        draft.createdAt(),
        draft.updatedAt());
  }
}
