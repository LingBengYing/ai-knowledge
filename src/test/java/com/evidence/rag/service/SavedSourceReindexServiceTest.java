package com.evidence.rag.service;

import static com.evidence.rag.support.PublishedCorpusFixture.TARGET;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.FileSynopsis;
import com.evidence.rag.model.domain.IndexClaim;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.SynopsisClaim;
import com.evidence.rag.model.domain.SynopsisDraft;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.repository.IndexingRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.repository.SynopsisMaterialRepository;
import com.evidence.rag.repository.SynopsisRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.PublishedCorpusFixture;
import com.evidence.rag.web.HttpProblemMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** New generation replacement against real SQLite; receipt values are explicit local fixtures. */
class SavedSourceReindexServiceTest {
  @TempDir Path directory;
  private final Actor owner = new Actor("org", "owner");
  private static final String CONTENT = "蓝色装置采用太阳能。";

  @Test
  void successfulReplacementPreservesSavedSourceAndOldJobButChangesTheWholeCurrentScope()
      throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var original = fixture.publish(owner, CONTENT.repeat(300));
      var authority = fixture.authority;
      var service = authority.indexing();
      var base = active(authority.store(), original.documentId());
      var before = fixture.evidence.snapshot(owner, DocumentSelection.allDocuments(), TARGET);
      var parsed = authority.parsedEvidence(owner, original.documentId());
      var bytes =
          authority
              .management()
              .documentContent(owner, original.documentId(), original.revisionId())
              .content();
      var initialStatus = service.indexingStatus(owner, original.jobId());
      var created = service.createReindexing(owner, original.documentId(), base, TARGET);
      assertNotEquals(original.jobId(), created.taskId());
      assertEquals("queued", created.state());
      assertEquals(1, created.attempt());
      assertNull(created.indexPublicationId());
      assertEquals(base, active(authority.store(), original.documentId()));
      assertEquals(
          before, fixture.evidence.snapshot(owner, DocumentSelection.allDocuments(), TARGET));
      var claim = service.claimIndexing(owner.workspaceId()).orElseThrow();
      assertTrue(claim.items().size() > 1);
      assertEquals(original.items(), claim.items());
      assertEquals(original.sourceSha256(), claim.sourceSha256());
      assertEquals(original.revisionId(), claim.revisionId());
      assertNotEquals(original.projectionGenerationId(), claim.projectionGenerationId());
      assertTrue(
          java.util.Collections.disjoint(digests(original).keySet(), digests(claim).keySet()));
      assertEquals(
          before, fixture.evidence.snapshot(owner, DocumentSelection.allDocuments(), TARGET));
      assertTrue(complete(service, claim));
      assertFalse(complete(service, claim));
      var current = service.indexingStatus(owner, claim.jobId());
      assertEquals("indexed", current.state());
      assertNotEquals(base, current.indexPublicationId());
      assertEquals(current.indexPublicationId(), active(authority.store(), claim.documentId()));
      assertNotEquals(
          before, fixture.evidence.snapshot(owner, DocumentSelection.allDocuments(), TARGET));
      assertEquals(initialStatus, service.indexingStatus(owner, original.jobId()));
      assertEquals(parsed, authority.parsedEvidence(owner, original.documentId()));
      assertArrayEquals(
          bytes,
          authority
              .management()
              .documentContent(owner, original.documentId(), original.revisionId())
              .content());
      assertEquals(2, count("SELECT COUNT(*) FROM index_publications"));
      assertEquals(
          original.items().size() * 2, count("SELECT COUNT(*) FROM index_publication_entries"));
      assertEquals(
          created.taskId(),
          authority
              .store()
              .transaction(
                  () ->
                      new IndexingRepository(authority.store())
                          .documentJobId(original.documentId())
                          .orElseThrow()));
      assertEquals(
          0,
          count("SELECT rebuild_sequence FROM indexing_jobs WHERE id='" + original.jobId() + "'"));
      assertEquals(
          1, count("SELECT rebuild_sequence FROM indexing_jobs WHERE id='" + claim.jobId() + "'"));
      assertEquals(
          base,
          text("SELECT base_publication_id FROM indexing_jobs WHERE id='" + claim.jobId() + "'"));
      assertEquals(
          "indexing_state_conflict",
          fails(409, () -> service.createIndexing(owner, original.documentId(), TARGET)).code());
      assertFalse(authority.auditEvents(owner).toString().contains(CONTENT));
      assertFalse(authority.auditEvents(owner).toString().contains(claim.token()));
    }
  }

  @Test
  void stalePendingAndMismatchedTargetsNeverCreateAnotherJobOrAudit() throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var original = fixture.publish(owner, CONTENT);
      var service = fixture.authority.indexing();
      var base = active(fixture.authority.store(), original.documentId());
      int auditBefore = count("SELECT COUNT(*) FROM management_audit");
      assertEquals(
          "indexing_state_conflict",
          fails(
                  409,
                  () ->
                      service.createReindexing(
                          owner, original.documentId(), "stale-publication", TARGET))
              .code());
      for (var changed :
          List.of(
              new IndexTarget(
                  "other-embedding", TARGET.projectionIdentity(), TARGET.modelRevision(), 2),
              new IndexTarget(
                  TARGET.embeddingIdentity(), "c".repeat(64), TARGET.modelRevision(), 2),
              new IndexTarget(
                  TARGET.embeddingIdentity(), TARGET.projectionIdentity(), "embed-v2", 2),
              new IndexTarget(
                  TARGET.embeddingIdentity(),
                  TARGET.projectionIdentity(),
                  TARGET.modelRevision(),
                  3))) {
        assertEquals(
            "index_configuration_changed",
            fails(409, () -> service.createReindexing(owner, original.documentId(), base, changed))
                .code());
      }
      assertEquals(1, count("SELECT COUNT(*) FROM indexing_jobs"));
      assertEquals(auditBefore, count("SELECT COUNT(*) FROM management_audit"));
      var job = service.createReindexing(owner, original.documentId(), base, TARGET);
      int queuedAudit = count("SELECT COUNT(*) FROM management_audit");
      assertEquals(
          "indexing_state_conflict",
          fails(409, () -> service.createReindexing(owner, original.documentId(), base, TARGET))
              .code());
      assertEquals(2, count("SELECT COUNT(*) FROM indexing_jobs"));
      assertEquals(queuedAudit, count("SELECT COUNT(*) FROM management_audit"));
      var claim = service.claimIndexing(owner.workspaceId()).orElseThrow();
      assertEquals(job.taskId(), claim.jobId());
      assertEquals(
          "indexing_state_conflict",
          fails(409, () -> service.createReindexing(owner, original.documentId(), base, TARGET))
              .code());
      assertTrue(complete(service, claim));
      assertEquals(
          "indexing_state_conflict",
          fails(409, () -> service.createReindexing(owner, original.documentId(), base, TARGET))
              .code());
    }
  }

  @Test
  void onlyCurrentWritersMayCreateOrRetryAndRevocationCannotSwitchTheActivePointer()
      throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var original = fixture.publish(owner, CONTENT);
      var service = fixture.authority.indexing();
      var base = active(fixture.authority.store(), original.documentId());
      var reader = new Actor(owner.workspaceId(), "reader");
      var editor = new Actor(owner.workspaceId(), "editor");
      sql("INSERT INTO document_acl VALUES('" + original.documentId() + "','reader','reader')");
      sql("INSERT INTO document_acl VALUES('" + original.documentId() + "','editor','editor')");
      for (var denied :
          List.of(reader, new Actor("other", "owner"), new Actor("org", "stranger"))) {
        fails(404, () -> service.createReindexing(denied, original.documentId(), base, TARGET));
      }
      assertEquals(1, count("SELECT COUNT(*) FROM indexing_jobs"));
      var job = service.createReindexing(owner, original.documentId(), base, TARGET);
      assertEquals("queued", service.indexingStatus(reader, job.taskId()).state());
      fails(404, () -> service.cancelIndexing(reader, job.taskId()));
      var claim = service.claimIndexing(owner.workspaceId()).orElseThrow();
      sql(
          "UPDATE document_acl SET role='reader' WHERE document_id='"
              + original.documentId()
              + "' AND principal_id='owner'");
      assertFalse(service.isIndexingClaimCurrent(claim));
      assertFalse(complete(service, claim));
      assertEquals(
          "authorization_changed", service.indexingStatus(editor, job.taskId()).errorCode());
      assertEquals(
          "authorization_changed",
          fails(409, () -> service.retryIndexing(editor, job.taskId(), TARGET)).code());
      assertEquals(base, active(fixture.authority.store(), original.documentId()));
      assertEquals(1, count("SELECT COUNT(*) FROM index_publications"));
    }
  }

  @Test
  void failureAllowsAnExplicitNewJobButAnOlderRetryCannotCollideWithIt() throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var original = fixture.publish(owner, CONTENT);
      var service = fixture.authority.indexing();
      var base = active(fixture.authority.store(), original.documentId());
      var first = service.createReindexing(owner, original.documentId(), base, TARGET);
      var failed = service.claimIndexing(owner.workspaceId()).orElseThrow();
      assertTrue(service.failIndexing(failed, "indexing_failed"));
      var second = service.createReindexing(owner, original.documentId(), base, TARGET);
      assertNotEquals(first.taskId(), second.taskId());
      assertEquals(
          2,
          count("SELECT rebuild_sequence FROM indexing_jobs WHERE id='" + second.taskId() + "'"));
      assertEquals(
          "indexing_state_conflict",
          fails(409, () -> service.retryIndexing(owner, first.taskId(), TARGET)).code());
      assertEquals(1, service.indexingStatus(owner, first.taskId()).attempt());
      var live = service.claimIndexing(owner.workspaceId()).orElseThrow();
      assertEquals(second.taskId(), live.jobId());
      assertEquals(
          "indexing_state_conflict",
          fails(409, () -> service.retryIndexing(owner, first.taskId(), TARGET)).code());
      assertFalse(complete(service, failed));
      assertEquals(base, active(fixture.authority.store(), original.documentId()));
      assertTrue(complete(service, live));
      assertEquals(
          "indexing_state_conflict",
          fails(409, () -> service.retryIndexing(owner, first.taskId(), TARGET)).code());
      assertEquals(2, count("SELECT COUNT(*) FROM index_publications"));
      assertEquals("indexed", service.indexingStatus(owner, original.jobId()).state());
    }
  }

  @Test
  void cancellationFencesLateWritesAndRetryUsesANewGenerationAgainstTheSameBase() throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var original = fixture.publish(owner, CONTENT);
      var service = fixture.authority.indexing();
      var base = active(fixture.authority.store(), original.documentId());
      var created = service.createReindexing(owner, original.documentId(), base, TARGET);
      var first = service.claimIndexing(owner.workspaceId()).orElseThrow();
      assertEquals("cancelled", service.cancelIndexing(owner, created.taskId()).state());
      assertFalse(service.isIndexingClaimCurrent(first));
      assertFalse(complete(service, first));
      assertFalse(service.failIndexing(first, "indexing_failed"));
      assertEquals(base, active(fixture.authority.store(), original.documentId()));
      assertEquals(2, service.retryIndexing(owner, created.taskId(), TARGET).attempt());
      var second = service.claimIndexing(owner.workspaceId()).orElseThrow();
      assertNotEquals(first.projectionGenerationId(), second.projectionGenerationId());
      assertEquals(first.items(), second.items());
      fails(
          422,
          () -> service.completeIndexing(second, digests(first), receipt(first, digests(first))));
      assertTrue(service.isIndexingClaimCurrent(second));
      assertEquals(base, active(fixture.authority.store(), original.documentId()));
      assertTrue(complete(service, second));
      assertFalse(complete(service, first));
    }
  }

  @Test
  void incompleteOrMismatchedReceiptsCannotReplaceTheOldPublication() throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var original = fixture.publish(owner, CONTENT.repeat(300));
      var service = fixture.authority.indexing();
      var base = active(fixture.authority.store(), original.documentId());
      service.createReindexing(owner, original.documentId(), base, TARGET);
      var claim = service.claimIndexing(owner.workspaceId()).orElseThrow();
      var full = digests(claim);
      var partial = new LinkedHashMap<>(full);
      partial.remove(partial.keySet().iterator().next());
      assertTrue(full.size() > 1);
      fails(422, () -> service.completeIndexing(claim, partial, receipt(claim, partial)));
      fails(
          422,
          () ->
              service.completeIndexing(
                  claim,
                  full,
                  new VerifiedRevision(
                      "d".repeat(64), receipt(claim, full).manifestSha256(), full.size())));
      fails(422, () -> service.completeIndexing(claim, full, receipt(original, digests(original))));
      assertEquals(1, count("SELECT COUNT(*) FROM index_publications"));
      assertEquals(base, active(fixture.authority.store(), original.documentId()));
      assertTrue(service.isIndexingClaimCurrent(claim));
      assertTrue(complete(service, claim));
    }
  }

  @Test
  void failedPublicationAuditRollsBackBothThePointerAndTheOldRunningSynopsis() throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var original = fixture.publish(owner, CONTENT);
      var service = fixture.authority.indexing();
      var base = active(fixture.authority.store(), original.documentId());
      var summaries = summaries(fixture.authority.store(), "summary-v1");
      var synopsis = summaries.create(owner, original.documentId());
      var synopsisClaim = summaries.claim(owner.workspaceId()).orElseThrow();
      service.createReindexing(owner, original.documentId(), base, TARGET);
      var claim = service.claimIndexing(owner.workspaceId()).orElseThrow();
      sql(
          "CREATE TRIGGER reindex_fixture_audit_failure BEFORE INSERT ON management_audit WHEN NEW.action='indexing_published' BEGIN SELECT RAISE(ABORT,'synthetic audit failure'); END");
      fails(503, () -> complete(service, claim));
      assertEquals(base, active(fixture.authority.store(), original.documentId()));
      assertEquals(1, count("SELECT COUNT(*) FROM index_publications"));
      assertTrue(service.isIndexingClaimCurrent(claim));
      assertEquals("processing", summaries.task(owner, synopsis.taskId()).state());
      assertTrue(summaries.current(synopsisClaim));
      sql("DROP TRIGGER reindex_fixture_audit_failure");
      assertTrue(complete(service, claim));
      assertEquals(
          "unavailable",
          text("SELECT state FROM synopsis_tasks WHERE id='" + synopsis.taskId() + "'"));
      assertEquals(
          "source_changed",
          text("SELECT error_code FROM synopsis_tasks WHERE id='" + synopsis.taskId() + "'"));
      fails(404, () -> summaries.task(owner, synopsis.taskId()));
      assertNull(
          text(
              "SELECT claim_token_sha256 FROM synopsis_tasks WHERE id='"
                  + synopsis.taskId()
                  + "'"));
      assertFalse(summaries.current(synopsisClaim));
      assertFalse(summaries.complete(synopsisClaim, synopsis(synopsisClaim)));
      assertEquals(0, count("SELECT COUNT(*) FROM synopsis_entries"));
    }
  }

  @Test
  void successfulReplacementOnlyEndsPendingSummariesAndRetainsCompletedSummaryAndTags()
      throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var original = fixture.publish(owner, CONTENT);
      var service = fixture.authority.indexing();
      var base = active(fixture.authority.store(), original.documentId());
      var summaries = summaries(fixture.authority.store(), "summary-v1");
      var saved = summaries.create(owner, original.documentId());
      var completeSummary = summaries.claim(owner.workspaceId()).orElseThrow();
      assertTrue(summaries.complete(completeSummary, synopsis(completeSummary)));
      fixture.authority.updateDocument(
          owner, original.documentId(), Map.of("tags", List.of("太阳能")));
      var otherProfile = summaries(fixture.authority.store(), "summary-v2");
      var queued = otherProfile.create(owner, original.documentId());
      var unrelated = fixture.publish(owner, "另一份资料采用风能。");
      var unrelatedSummary = otherProfile.create(owner, unrelated.documentId());
      var pending = service.createReindexing(owner, original.documentId(), base, TARGET);
      assertEquals("queued", otherProfile.task(owner, queued.taskId()).state());
      service.cancelIndexing(owner, pending.taskId());
      assertEquals("queued", otherProfile.task(owner, queued.taskId()).state());
      service.retryIndexing(owner, pending.taskId(), TARGET);
      assertTrue(complete(service, service.claimIndexing(owner.workspaceId()).orElseThrow()));
      assertEquals(
          "available", text("SELECT state FROM synopsis_tasks WHERE id='" + saved.taskId() + "'"));
      assertEquals(
          3, count("SELECT COUNT(*) FROM synopsis_entries WHERE task_id='" + saved.taskId() + "'"));
      assertEquals(
          "unavailable",
          text("SELECT state FROM synopsis_tasks WHERE id='" + queued.taskId() + "'"));
      assertEquals(
          "source_changed",
          text("SELECT error_code FROM synopsis_tasks WHERE id='" + queued.taskId() + "'"));
      fails(404, () -> otherProfile.task(owner, queued.taskId()));
      assertEquals("queued", otherProfile.task(owner, unrelatedSummary.taskId()).state());
      assertEquals(
          List.of("太阳能"),
          fixture
              .authority
              .management()
              .listDocuments(
                  owner, com.evidence.rag.web.converter.ManagementRequestMapper.query(Map.of()))
              .items()
              .stream()
              .filter(document -> document.documentId().equals(original.documentId()))
              .findFirst()
              .orElseThrow()
              .tags());
    }
  }

  @Test
  void restartFailsTheAbandonedAttemptWithoutReplacingTheOldPublicationAndRetryCanFinish()
      throws Exception {
    IndexClaim abandoned;
    String base;
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var original = fixture.publish(owner, CONTENT);
      base = active(fixture.authority.store(), original.documentId());
      fixture.authority.indexing().createReindexing(owner, original.documentId(), base, TARGET);
      abandoned = fixture.authority.claimIndexing(owner.workspaceId()).orElseThrow();
    }
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var service = fixture.authority.indexing();
      assertEquals("failed", service.indexingStatus(owner, abandoned.jobId()).state());
      assertEquals(
          "worker_interrupted", service.indexingStatus(owner, abandoned.jobId()).errorCode());
      assertEquals(base, active(fixture.authority.store(), abandoned.documentId()));
      assertFalse(complete(service, abandoned));
      assertEquals(2, service.retryIndexing(owner, abandoned.jobId(), TARGET).attempt());
      var next = service.claimIndexing(owner.workspaceId()).orElseThrow();
      assertNotEquals(abandoned.projectionGenerationId(), next.projectionGenerationId());
      assertTrue(complete(service, next));
      assertEquals(2, count("SELECT COUNT(*) FROM index_publications"));
    }
  }

  @Test
  void completeSourceBytesAreCheckedBeforeCreatingARebuildEvenWhenAllCountsStillMatch()
      throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var original = fixture.publish(owner, CONTENT);
      var service = fixture.authority.indexing();
      var base = active(fixture.authority.store(), original.documentId());
      corruptOriginal(original.documentId());
      assertEquals(
          "indexing_state_conflict",
          fails(409, () -> service.createReindexing(owner, original.documentId(), base, TARGET))
              .code());
      assertEquals(1, count("SELECT COUNT(*) FROM indexing_jobs"));
      assertEquals(base, active(fixture.authority.store(), original.documentId()));
    }
  }

  @Test
  void aChangedSourceAfterClaimCannotPublishEvenWithACompleteValidProjectionReceipt()
      throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var original = fixture.publish(owner, CONTENT);
      var service = fixture.authority.indexing();
      var base = active(fixture.authority.store(), original.documentId());
      service.createReindexing(owner, original.documentId(), base, TARGET);
      var claim = service.claimIndexing(owner.workspaceId()).orElseThrow();
      corruptOriginal(original.documentId());
      assertFalse(service.isIndexingClaimCurrent(claim));
      assertFalse(complete(service, claim));
      assertEquals(base, active(fixture.authority.store(), original.documentId()));
      assertEquals(1, count("SELECT COUNT(*) FROM index_publications"));
    }
  }

  @Test
  void metadataOrganizationDoesNotChangeSavedEvidenceOrInvalidateTheRebuild() throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var original = fixture.publish(owner, CONTENT);
      var service = fixture.authority.indexing();
      var base = active(fixture.authority.store(), original.documentId());
      service.createReindexing(owner, original.documentId(), base, TARGET);
      var claim = service.claimIndexing(owner.workspaceId()).orElseThrow();
      fixture.authority.updateDocument(
          owner, original.documentId(), Map.of("display_name", "整理后的标题", "tags", List.of("整理")));
      assertTrue(service.isIndexingClaimCurrent(claim));
      assertTrue(complete(service, claim));
      assertEquals(original.items(), claim.items());
      assertArrayEquals(
          CONTENT.getBytes(StandardCharsets.UTF_8),
          fixture
              .authority
              .management()
              .documentContent(owner, original.documentId(), original.revisionId())
              .content());
    }
  }

  @Test
  void invalidIdentityOrUnpublishedSavedSourceDoesNotAllocateARebuild() throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var service = fixture.authority.indexing();
      var uploaded =
          fixture
              .authority
              .ingestion()
              .uploadDocument(
                  owner, "draft.txt", "text/plain", CONTENT.getBytes(StandardCharsets.UTF_8));
      for (String base : new String[] {null, "", "x".repeat(101), "bad\nidentity"}) {
        fails(422, () -> service.createReindexing(owner, uploaded.documentId(), base, TARGET));
      }
      fails(422, () -> service.createReindexing(null, uploaded.documentId(), "base", TARGET));
      fails(422, () -> service.createReindexing(owner, uploaded.documentId(), "base", null));
      assertEquals(
          "indexing_state_conflict",
          fails(409, () -> service.createReindexing(owner, uploaded.documentId(), "base", TARGET))
              .code());
      assertEquals(0, count("SELECT COUNT(*) FROM indexing_jobs"));
    }
  }

  private static String active(SqliteAuthorityStore store, String documentId) {
    return store.transaction(
        () -> new IndexingRepository(store).activePublication(documentId).orElseThrow().id());
  }

  private static Map<String, String> digests(IndexClaim claim) {
    var result = new LinkedHashMap<String, String>();
    for (var item : claim.items()) {
      result.put(
          RetrievalProjection.physicalSegmentId(claim.projectionGenerationId(), item.evidenceId()),
          "a".repeat(64));
    }
    return result;
  }

  private static VerifiedRevision receipt(IndexClaim claim, Map<String, String> digests) {
    return new VerifiedRevision(
        claim.target().projectionIdentity(),
        new RetrievalProjection.RevisionManifest(
                claim.workspaceId(), claim.documentId(), claim.projectionGenerationId(), digests)
            .sha256(),
        digests.size());
  }

  private static boolean complete(IndexingService service, IndexClaim claim) {
    var digests = digests(claim);
    return service.completeIndexing(claim, digests, receipt(claim, digests));
  }

  private static SynopsisLibraryService summaries(SqliteAuthorityStore store, String revision) {
    return new SynopsisLibraryService(
        store,
        new SynopsisRepository(store),
        new SynopsisMaterialRepository(store),
        new ManagementRepository(store),
        new DocumentPermissionPolicy(),
        () -> revision);
  }

  private static FileSynopsis synopsis(SynopsisClaim claim) {
    var source = claim.input().evidence().getFirst();
    var reference =
        new FileSynopsis.Reference(source.id(), source.sha256(), source.kind(), source.time());
    var entries =
        List.of(
                SynopsisDraft.Section.OVERVIEW,
                SynopsisDraft.Section.TOPIC,
                SynopsisDraft.Section.TERM)
            .stream()
            .map(
                section ->
                    new FileSynopsis.Entry(
                        new SynopsisDraft.Item(section, CONTENT, List.of(source.id())),
                        List.of(reference),
                        null))
            .toList();
    return new FileSynopsis(
        claim.publication(),
        claim.input().fingerprint(),
        claim.modelRevision(),
        claim.policyRevision(),
        entries,
        null);
  }

  private void corruptOriginal(String documentId) throws SQLException {
    String update =
        "UPDATE corpus_documents SET original_blob=x'00010203' WHERE document_id='"
            + documentId
            + "'";
    assertThrows(SQLException.class, () -> sql(update));
    // Explicit offline corruption of this synthetic database only; normal guards are asserted
    // above.
    sql("DROP TRIGGER corpus_source_identity");
    sql("DROP TRIGGER cleanup_corpus_documents_purge");
    sql(update);
  }

  private static ApplicationException fails(int status, Runnable action) {
    var problem = assertThrows(ApplicationException.class, action::run);
    assertEquals(status, HttpProblemMapper.status(problem));
    return problem;
  }

  private void sql(String sql) throws SQLException {
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private int count(String sql) throws SQLException {
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement();
        var result = statement.executeQuery(sql)) {
      assertTrue(result.next());
      return result.getInt(1);
    }
  }

  private String text(String sql) throws SQLException {
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement();
        var result = statement.executeQuery(sql)) {
      assertTrue(result.next());
      return result.getString(1);
    }
  }
}
