package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.FileSynopsis;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.SynopsisEvidence;
import com.evidence.rag.model.domain.WikiContent;
import com.evidence.rag.model.domain.WikiPageRevision;
import com.evidence.rag.model.domain.WikiProposal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WikiWorkspaceRepositoryTest {
  private static final Actor OWNER = new Actor("org", "owner");
  private static final Actor MEMBER = new Actor("org", "member");
  private static final Actor OUTSIDER = new Actor("other", "owner");
  @TempDir Path directory;

  @Test
  void proposalRevisionAndCompleteBindingsSurviveRestartAndShareOnlyWithinOrganization() {
    var content = content("灯塔", "原文第一行\n原文第二行");
    var proposal = proposal("proposal", "page", content);
    var revision = new WikiPageRevision("page", 1, content, "extractive-v1", "wiki-v1", 11);
    try (var store = new SqliteAuthorityStore(directory)) {
      var repository = new WikiWorkspaceRepository(store);
      assertThrows(IllegalStateException.class, () -> repository.page(OWNER, "page"));
      store.transaction(
          () -> {
            repository.insertProposal(OWNER, proposal);
            assertEquals(proposal, repository.proposal(MEMBER, "proposal").orElseThrow());
            assertTrue(repository.proposal(OUTSIDER, "proposal").isEmpty());
            repository.saveRevision(OWNER, revision, 0);
            repository.review(MEMBER, "proposal", "accepted", 12);
            assertEquals(revision, repository.page(MEMBER, "page").orElseThrow());
            assertEquals(1, repository.pageCount(MEMBER, "灯"));
            assertEquals(0, repository.pageCount(OUTSIDER, ""));
            assertTrue(repository.version(OUTSIDER, "page", 1).isEmpty());
            return null;
          });
    }
    try (var store = new SqliteAuthorityStore(directory)) {
      var repository = new WikiWorkspaceRepository(store);
      store.transaction(
          () -> {
            assertEquals(revision, repository.version(MEMBER, "page", 1).orElseThrow());
            var accepted = repository.proposal(OWNER, "proposal").orElseThrow();
            assertEquals("accepted", accepted.status());
            assertEquals(12L, accepted.reviewedAt());
            assertEquals(proposal.after(), accepted.after());
            assertEquals(0, repository.proposalCount(OWNER, "pending"));
            assertEquals(1, repository.proposalCount(MEMBER, "accepted"));
            assertEquals(0, repository.proposalCount(OUTSIDER, "accepted"));
            return null;
          });
    }
  }

  @Test
  void conflictsRollbackNewRevisionAndKeepImmutableHistory() {
    try (var store = new SqliteAuthorityStore(directory)) {
      var repository = new WikiWorkspaceRepository(store);
      var original = content("灯塔", "原文");
      store.transaction(
          () -> {
            repository.insertProposal(OWNER, proposal("first", "page", original));
            repository.saveRevision(
                OWNER, new WikiPageRevision("page", 1, original, "model-v1", "wiki-v1", 10), 0);
            repository.review(OWNER, "first", "accepted", 11);
            return null;
          });
      var conflict =
          assertThrows(
              ApplicationException.class,
              () ->
                  store.transaction(
                      () -> {
                        repository.saveRevision(
                            OWNER,
                            new WikiPageRevision(
                                "page", 2, content("更新", "新原文"), "model-v1", "wiki-v1", 12),
                            1);
                        repository.review(OWNER, "first", "dismissed", 12);
                        return null;
                      }));
      assertEquals("wiki_proposal_conflict", conflict.code());
      var stale =
          assertThrows(
              ApplicationException.class,
              () ->
                  store.transaction(
                      () -> {
                        repository.saveRevision(
                            OWNER,
                            new WikiPageRevision("page", 1, original, "model-v1", "wiki-v1", 12),
                            0);
                        return null;
                      }));
      assertEquals("wiki_version_conflict", stale.code());
      store.transaction(
          () -> {
            assertEquals(1, repository.page(OWNER, "page").orElseThrow().version());
            assertFalse(repository.version(OWNER, "page", 2).isPresent());
            return null;
          });
      for (String sql :
          List.of(
              "UPDATE wiki_page_revisions SET content_json='{}'",
              "DELETE FROM wiki_page_revisions",
              "DELETE FROM wiki_proposals",
              "UPDATE wiki_proposals SET after_json='{}'")) {
        assertThrows(
            ApplicationException.class,
            () ->
                store.transaction(
                    () -> {
                      store.execute(sql);
                      return null;
                    }));
      }
    }
  }

  @Test
  void paginationCountsCurrentPagesAndKeepsLiteralQueriesAndDifferentFileIdentity() {
    try (var store = new SqliteAuthorityStore(directory)) {
      var repository = new WikiWorkspaceRepository(store);
      store.transaction(
          () -> {
            for (int index = 1; index <= 3; index++) {
              var body = content(index == 3 ? "100%_匹配" : "主题" + index, "相同原文");
              repository.saveRevision(
                  OWNER,
                  new WikiPageRevision("page-" + index, 1, body, "model-v1", "wiki-v1", index),
                  0);
              repository.insertProposal(
                  OWNER, proposal("proposal-" + index, "new-page-" + index, body));
            }
            repository.saveRevision(
                OWNER,
                new WikiPageRevision("page-1", 2, content("新主题", "更新"), "model-v1", "wiki-v1", 5),
                1);
            assertEquals(3, repository.pageCount(OWNER, ""));
            assertEquals(
                List.of("page-3"),
                repository.pages(OWNER, 1, 1, "").stream().map(WikiPageRevision::pageId).toList());
            assertEquals(1, repository.pageCount(OWNER, "%_"));
            assertEquals(0, repository.pageCount(OWNER, "%' OR 1=1 --"));
            assertEquals(2, repository.page(OWNER, "page-1").orElseThrow().version());
            assertEquals(1, repository.version(OWNER, "page-1", 1).orElseThrow().version());
            assertEquals(1, repository.proposals(OWNER, 1, 1, "pending").size());
            repository.review(OWNER, "proposal-1", "dismissed", 12);
            assertEquals(2, repository.proposalCount(OWNER, "pending"));
            assertEquals(3, repository.proposalCount(OWNER, null));
            assertTrue(repository.proposals(OUTSIDER, 0, 20, null).isEmpty());
            return null;
          });
    }
  }

  static WikiContent content(String title, String text) {
    var target = new IndexTarget("embedding", "projection", "model-v1", 2);
    var publication =
        new PublicationVersion(
            "document",
            "publication",
            "revision",
            "generation",
            "a".repeat(64),
            "parser-v1",
            target,
            "b".repeat(64),
            1);
    var reference =
        new FileSynopsis.Reference("evidence", "c".repeat(64), SynopsisEvidence.Kind.TEXT, null);
    return new WikiContent(
        title,
        "topic",
        List.of(
            new WikiContent.Section(
                "section-1",
                "概览",
                text,
                List.of(new WikiContent.Source("source-1", publication, reference)))));
  }

  private static WikiProposal proposal(String id, String pageId, WikiContent content) {
    return new WikiProposal(
        id,
        pageId,
        0,
        null,
        content,
        "extractive",
        "extractive-v1",
        "wiki-v1",
        "pending",
        10,
        null);
  }
}
