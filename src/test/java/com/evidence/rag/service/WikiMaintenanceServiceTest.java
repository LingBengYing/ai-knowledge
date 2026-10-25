package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.IndexClaim;
import com.evidence.rag.model.domain.ParsedText;
import com.evidence.rag.model.domain.TextPage;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.model.dto.WikiPageLifecycleCommand;
import com.evidence.rag.model.dto.WikiProposalCommand;
import com.evidence.rag.model.dto.WikiRefreshCommand;
import com.evidence.rag.model.dto.WikiSourceVersion;
import com.evidence.rag.repository.DocumentUpdateRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.repository.SynopsisMaterialRepository;
import com.evidence.rag.repository.WikiMaintenanceRepository;
import com.evidence.rag.repository.WikiWorkspaceRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.PublishedCorpusFixture;
import com.evidence.rag.tool.parser.TextParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WikiMaintenanceServiceTest {
  @TempDir Path directory;
  private final Actor owner = new Actor("org-main", "owner");

  @Test
  void changedSourcesHaveBoundedPreviewsAndRefreshPreservesUnaffectedSectionsUntilReview() {
    String pageId;
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var changed = fixture.publish(owner, "旧规则：申请需要两个工作日。");
      var stable = fixture.publish(owner, "稳定规则：窗口开放时间为九点。");
      var wiki = wiki(fixture.authority.store());
      var initial =
          wiki.createProposal(
              owner,
              new WikiProposalCommand(
                  null,
                  0,
                  "规则页",
                  "topic",
                  List.of(changed.documentId(), stable.documentId()),
                  "extractive"));
      var page = wiki.accept(owner, initial.id(), 0);
      pageId = page.pageId();
      var unchanged = page.content().sections().get(1);
      assertEquals(
          0, maintenance(fixture.authority.store()).list(owner, 0, 20, "stale_knowledge").total());

      replace(
          fixture, changed.documentId(), changed.revisionId(), "新规则：申请当天完成。" + "说明".repeat(250));
      var changes = maintenance(fixture.authority.store()).sourceChanges(owner, pageId);
      assertTrue(changes.canRefresh());
      assertEquals("updated", changes.sources().getFirst().status());
      assertEquals("旧规则：申请需要两个工作日。", changes.sources().getFirst().before().excerpt());
      assertTrue(changes.sources().getFirst().after().excerpt().startsWith("新规则"));
      assertTrue(changes.sources().getFirst().after().truncated());
      assertEquals("current", changes.sources().getLast().status());
      assertEquals(
          1, maintenance(fixture.authority.store()).list(owner, 0, 20, "stale_knowledge").total());

      var versions =
          changes.sources().stream()
              .map(
                  source ->
                      new WikiSourceVersion(source.documentId(), source.after().publicationId()))
              .toList();
      var proposal =
          wiki.refreshProposal(owner, pageId, new WikiRefreshCommand(1, "extractive", versions));
      assertEquals("pending", proposal.status());
      assertEquals(1, wiki.page(owner, pageId).version());
      assertEquals(unchanged, proposal.after().sections().getLast());
      assertTrue(proposal.after().sections().getFirst().body().startsWith("新规则"));
      assertEquals(2, wiki.accept(owner, proposal.id(), 1).version());
      assertEquals(
          0, maintenance(fixture.authority.store()).list(owner, 0, 20, "stale_knowledge").total());
      assertTrue(
          wiki.version(owner, pageId, 1).content().sections().getFirst().body().startsWith("旧规则"));
      assertFalse(maintenance(fixture.authority.store()).sourceChanges(owner, pageId).canRefresh());
      assertEquals(
          "wiki_sources_current",
          assertThrows(
                  ApplicationException.class,
                  () ->
                      wiki.refreshProposal(
                          owner, pageId, new WikiRefreshCommand(2, "extractive", versions)))
              .code());
    }
    try (var fixture = new PublishedCorpusFixture(directory)) {
      assertEquals(2, wiki(fixture.authority.store()).page(owner, pageId).version());
      assertEquals(
          0, maintenance(fixture.authority.store()).list(owner, 0, 20, "stale_knowledge").total());
    }
  }

  @Test
  void selectionVersionAndLaterSourceChangeRejectProposalWithoutPublishing() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var document = fixture.publish(owner, "初版原文。");
      var wiki = wiki(fixture.authority.store());
      var proposal =
          wiki.createProposal(
              owner,
              new WikiProposalCommand(
                  null, 0, "知识", "topic", List.of(document.documentId()), "extractive"));
      var page = wiki.accept(owner, proposal.id(), 0);
      var old =
          maintenance(fixture.authority.store())
              .sourceChanges(owner, page.pageId())
              .sources()
              .getFirst();
      var obsolete = List.of(new WikiSourceVersion(old.documentId(), old.after().publicationId()));
      replace(fixture, document.documentId(), document.revisionId(), "更新原文。");
      assertEquals(
          "wiki_source_changed",
          assertThrows(
                  ApplicationException.class,
                  () ->
                      wiki.createProposal(
                          owner,
                          new WikiProposalCommand(
                              null,
                              0,
                              "旧选择",
                              "topic",
                              List.of(document.documentId()),
                              "extractive",
                              obsolete)))
              .code());
      assertEquals(
          "wiki_source_changed",
          assertThrows(
                  ApplicationException.class,
                  () ->
                      wiki.refreshProposal(
                          owner, page.pageId(), new WikiRefreshCommand(1, "extractive", obsolete)))
              .code());
      var current =
          maintenance(fixture.authority.store())
              .sourceChanges(owner, page.pageId())
              .sources()
              .getFirst();
      var pending =
          wiki.refreshProposal(
              owner,
              page.pageId(),
              new WikiRefreshCommand(
                  1,
                  "extractive",
                  List.of(
                      new WikiSourceVersion(
                          current.documentId(), current.after().publicationId()))));
      replace(fixture, document.documentId(), current.after().revisionId(), "再次更新原文。");
      assertEquals(
          "wiki_source_changed",
          assertThrows(ApplicationException.class, () -> wiki.accept(owner, pending.id(), 1))
              .code());
      assertEquals("pending", wiki.proposal(owner, pending.id()).status());
      assertEquals(1, wiki.page(owner, page.pageId()).version());
    }
  }

  @Test
  void removedSourceIsVisibleAsUnavailableWithoutLeakingItsPreviewOrAllowingRefresh()
      throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var claim = fixture.publish(owner, "必须清理的旧正文。");
      var wiki = wiki(fixture.authority.store());
      var proposal =
          wiki.createProposal(
              owner,
              new WikiProposalCommand(
                  null, 0, "保留知识", "topic", List.of(claim.documentId()), "extractive"));
      var page = wiki.accept(owner, proposal.id(), 0);
      try (var connection =
              DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
          var statement =
              connection.prepareStatement("INSERT INTO document_tombstones VALUES(?,?,?,?)")) {
        statement.setString(1, claim.documentId());
        statement.setString(2, owner.workspaceId());
        statement.setString(3, owner.principalId());
        statement.setString(4, "2026-10-25T00:00:00Z");
        statement.executeUpdate();
      }
      var changes = maintenance(fixture.authority.store()).sourceChanges(owner, page.pageId());
      assertFalse(changes.canRefresh());
      assertEquals("unavailable", changes.sources().getFirst().status());
      assertEquals("", changes.sources().getFirst().before().excerpt());
      assertNull(changes.sources().getFirst().after());
      assertEquals(
          1, maintenance(fixture.authority.store()).list(owner, 0, 20, "stale_knowledge").total());
      wiki.delete(owner, page.pageId(), new WikiPageLifecycleCommand(1, 0));
      assertEquals(
          0, maintenance(fixture.authority.store()).list(owner, 0, 20, "stale_knowledge").total());
    }
  }

  @Test
  void qualityRulesCountAcrossPagesAndKeepOrganizationAndUnparsedDocumentsSeparate() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      fixture.publish(owner, "完全一致的原文件。");
      fixture.publish(owner, "完全一致的原文件。");
      fixture.publish(new Actor("other-org", "owner"), "完全一致的原文件。");
      fixture
          .authority
          .ingestion()
          .uploadDocument(
              owner, "failed.txt", "text/plain", "失败示例".getBytes(StandardCharsets.UTF_8));
      var failed = fixture.authority.ingestion().claimIngestion(owner.workspaceId()).orElseThrow();
      assertTrue(fixture.authority.ingestion().failIngestion(failed, "parser_failed"));
      fixture
          .authority
          .ingestion()
          .uploadDocument(owner, "empty.txt", "text/plain", " ".getBytes(StandardCharsets.UTF_8));
      var empty = fixture.authority.ingestion().claimIngestion(owner.workspaceId()).orElseThrow();
      assertThrows(
          ApplicationException.class,
          () ->
              fixture
                  .authority
                  .ingestion()
                  .completeIngestion(
                      empty, new ParsedText(List.of(new TextPage(1, " ")), List.of())));
      fixture
          .authority
          .ingestion()
          .uploadDocument(
              owner, "pending.txt", "text/plain", "尚未处理".getBytes(StandardCharsets.UTF_8));
      var service = maintenance(fixture.authority.store());
      var first = service.list(owner, 0, 1, "all");
      assertEquals(2, first.total());
      assertEquals(1, first.items().size());
      assertEquals(1L, first.counts().get("parse_failed"));
      assertEquals(0L, first.counts().get("empty_text"));
      assertEquals(1L, first.counts().get("duplicate_source"));
      assertNotEquals(
          first.items().getFirst().id(), service.list(owner, 1, 1, "all").items().getFirst().id());
      var duplicates = service.list(owner, 0, 20, "duplicate_source");
      assertNotNull(duplicates.items().getFirst().relatedDocumentId());
      assertThrows(ApplicationException.class, () -> service.list(null, 0, 20, "all"));
      assertThrows(
          ApplicationException.class,
          () -> service.list(new Actor("other-org", "owner"), 0, 20, "all"));
      assertThrows(
          ApplicationException.class, () -> service.list(owner, 0, 20, "semantic_conflict"));
      assertThrows(ApplicationException.class, () -> service.list(owner, 0, 101, "all"));
    }
  }

  private void replace(PublishedCorpusFixture fixture, String id, String revision, String text) {
    var service =
        new DocumentReplacementService(
            fixture.authority.store(),
            new DocumentUpdateRepository(fixture.authority.store()),
            new ManagementRepository(fixture.authority.store()),
            new DocumentPermissionPolicy(),
            fixture.authority.ingestion(),
            () -> null,
            null,
            null,
            true);
    service.setAutomaticIndexingEnabled(false);
    var uploaded =
        service.upload(
            owner, id, revision, "new.txt", "text/plain", text.getBytes(StandardCharsets.UTF_8));
    var parsed = fixture.authority.ingestion().claimIngestion(owner.workspaceId()).orElseThrow();
    assertTrue(
        fixture
            .authority
            .ingestion()
            .completeIngestion(
                parsed, new TextParser().parse("new.txt", "text/plain", parsed.content())));
    fixture
        .authority
        .indexing()
        .createReplacementIndexing(
            owner, id, uploaded.candidateRevisionId(), revision, PublishedCorpusFixture.TARGET);
    complete(
        fixture, fixture.authority.indexing().claimIndexing(owner.workspaceId()).orElseThrow());
  }

  private void complete(PublishedCorpusFixture fixture, IndexClaim claim) {
    var digests = new LinkedHashMap<String, String>();
    PublishedCorpusFixture.physicalIds(claim).forEach(id -> digests.put(id, "a".repeat(64)));
    var manifest =
        new RetrievalProjection.RevisionManifest(
            owner.workspaceId(), claim.documentId(), claim.projectionGenerationId(), digests);
    assertTrue(
        fixture
            .authority
            .indexing()
            .completeIndexing(
                claim,
                digests,
                new VerifiedRevision(
                    PublishedCorpusFixture.TARGET.projectionIdentity(),
                    manifest.sha256(),
                    digests.size())));
  }

  private WikiWorkspaceService wiki(SqliteAuthorityStore store) {
    return new WikiWorkspaceService(
        store,
        new WikiWorkspaceRepository(store),
        new SynopsisMaterialRepository(store),
        new WikiCompilationService(),
        null,
        owner.workspaceId());
  }

  private WikiMaintenanceService maintenance(SqliteAuthorityStore store) {
    return new WikiMaintenanceService(
        store,
        new WikiMaintenanceRepository(store),
        new WikiWorkspaceRepository(store),
        new SynopsisMaterialRepository(store),
        owner.workspaceId());
  }
}
