package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.WikiPageRevision;
import com.evidence.rag.model.domain.WikiProposal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WikiPagePurgeRepositoryTest {
  private static final Actor OWNER = new Actor("org", "owner");
  @TempDir Path directory;

  @Test
  void purgeRemovesOnlySelectedPageAndProposalsAndRetainsContentFreeAuditAcrossRestart() {
    try (var store = new SqliteAuthorityStore(directory)) {
      var repository = new WikiWorkspaceRepository(store);
      store.transaction(
          () -> {
            seed(repository, OWNER, "page");
            seed(repository, OWNER, "other");
            seed(repository, new Actor("other-org", "member"), "page");
            repository.changeLifecycle(OWNER, "page", 2, 0, "deleted", 20);
            repository.purge(OWNER, "page", 2, 1, 21);
            assertTrue(repository.page(OWNER, "page").isEmpty());
            assertTrue(repository.version(OWNER, "page", 1).isEmpty());
            assertEquals(
                0,
                repository.proposals(OWNER, 0, 20, null).stream()
                    .filter(value -> value.pageId().equals("page"))
                    .count());
            assertEquals(2, repository.page(OWNER, "other").orElseThrow().version());
            assertEquals(
                2,
                repository.page(new Actor("other-org", "member"), "page").orElseThrow().version());
            assertEquals(
                List.of(
                    "workspace_id",
                    "page_id",
                    "content_version",
                    "lifecycle_version",
                    "actor_id",
                    "created_at"),
                store.rows("PRAGMA table_info(wiki_page_purges)").stream()
                    .map(row -> row.get("name"))
                    .toList());
            assertEquals(
                1,
                store.count(
                    "SELECT COUNT(*) FROM wiki_page_purges WHERE workspace_id='org' AND page_id='page' AND content_version=2 AND lifecycle_version=1 AND actor_id='owner' AND created_at=21"));
            assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
            return null;
          });
      for (String sql :
          List.of(
              "DELETE FROM wiki_page_revisions",
              "DELETE FROM wiki_proposals",
              "UPDATE wiki_page_purges SET actor_id='changed'",
              "DELETE FROM wiki_page_purges")) {
        assertThrows(
            ApplicationException.class,
            () ->
                store.transaction(
                    () -> {
                      store.execute(sql);
                      return null;
                    }));
      }
      assertThrows(
          ApplicationException.class,
          () ->
              store.transaction(
                  () -> {
                    seed(repository, OWNER, "page");
                    return null;
                  }));
    }
    try (var store = new SqliteAuthorityStore(directory)) {
      var repository = new WikiWorkspaceRepository(store);
      store.transaction(
          () -> {
            assertTrue(repository.page(OWNER, "page").isEmpty());
            assertEquals(1, store.count("SELECT COUNT(*) FROM wiki_page_purges"));
            assertEquals(2, repository.page(OWNER, "other").orElseThrow().version());
            return null;
          });
    }
  }

  @Test
  void staleOrActivePurgeAndFailureRollbackDoNotDestroyHistoryOrLeakAuthorization() {
    try (var store = new SqliteAuthorityStore(directory)) {
      var repository = new WikiWorkspaceRepository(store);
      store.transaction(
          () -> {
            seed(repository, OWNER, "page");
            return null;
          });
      assertThrows(
          ApplicationException.class,
          () ->
              store.transaction(
                  () -> {
                    repository.purge(OWNER, "page", 2, 0, 20);
                    return null;
                  }));
      store.transaction(
          () -> {
            repository.changeLifecycle(OWNER, "page", 2, 0, "deleted", 20);
            return null;
          });
      assertThrows(
          ApplicationException.class,
          () ->
              store.transaction(
                  () -> {
                    repository.purge(OWNER, "page", 1, 1, 21);
                    return null;
                  }));
      assertThrows(
          ApplicationException.class,
          () ->
              store.transaction(
                  () -> {
                    repository.purge(OWNER, "page", 2, 1, 21);
                    throw new IllegalStateException("synthetic transaction failure");
                  }));
      store.transaction(
          () -> {
            assertEquals(2, repository.page(OWNER, "page").orElseThrow().version());
            assertEquals("deleted", repository.lifecycle(OWNER, "page").state());
            assertEquals(0, store.count("SELECT COUNT(*) FROM wiki_page_purges"));
            return null;
          });
      assertThrows(
          ApplicationException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute("DELETE FROM wiki_page_lifecycle");
                    return null;
                  }));
      assertThrows(
          ApplicationException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute(
                        "INSERT INTO wiki_page_purges VALUES('org','page',2,1,'owner',21)");
                    return null;
                  }));
    }
  }

  private void seed(WikiWorkspaceRepository repository, Actor actor, String pageId) {
    var content = WikiWorkspaceRepositoryTest.content("合成知识页", "不可保留的派生正文");
    repository.insertProposal(
        actor,
        new WikiProposal(
            pageId + "-accepted",
            pageId,
            0,
            null,
            content,
            "extractive",
            "extractive-v1",
            "wiki-v1",
            "pending",
            1,
            null));
    repository.saveRevision(
        actor, new WikiPageRevision(pageId, 1, content, "extractive-v1", "wiki-v1", 2), 0);
    repository.review(actor, pageId + "-accepted", "accepted", 2);
    repository.saveRevision(
        actor, new WikiPageRevision(pageId, 2, content, "extractive-v1", "wiki-v1", 3), 1);
    for (String status : List.of("pending", "dismissed")) {
      String id = pageId + "-" + status;
      repository.insertProposal(
          actor,
          new WikiProposal(
              id,
              pageId,
              2,
              content,
              content,
              "extractive",
              "extractive-v1",
              "wiki-v1",
              "pending",
              4,
              null));
      if (status.equals("dismissed")) {
        repository.review(actor, id, status, 5);
      }
    }
  }
}
