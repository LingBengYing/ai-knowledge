package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.vector.DeterministicProjection;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.IndexClaim;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.VerifiedRevision;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WikiExtractiveRefreshTest {
  @TempDir Path directory;
  private final Actor owner = new Actor("org-main", "owner");

  /** An unchanged co-cited document keeps only the segments the affected section cited. */
  @Test
  void extractiveRefreshDoesNotAddUnchangedSegmentsThePageNeverCited() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var changed = fixture.publish(owner, "设备启动 1 秒。");
      var stable = fixture.publish(owner, "票据保管原文。".repeat(1500));
      var models = new RevisionModels();
      try (var runtime = runtime(fixture, models)) {
        var service = wiki(fixture.authority.store(), runtime);
        var initial =
            service.createProposal(
                owner,
                new WikiProposalCommand(
                    null,
                    0,
                    "混合规范",
                    "topic",
                    List.of(changed.documentId(), stable.documentId()),
                    "model",
                    null));
        var page = service.accept(owner, initial.id(), 0);
        var cited =
            page.content().sections().stream()
                .flatMap(section -> section.sources().stream())
                .filter(source -> source.documentId().equals(stable.documentId()))
                .map(source -> source.evidenceId())
                .collect(java.util.stream.Collectors.toSet());
        replace(fixture, changed.documentId(), changed.revisionId(), "设备启动 3 秒。");
        var proposal =
            service.refreshProposal(
                owner,
                page.pageId(),
                new WikiRefreshCommand(1, "extractive", currentVersions(fixture, page.pageId())));
        var added =
            proposal.after().sections().stream()
                .flatMap(section -> section.sources().stream())
                .filter(source -> source.documentId().equals(stable.documentId()))
                .map(source -> source.evidenceId())
                .filter(locator -> !cited.contains(locator))
                .toList();
        assertEquals(List.of(), added, "unchanged segments pulled into the page: " + added.size());
        assertEquals(2, proposal.after().sections().size());
      }
    }
  }

  /** Each affected section is replaced where it stood, under a heading no kept section uses. */
  @Test
  void extractiveRefreshReplacesSectionsInPlaceWithoutHeadingCollisions() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var first = fixture.publish(owner, "第一份 1 版。");
      var stable = fixture.publish(owner, "中间资料保持。");
      var last = fixture.publish(owner, "第三份 1 版。");
      var service = wiki(fixture.authority.store());
      var initial =
          service.createProposal(
              owner,
              new WikiProposalCommand(
                  null,
                  0,
                  "三段摘编",
                  "topic",
                  List.of(first.documentId(), stable.documentId(), last.documentId()),
                  "extractive",
                  null));
      var page = service.accept(owner, initial.id(), 0);
      var kept = page.content().sections().get(1);
      replace(fixture, first.documentId(), first.revisionId(), "第一份 2 版。");
      replace(fixture, last.documentId(), last.revisionId(), "第三份 2 版。");
      var proposal =
          service.refreshProposal(
              owner,
              page.pageId(),
              new WikiRefreshCommand(1, "extractive", currentVersions(fixture, page.pageId())));
      var sections = proposal.after().sections();
      assertEquals(
          List.of(first.documentId(), stable.documentId(), last.documentId()),
          sections.stream().map(section -> section.sources().getFirst().documentId()).toList());
      assertEquals(kept, sections.get(1));
      assertTrue(sections.getFirst().body().contains("2 版"));
      assertTrue(sections.getLast().body().contains("2 版"));
      assertEquals(
          sections.size(),
          sections.stream().map(section -> section.heading()).distinct().count(),
          sections.stream().map(section -> section.heading()).toList().toString());
    }
  }

  /** A reviewed source list that no longer matches the page is a stale review (409). */
  @Test
  void refreshWithSourceListThatNoLongerMatchesThePageIsAConflict() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var changed = fixture.publish(owner, "设备启动 1 秒。");
      var stable = fixture.publish(owner, "票据保管 6 个月。");
      var service = wiki(fixture.authority.store());
      var initial =
          service.createProposal(
              owner,
              new WikiProposalCommand(
                  null,
                  0,
                  "规范",
                  "topic",
                  List.of(changed.documentId(), stable.documentId()),
                  "extractive",
                  null));
      var page = service.accept(owner, initial.id(), 0);
      replace(fixture, changed.documentId(), changed.revisionId(), "设备启动 3 秒。");
      var partial =
          currentVersions(fixture, page.pageId()).stream()
              .filter(version -> version.documentId().equals(changed.documentId()))
              .toList();
      var error =
          assertThrows(
              ApplicationException.class,
              () ->
                  service.refreshProposal(
                      owner, page.pageId(), new WikiRefreshCommand(1, "extractive", partial)));
      assertEquals("wiki_source_changed", error.code());
    }
  }

  /** "all" is the source-quality queue; stale pages have their own kind and count. */
  @Test
  void staleKnowledgeIsCountedSeparatelyFromTheQualityQueue() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var changed = fixture.publish(owner, "设备启动 1 秒。");
      var service = wiki(fixture.authority.store());
      var initial =
          service.createProposal(
              owner,
              new WikiProposalCommand(
                  null, 0, "规范", "topic", List.of(changed.documentId()), "extractive", null));
      var page = service.accept(owner, initial.id(), 0);
      var maintenance = maintenance(fixture.authority.store());
      assertEquals(0L, maintenance.list(owner, 0, 20, "stale_knowledge").total());
      replace(fixture, changed.documentId(), changed.revisionId(), "设备启动 3 秒。");
      var stale = maintenance.list(owner, 0, 20, "stale_knowledge");
      assertEquals(1L, stale.total());
      assertEquals(page.pageId(), stale.items().getFirst().targetId());
      var all = maintenance.list(owner, 0, 20, "all");
      assertEquals(1L, all.counts().get("stale_knowledge"));
      assertEquals(0L, all.total());
      assertTrue(all.items().stream().noneMatch(item -> item.kind().equals("stale_knowledge")));
      assertTrue(maintenance.sourceChanges(owner, page.pageId()).canRefresh());
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
                  List.of(evidence.getFirst().id(), evidence.getLast().id())),
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
