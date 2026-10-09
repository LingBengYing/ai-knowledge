package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.vector.DeterministicProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.dto.WikiPageLifecycleCommand;
import com.evidence.rag.model.dto.WikiProposalCommand;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.repository.SynopsisMaterialRepository;
import com.evidence.rag.repository.WikiWorkspaceRepository;
import com.evidence.rag.support.PublishedCorpusFixture;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WikiWorkspaceServiceTest {
  @TempDir Path directory;
  private final Actor owner = new Actor("org-main", "owner");

  @Test
  void proposalIsReviewedBeforePageExistsAndSurvivesRestartWithOriginalSource() {
    String pageId;
    String proposalId;
    String sourceId;
    String text = "青榆灯塔计划于十一月启动。先切断电源，再进行维护。";
    try (var fixture = new PublishedCorpusFixture(directory)) {
      String documentId = fixture.publish(owner, text).documentId();
      var service = service(fixture.authority.store());
      var proposal = service.createProposal(owner, command(null, 0, "灯塔", documentId));
      proposalId = proposal.id();
      pageId = proposal.pageId();
      assertEquals("pending", proposal.status());
      assertNull(proposal.before());
      assertEquals("extractive", proposal.generationMethod());
      assertEquals(WikiCompilationService.POLICY_REVISION, proposal.policyRevision());
      assertEquals("current", proposal.sourceState());
      assertEquals(0, service.pages(owner, 0, 20, "").total());
      assertEquals(1, service.proposals(owner, 0, 20, "pending").total());
      assertEquals(text, proposal.after().sections().getFirst().body());
      var source = proposal.after().sections().getFirst().sources().getFirst();
      sourceId = source.id();
      assertEquals(documentId, source.documentId());
      assertTrue(source.current());
      assertArrayEquals(
          text.getBytes(StandardCharsets.UTF_8),
          service.proposalSource(owner, proposalId, sourceId).content());
      var page = service.accept(owner, proposalId, 0);
      assertEquals(1, page.version());
      assertEquals(pageId, page.pageId());
      assertEquals("accepted", service.proposal(owner, proposalId).status());
      assertEquals(1, service.pages(owner, 0, 20, "灯塔").total());
      assertEquals(0, service.pages(owner, 0, 20, "不存在").total());
    }
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var service = service(fixture.authority.store());
      assertEquals(1, service.page(owner, pageId).version());
      assertEquals("accepted", service.proposal(owner, proposalId).status());
      assertArrayEquals(
          text.getBytes(StandardCharsets.UTF_8),
          service.source(owner, pageId, 1, sourceId).content());
      assertEquals(service.page(owner, pageId), service.version(owner, pageId, 1));
    }
  }

  @Test
  void versionConflictsDoNotOverwritePageOrEndCompetingProposal() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      String documentId = fixture.publish(owner, "灯塔维护说明。").documentId();
      var service = service(fixture.authority.store());
      var initial = service.createProposal(owner, command(null, 0, "初版", documentId));
      var first = service.accept(owner, initial.id(), 0);
      var next = service.createProposal(owner, command(first.pageId(), 1, "新版", documentId));
      var competing = service.createProposal(owner, command(first.pageId(), 1, "竞争版", documentId));
      assertEquals("初版", next.before().title());
      assertEquals(2, service.accept(owner, next.id(), 1).version());
      assertEquals("初版", service.version(owner, first.pageId(), 1).content().title());
      assertEquals("新版", service.page(owner, first.pageId()).content().title());
      assertEquals(
          FailureKind.CONFLICT,
          assertThrows(ApplicationException.class, () -> service.accept(owner, competing.id(), 1))
              .kind());
      assertEquals("pending", service.proposal(owner, competing.id()).status());
      assertThrows(ApplicationException.class, () -> service.accept(owner, next.id(), 1));
      assertThrows(
          ApplicationException.class,
          () -> service.createProposal(owner, command(first.pageId(), 1, "旧基础", documentId)));
    }
  }

  @Test
  void duplicateTextInDifferentFilesRetainsBothSourceIdentities() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      String first = fixture.publish(owner, "灯塔预算是合成示例。").documentId();
      String second = fixture.publish(owner, "灯塔预算是合成示例。").documentId();
      var service = service(fixture.authority.store());
      var proposal =
          service.createProposal(
              owner,
              new WikiProposalCommand(
                  null, 0, "灯塔", "topic", List.of(first, second), "extractive"));
      var sources =
          proposal.after().sections().stream().flatMap(s -> s.sources().stream()).toList();
      assertEquals(2, sources.size());
      assertNotEquals(sources.getFirst().documentId(), sources.getLast().documentId());
      assertNotEquals(sources.getFirst().id(), sources.getLast().id());
      for (var source : sources) {
        assertEquals(
            source.evidenceSha256(),
            service.proposalSource(owner, proposal.id(), source.id()).evidence().sha256());
      }
    }
  }

  @Test
  void withdrawnSourceMakesPageStaleAndBlocksReadsAndPendingAcceptance() throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      String documentId = fixture.publish(owner, "灯塔旧资料。").documentId();
      var service = service(fixture.authority.store());
      var initial = service.createProposal(owner, command(null, 0, "灯塔", documentId));
      var page = service.accept(owner, initial.id(), 0);
      var pending = service.createProposal(owner, command(page.pageId(), 1, "更新", documentId));
      try (var connection =
              DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
          var statement =
              connection.prepareStatement("INSERT INTO document_tombstones VALUES(?,?,?,?)")) {
        statement.setString(1, documentId);
        statement.setString(2, owner.workspaceId());
        statement.setString(3, owner.principalId());
        statement.setString(4, "2026-10-09T00:00:00Z");
        statement.executeUpdate();
      }
      assertEquals("stale", service.page(owner, page.pageId()).sourceState());
      assertFalse(
          service
              .page(owner, page.pageId())
              .content()
              .sections()
              .getFirst()
              .sources()
              .getFirst()
              .current());
      assertEquals("stale", service.proposal(owner, pending.id()).sourceState());
      assertEquals("", service.page(owner, page.pageId()).content().sections().getFirst().body());
      assertEquals(
          "来源资料已清理", service.page(owner, page.pageId()).content().sections().getFirst().heading());
      assertEquals(
          "", service.version(owner, page.pageId(), 1).content().sections().getFirst().body());
      assertEquals("", service.proposal(owner, initial.id()).after().sections().getFirst().body());
      assertEquals("", service.proposal(owner, pending.id()).before().sections().getFirst().body());
      assertEquals("", service.proposal(owner, pending.id()).after().sections().getFirst().body());
      assertThrows(ApplicationException.class, () -> service.accept(owner, pending.id(), 1));
      assertEquals("pending", service.proposal(owner, pending.id()).status());
      String sourceId = page.content().sections().getFirst().sources().getFirst().id();
      assertThrows(
          ApplicationException.class, () -> service.source(owner, page.pageId(), 1, sourceId));
      assertThrows(
          ApplicationException.class, () -> service.proposalSource(owner, initial.id(), sourceId));
    }
  }

  @Test
  void organizationMembersShareReviewButOtherOrganizationCannotReadOrWrite() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      String documentId = fixture.publish(owner, "共同资料。").documentId();
      var service = service(fixture.authority.store());
      var member = new Actor(owner.workspaceId(), "member");
      var proposal = service.createProposal(member, command(null, 0, "共享", documentId));
      var page = service.accept(owner, proposal.id(), 0);
      assertEquals(page, service.page(member, page.pageId()));
      var other = new Actor("other-org", "owner");
      assertEquals(
          FailureKind.FORBIDDEN,
          assertThrows(ApplicationException.class, () -> service.page(other, page.pageId()))
              .kind());
      assertThrows(ApplicationException.class, () -> service.pages(other, 0, 20, ""));
      assertThrows(
          ApplicationException.class,
          () -> service.createProposal(other, command(null, 0, "越界", documentId)));
      assertEquals(
          FailureKind.UNAUTHENTICATED,
          assertThrows(ApplicationException.class, () -> service.pages(null, 0, 20, "")).kind());
    }
  }

  @Test
  void dismissalPersistsAndNeverCreatesPage() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      String documentId = fixture.publish(owner, "审阅资料。").documentId();
      var service = service(fixture.authority.store());
      var proposal = service.createProposal(owner, command(null, 0, "待审", documentId));
      assertEquals("dismissed", service.dismiss(owner, proposal.id()).status());
      assertEquals(0, service.pages(owner, 0, 20, "").total());
      assertEquals(1, service.proposals(owner, 0, 20, "dismissed").total());
      assertThrows(ApplicationException.class, () -> service.accept(owner, proposal.id(), 0));
      assertThrows(ApplicationException.class, () -> service.dismiss(owner, proposal.id()));
    }
  }

  @Test
  void lifecycleHidesOtherOrganizationAndNeverCallsModelForDeletedPage() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      String documentId = fixture.publish(owner, "可恢复的原始资料。").documentId();
      var service = service(fixture.authority.store());
      var proposed = service.createProposal(owner, command(null, 0, "可恢复", documentId));
      var page = service.accept(owner, proposed.id(), 0);
      var other = new Actor("other-org", "member");
      assertEquals(
          FailureKind.NOT_FOUND,
          assertThrows(
                  ApplicationException.class,
                  () -> service.delete(other, page.pageId(), new WikiPageLifecycleCommand(1, 0)))
              .kind());
      assertEquals(
          FailureKind.NOT_FOUND,
          assertThrows(
                  ApplicationException.class,
                  () -> service.restore(other, page.pageId(), new WikiPageLifecycleCommand(1, 0)))
              .kind());
      assertEquals(
          FailureKind.NOT_FOUND,
          assertThrows(
                  ApplicationException.class,
                  () -> service.purge(other, page.pageId(), new WikiPageLifecycleCommand(1, 0)))
              .kind());
      service.delete(owner, page.pageId(), new WikiPageLifecycleCommand(1, 0));
      assertEquals(
          "wiki_page_deleted",
          assertThrows(
                  ApplicationException.class,
                  () ->
                      service.createProposal(
                          owner,
                          new WikiProposalCommand(
                              page.pageId(), 1, "不得调用模型", "topic", List.of(documentId), "model")))
              .code());
      assertEquals("deleted", service.page(owner, page.pageId()).state());
      assertEquals(1, service.version(owner, page.pageId(), 1).version());
    }
  }

  @Test
  void missingModelConfigurationDoesNotPersistFakeProposal() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      String documentId = fixture.publish(owner, "模型资料。").documentId();
      var service = service(fixture.authority.store());
      var failure =
          assertThrows(
              ApplicationException.class,
              () ->
                  service.createProposal(
                      owner,
                      new WikiProposalCommand(
                          null, 0, "模型页", "topic", List.of(documentId), "model")));
      assertEquals(FailureKind.UNAVAILABLE, failure.kind());
      assertEquals(0, service.proposals(owner, 0, 20, "pending").total());
    }
  }

  @Test
  void modelCompilesOutsideAuthorityTransactionInsideRuntimeLeaseAndNeverPublishesAutomatically() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      String documentId = fixture.publish(owner, "灯塔资料原文。").documentId();
      var store = fixture.authority.store();
      var calls = new AtomicInteger();
      var models =
          new WikiModels(
              evidence -> {
                calls.incrementAndGet();
                assertTrue(store.transaction(() -> true));
                assertTrue(store.operationGate().tryMaintenance().isEmpty());
                assertEquals("灯塔资料原文。", evidence.getFirst().text());
                return draft(evidence);
              });
      try (var runtime = runtime(fixture, models)) {
        var service = service(store, runtime);
        var result =
            service.createProposal(
                owner,
                new WikiProposalCommand(null, 0, "模型知识页", "topic", List.of(documentId), "model"));
        assertEquals(1, calls.get());
        assertEquals("model", result.generationMethod());
        assertEquals(models.revision(), result.modelRevision());
        assertEquals(
            WikiCompilationService.POLICY_REVISION
                + ":"
                + TextModels.WIKI_COMPILATION_PROMPT_REVISION,
            result.policyRevision());
        assertEquals("pending", result.status());
        assertEquals(0, service.pages(owner, 0, 20, "").total());
        assertEquals(1, service.accept(owner, result.id(), 0).version());
      }
      assertTrue(store.operationGate().isIdle());
    }
  }

  @Test
  void sourceChangeDuringModelCallRejectsEntireProposalWithoutRetry() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      String documentId = fixture.publish(owner, "待更新的原资料。").documentId();
      var store = fixture.authority.store();
      var calls = new AtomicInteger();
      var models =
          new WikiModels(
              evidence -> {
                calls.incrementAndGet();
                withdraw(documentId);
                return draft(evidence);
              });
      try (var runtime = runtime(fixture, models)) {
        var service = service(store, runtime);
        var failure =
            assertThrows(
                ApplicationException.class,
                () ->
                    service.createProposal(
                        owner,
                        new WikiProposalCommand(
                            null, 0, "模型知识页", "topic", List.of(documentId), "model")));
        assertEquals("wiki_source_changed", failure.code());
        assertEquals(1, calls.get());
        assertEquals(0, service.proposals(owner, 0, 20, "pending").total());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"upstream_failure", "invalid_response"})
  void providerFailureReturnsUnavailableWithoutRetryOrHalfSavedProposal(String failureMode) {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      String documentId = fixture.publish(owner, "模型失败资料。").documentId();
      var store = fixture.authority.store();
      var calls = new AtomicInteger();
      var models =
          new WikiModels(
              evidence -> {
                calls.incrementAndGet();
                if ("invalid_response".equals(failureMode)) {
                  return new TextModels.WikiDraft(
                      List.of(
                          new TextModels.WikiSectionDraft(
                              "不正确的来源", "待审文本", List.of("invented-evidence"))));
                }
                throw new TextModels.Failure("model_upstream_failure");
              });
      try (var runtime = runtime(fixture, models)) {
        var service = service(store, runtime);
        var failure =
            assertThrows(
                ApplicationException.class,
                () ->
                    service.createProposal(
                        owner,
                        new WikiProposalCommand(
                            null, 0, "模型知识页", "topic", List.of(documentId), "model")));
        assertEquals(FailureKind.UNAVAILABLE, failure.kind());
        assertEquals("wiki_compilation_unavailable", failure.code());
        assertEquals(1, calls.get());
        assertEquals(0, service.proposals(owner, 0, 20, "pending").total());
      }
    }
  }

  private void withdraw(String documentId) {
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement =
            connection.prepareStatement("INSERT INTO document_tombstones VALUES(?,?,?,?)")) {
      statement.setString(1, documentId);
      statement.setString(2, owner.workspaceId());
      statement.setString(3, owner.principalId());
      statement.setString(4, "2026-10-09T00:00:00Z");
      statement.executeUpdate();
    } catch (Exception failure) {
      throw new AssertionError("Synthetic source withdrawal failed", failure);
    }
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

  private static TextModels.WikiDraft draft(List<TextModels.Evidence> evidence) {
    return new TextModels.WikiDraft(
        List.of(
            new TextModels.WikiSectionDraft(
                "概览", evidence.getFirst().text(), List.of(evidence.getFirst().id()))));
  }

  private static final class WikiModels implements TextModels {
    private final Function<List<Evidence>, WikiDraft> compile;

    WikiModels(Function<List<Evidence>, WikiDraft> compile) {
      this.compile = compile;
    }

    @Override
    public WikiDraft compileWiki(String title, List<Evidence> evidence) {
      return compile.apply(evidence);
    }

    @Override
    public List<List<Double>> embed(List<String> texts) {
      throw new AssertionError("Wiki compilation does not embed");
    }

    @Override
    public List<Ranked> rerank(String query, List<String> texts) {
      throw new AssertionError("Wiki compilation does not rerank");
    }

    @Override
    public Extraction extract(String query, List<Evidence> evidence) {
      throw new AssertionError("Wiki compilation uses its own protocol");
    }

    @Override
    public String revision() {
      return "wiki-test-model-v1";
    }
  }

  private WikiWorkspaceService service(SqliteAuthorityStore store) {
    return service(store, null);
  }

  private WikiWorkspaceService service(SqliteAuthorityStore store, ManagedTextRuntime runtime) {
    return new WikiWorkspaceService(
        store,
        new WikiWorkspaceRepository(store),
        new SynopsisMaterialRepository(store),
        new WikiCompilationService(),
        runtime,
        owner.workspaceId());
  }

  private static WikiProposalCommand command(
      String pageId, long version, String title, String documentId) {
    return new WikiProposalCommand(
        pageId, version, title, "topic", List.of(documentId), "extractive");
  }
}
