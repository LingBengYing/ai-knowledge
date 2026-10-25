package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.vector.DeterministicProjection;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.IndexClaim;
import com.evidence.rag.model.domain.IndexTarget;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WikiMaintenanceServiceTest {
  @TempDir Path directory;
  private final Actor owner = new Actor("org-main", "owner");

  @ParameterizedTest
  @CsvSource({"model,false", "model,true"})
  void revisionUpdatesExactOriginalSlotsAndNeverDuplicatesIndependentUnchangedSections(
      String method, boolean allAffected) {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var changed = fixture.publish(owner, "设备启动按键 1 秒。");
      var stable = fixture.publish(owner, "票据保管 6 个月。");
      var models = new RevisionModels();
      try (var runtime = runtime(fixture, models)) {
        var service = wiki(fixture.authority.store(), runtime);
        var initial =
            service.createProposal(
                owner,
                new WikiProposalCommand(
                    null,
                    0,
                    "两份独立规范",
                    "topic",
                    List.of(changed.documentId(), stable.documentId()),
                    "model"));
        var page = service.accept(owner, initial.id(), 0);
        var original = page.content().sections();
        assertEquals(3, original.size());
        replace(fixture, changed.documentId(), changed.revisionId(), "设备启动按键 3 秒。");
        if (allAffected) {
          replace(fixture, stable.documentId(), stable.revisionId(), "票据保管 8 个月。");
        }
        var changes = maintenance(fixture.authority.store()).sourceChanges(owner, page.pageId());
        var versions =
            changes.sources().stream()
                .map(
                    source ->
                        new WikiSourceVersion(source.documentId(), source.after().publicationId()))
                .toList();
        var proposal =
            service.refreshProposal(
                owner, page.pageId(), new WikiRefreshCommand(1, method, versions));
        var updated = proposal.after().sections();
        assertEquals(
            original.stream().map(section -> section.id()).toList(),
            updated.stream().map(section -> section.id()).toList());
        assertEquals(3, updated.size());
        assertTrue(updated.getFirst().body().contains("3 秒"));
        assertTrue(updated.get(1).body().contains("3 秒"));
        assertFalse(updated.stream().anyMatch(section -> section.body().contains("1 秒")));
        assertEquals(2, updated.getFirst().sources().size());
        assertEquals(1, updated.get(1).sources().size());
        assertEquals(changed.documentId(), updated.get(1).sources().getFirst().documentId());
        if (allAffected) {
          assertTrue(updated.getLast().body().contains("8 个月"));
        } else {
          assertEquals(original.getLast(), updated.getLast());
          var retainedSource = original.getLast().sources().getFirst();
          assertTrue(
              updated.subList(0, 2).stream()
                  .flatMap(section -> section.sources().stream())
                  .noneMatch(source -> source.id().equals(retainedSource.id())));
        }
        assertEquals(1, service.page(owner, page.pageId()).version());
        assertEquals(1, models.compilations);
        assertEquals("model".equals(method) ? 1 : 0, models.revisions);
        if ("model".equals(method)) {
          assertEquals(allAffected ? 3 : 2, models.targets.size());
          assertEquals(2, models.targets.getFirst().evidenceIds().size());
          assertEquals(1, models.targets.get(1).evidenceIds().size());
        }
        assertTrue(
            proposal.policyRevision().startsWith(WikiCompilationService.REVISION_POLICY_REVISION));
        assertEquals(2, service.accept(owner, proposal.id(), 1).version());
        assertEquals(
            original.getFirst().body(),
            service.version(owner, page.pageId(), 1).content().sections().getFirst().body());
        assertFalse(
            maintenance(fixture.authority.store())
                .sourceChanges(owner, page.pageId())
                .canRefresh());
      }
    }
  }

  @Test
  void extractiveRevisionKeepsLargeDocumentsAsBoundedSegmentsAndRetainsOtherSections() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var changed = fixture.publish(owner, "旧资料。".repeat(1500));
      var stable = fixture.publish(owner, "保管原文保持。");
      var service = wiki(fixture.authority.store());
      var initial =
          service.createProposal(
              owner,
              new WikiProposalCommand(
                  null,
                  0,
                  "大文档摘编",
                  "topic",
                  List.of(changed.documentId(), stable.documentId()),
                  "extractive"));
      var page = service.accept(owner, initial.id(), 0);
      var retained = page.content().sections().getLast();
      assertTrue(page.content().sections().size() > 5);
      String updated = "新资料😀。".repeat(1800);
      var expected =
          new TextParser()
                  .parse("new.txt", "text/plain", updated.getBytes(StandardCharsets.UTF_8))
                  .segments()
                  .stream()
                  .map(segment -> segment.text())
                  .toList();
      replace(fixture, changed.documentId(), changed.revisionId(), updated);
      var proposal =
          service.refreshProposal(
              owner,
              page.pageId(),
              new WikiRefreshCommand(1, "extractive", currentVersions(fixture, page.pageId())));
      var sections = proposal.after().sections();
      assertEquals(expected.size() + 1, sections.size());
      assertEquals(
          expected,
          sections.subList(0, expected.size()).stream().map(section -> section.body()).toList());
      assertTrue(
          sections.stream()
              .allMatch(
                  section -> section.body().codePointCount(0, section.body().length()) <= 1200));
      assertEquals(retained, sections.getLast());
      assertEquals(
          expected.size(),
          sections.stream()
              .filter(
                  section -> section.sources().getFirst().documentId().equals(changed.documentId()))
              .count());
      assertEquals(
          WikiCompilationService.EXTRACTIVE_REVISION_POLICY_REVISION, proposal.policyRevision());
      assertEquals(2, service.accept(owner, proposal.id(), 1).version());
    }
  }

  @Test
  void extractiveRevisionDoesNotRepeatUnchangedEvidenceAlreadyRetainedByAMixedPage() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var changed = fixture.publish(owner, "设备启动 1 秒。");
      var stable = fixture.publish(owner, "票据保管 6 个月。");
      var models = new RevisionModels();
      try (var runtime = runtime(fixture, models)) {
        var service = wiki(fixture.authority.store(), runtime);
        var initial =
            service.createProposal(
                owner,
                new WikiProposalCommand(
                    null,
                    0,
                    "独立规范",
                    "topic",
                    List.of(changed.documentId(), stable.documentId()),
                    "model"));
        var page = service.accept(owner, initial.id(), 0);
        var retained = page.content().sections().getLast();
        replace(fixture, changed.documentId(), changed.revisionId(), "设备启动 3 秒。");
        var proposal =
            service.refreshProposal(
                owner,
                page.pageId(),
                new WikiRefreshCommand(1, "extractive", currentVersions(fixture, page.pageId())));
        assertEquals(2, proposal.after().sections().size());
        assertTrue(proposal.after().sections().getFirst().body().contains("3 秒"));
        assertEquals(retained, proposal.after().sections().getLast());
        assertEquals(
            1,
            proposal.after().sections().stream()
                .filter(
                    section ->
                        section.sources().getFirst().documentId().equals(stable.documentId()))
                .count());
        assertEquals(0, models.revisions);
      }
    }
  }

  @Test
  void unchangedOnlyModelSectionCannotEnterRevisionOrBecomeAPendingProposal() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var changed = fixture.publish(owner, "设备启动 1 秒。");
      var stable = fixture.publish(owner, "票据保管 6 个月。");
      var models = new RevisionModels();
      try (var runtime = runtime(fixture, models)) {
        var service = wiki(fixture.authority.store(), runtime);
        var initial =
            service.createProposal(
                owner,
                new WikiProposalCommand(
                    null,
                    0,
                    "独立规范",
                    "topic",
                    List.of(changed.documentId(), stable.documentId()),
                    "model"));
        var page = service.accept(owner, initial.id(), 0);
        models.extraSectionId = page.content().sections().getLast().id();
        replace(fixture, changed.documentId(), changed.revisionId(), "设备启动 3 秒。");
        var error =
            assertThrows(
                ApplicationException.class,
                () ->
                    service.refreshProposal(
                        owner,
                        page.pageId(),
                        new WikiRefreshCommand(
                            1, "model", currentVersions(fixture, page.pageId()))));
        assertEquals("wiki_compilation_unavailable", error.code());
        assertEquals(1, models.revisions);
        assertEquals(0, service.proposals(owner, 0, 20, "pending").total());
        assertEquals(1, service.page(owner, page.pageId()).version());
      }
    }
  }

  @Test
  void retainedSourceChangedDuringRevisionStillRejectsWholeProposal() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var changed = fixture.publish(owner, "设备启动 1 秒。");
      var stable = fixture.publish(owner, "票据保管 6 个月。");
      var models = new RevisionModels();
      try (var runtime = runtime(fixture, models)) {
        var service = wiki(fixture.authority.store(), runtime);
        var initial =
            service.createProposal(
                owner,
                new WikiProposalCommand(
                    null,
                    0,
                    "独立规范",
                    "topic",
                    List.of(changed.documentId(), stable.documentId()),
                    "model"));
        var page = service.accept(owner, initial.id(), 0);
        replace(fixture, changed.documentId(), changed.revisionId(), "设备启动 3 秒。");
        models.onRevision =
            () -> replace(fixture, stable.documentId(), stable.revisionId(), "票据保管 8 个月。");
        var error =
            assertThrows(
                ApplicationException.class,
                () ->
                    service.refreshProposal(
                        owner,
                        page.pageId(),
                        new WikiRefreshCommand(
                            1, "model", currentVersions(fixture, page.pageId()))));
        assertEquals("wiki_source_changed", error.code());
        assertEquals(1, models.revisions);
        assertEquals(0, service.proposals(owner, 0, 20, "pending").total());
        assertEquals(1, service.page(owner, page.pageId()).version());
      }
    }
  }

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

  private List<WikiSourceVersion> currentVersions(PublishedCorpusFixture fixture, String pageId) {
    return maintenance(fixture.authority.store()).sourceChanges(owner, pageId).sources().stream()
        .map(source -> new WikiSourceVersion(source.documentId(), source.after().publicationId()))
        .toList();
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
    return wiki(store, null);
  }

  private WikiWorkspaceService wiki(SqliteAuthorityStore store, ManagedTextRuntime runtime) {
    return new WikiWorkspaceService(
        store,
        new WikiWorkspaceRepository(store),
        new SynopsisMaterialRepository(store),
        new WikiCompilationService(),
        runtime,
        owner.workspaceId());
  }

  private ManagedTextRuntime runtime(PublishedCorpusFixture fixture, TextModels models) {
    var store = fixture.authority.store();
    var projection = new DeterministicProjection(owner.workspaceId(), 2);
    var target = new IndexTarget("wiki-test", projection.identity(), models.revision(), 2);
    var answers =
        new AnswerService(fixture.evidence, models, projection, target, Duration.ofSeconds(3), 2);
    var indexing =
        new IndexingTaskProcessor(
            fixture.authority.indexing(),
            owner.workspaceId(),
            target,
            Duration.ofSeconds(3),
            ignored -> {
              throw new AssertionError("Wiki must not reindex");
            });
    var snapshot =
        new TextRuntimeSnapshot(1, models, projection, target, answers, indexing, () -> {});
    var runtime = new ManagedTextRuntime(store, (version, configuration) -> snapshot);
    try (var lease = store.operationGate().tryMaintenance().orElseThrow()) {
      runtime.install(snapshot, lease, () -> {});
    }
    return runtime;
  }

  private static final class RevisionModels implements TextModels {
    int compilations;
    int revisions;
    List<WikiSectionTarget> targets;
    String extraSectionId;
    Runnable onRevision = () -> {};

    @Override
    public WikiDraft compileWiki(String title, List<Evidence> evidence) {
      compilations++;
      return new WikiDraft(
          List.of(
              new WikiSectionDraft(
                  "概述",
                  evidence.getFirst().text() + "\n" + evidence.getLast().text(),
                  evidence.stream().map(Evidence::id).toList()),
              new WikiSectionDraft(
                  "设备操作", evidence.getFirst().text(), List.of(evidence.getFirst().id())),
              new WikiSectionDraft(
                  "票据保管", evidence.getLast().text(), List.of(evidence.getLast().id()))));
    }

    @Override
    public WikiRevisionDraft reviseWiki(
        String title, List<Evidence> evidence, List<WikiSectionTarget> targets) {
      revisions++;
      this.targets = targets;
      onRevision.run();
      var sections = new ArrayList<WikiSectionRevision>();
      for (var target : targets) {
        String body =
            evidence.stream()
                .filter(item -> target.evidenceIds().contains(item.id()))
                .map(Evidence::text)
                .collect(java.util.stream.Collectors.joining("\n"));
        sections.add(
            new WikiSectionRevision(
                target.sectionId(), target.heading(), body, target.evidenceIds()));
      }
      Collections.reverse(sections);
      if (extraSectionId != null) {
        sections.add(
            new WikiSectionRevision(
                extraSectionId,
                "多余保管段",
                evidence.getLast().text(),
                List.of(evidence.getLast().id())));
      }
      return new WikiRevisionDraft(sections);
    }

    @Override
    public List<List<Double>> embed(List<String> texts) {
      throw new AssertionError("unexpected embedding");
    }

    @Override
    public List<Ranked> rerank(String query, List<String> texts) {
      throw new AssertionError("unexpected rerank");
    }

    @Override
    public Extraction extract(String query, List<Evidence> evidence) {
      throw new AssertionError("unexpected extraction");
    }

    @Override
    public String revision() {
      return "wiki-revision-synthetic-v1";
    }
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
