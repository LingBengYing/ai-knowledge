package com.evidence.rag.service;

import static com.evidence.rag.support.PublishedCorpusFixture.TARGET;
import static com.evidence.rag.support.PublishedCorpusFixture.physicalIds;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AnswerEligibility;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.TraceDraft;
import com.evidence.rag.model.domain.TraceEvidence;
import com.evidence.rag.support.PublishedCorpusFixture;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvidenceServiceTest {
  @TempDir Path directory;
  private final Actor owner = new Actor("org", "owner");
  private static final String TEXT = "😀退货政策：签收后14天内可以退货。";

  @Test
  void allUsesOnlyCurrentAuthorizedPublicationsAndGenerationNotSourceRevision() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var visible = fixture.publish(owner, TEXT);
      fixture.publish(new Actor("other", "owner"), "Foreign corpus.");
      fixture.publish(new Actor("org", "someone-else"), "Private corpus.");
      fixture
          .authority
          .ingestion()
          .uploadDocument(owner, "queued.txt", "text/plain", new byte[] {65});
      var scope = fixture.evidence.snapshot(owner, DocumentSelection.allDocuments(), TARGET);
      assertEquals(1, scope.publications().size());
      var binding = scope.publications().getFirst();
      assertEquals(visible.documentId(), binding.documentId());
      assertEquals(visible.revisionId(), binding.sourceRevisionId());
      assertEquals(visible.projectionGenerationId(), binding.projectionGenerationId());
      assertNotEquals(binding.sourceRevisionId(), binding.projectionGenerationId());
      assertEquals(TARGET, binding.target());
    }
  }

  @Test
  void selectedSetFailsWholeForMissingPrivateUnpublishedOrTargetMismatch() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var visible = fixture.publish(owner, TEXT);
      var privateDoc = fixture.publish(new Actor("org", "someone-else"), "Private.");
      var unpublished =
          fixture
              .authority
              .ingestion()
              .uploadDocument(owner, "queued.txt", "text/plain", new byte[] {65});
      for (String unavailable :
          List.of("missing", privateDoc.documentId(), unpublished.documentId())) {
        fails(
            FailureKind.NOT_FOUND,
            () ->
                fixture.evidence.snapshot(
                    owner,
                    DocumentSelection.selected(List.of(visible.documentId(), unavailable)),
                    TARGET));
      }
      fails(
          FailureKind.NOT_FOUND,
          () ->
              fixture.evidence.snapshot(
                  owner,
                  DocumentSelection.selected(List.of(visible.documentId())),
                  new IndexTarget(
                      "other-embed", TARGET.projectionIdentity(), TARGET.modelRevision(), 2)));
    }
  }

  @Test
  void explicitEmptyNeverBecomesAllAndPersistsEmptyScopeRefusal() throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      fixture.publish(owner, TEXT);
      var scope = fixture.evidence.snapshot(owner, DocumentSelection.selected(List.of()), TARGET);
      assertFalse(scope.selection().all());
      assertTrue(scope.publications().isEmpty());
      var receipt =
          fixture.evidence.finish(
              scope, abstained("empty_scope"), () -> AnswerEligibility.ELIGIBLE);
      assertEquals("abstained", receipt.outcome());
      assertEquals("empty_scope", receipt.reasonCode());
      assertEquals(1, scalar("SELECT COUNT(*) FROM query_traces"));
      assertEquals(0, scalar("SELECT COUNT(*) FROM query_trace_documents"));
      assertEquals(0, scalar("SELECT COUNT(*) FROM query_trace_evidence"));
    }
  }

  @Test
  void authorizedAllOverCapacityFailsInsteadOfTruncating() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      for (int index = 0; index < 129; index++) {
        fixture.publish(owner, "Published " + index);
      }
      fails(
          FailureKind.CAPACITY_EXCEEDED,
          () -> fixture.evidence.snapshot(owner, DocumentSelection.allDocuments(), TARGET));
    }
  }

  @Test
  void hydrateReturnsAuthoritativeUnicodeSourceAndPreservesCandidateOrder() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var first = fixture.publish(owner, TEXT);
      var second = fixture.publish(owner, "Other policy.");
      var scope = fixture.evidence.snapshot(owner, DocumentSelection.allDocuments(), TARGET);
      var ids = List.of(physicalIds(second).getFirst(), physicalIds(first).getFirst());
      var evidence = fixture.evidence.hydrate(scope, ids);
      assertEquals(ids, evidence.stream().map(item -> item.physicalSegmentId()).toList());
      assertEquals(TEXT, evidence.get(1).segment().text());
      assertEquals(TEXT.codePointCount(0, TEXT.length()), evidence.get(1).segment().end());
      assertEquals(hash(TEXT), evidence.get(1).pageSha256());
      assertFalse(evidence.toString().contains(TEXT));
      assertFalse(evidence.toString().contains("fixture.txt"));
    }
  }

  @Test
  void hydrateRejectsDuplicateUnknownAndOutOfScopePhysicalIds() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var first = fixture.publish(owner, TEXT);
      var second = fixture.publish(owner, "Other.");
      var scope =
          fixture.evidence.snapshot(
              owner, DocumentSelection.selected(List.of(first.documentId())), TARGET);
      String id = physicalIds(first).getFirst();
      fails(FailureKind.INVALID_INPUT, () -> fixture.evidence.hydrate(scope, List.of(id, id)));
      fails(
          FailureKind.INVALID_INPUT,
          () -> fixture.evidence.hydrate(scope, List.of("seg-" + "f".repeat(64))));
      fails(FailureKind.INVALID_INPUT, () -> fixture.evidence.hydrate(scope, physicalIds(second)));
      fails(
          FailureKind.INVALID_INPUT,
          () -> fixture.evidence.hydrate(scope, List.of(first.segments().getFirst().segmentId())));
    }
  }

  @Test
  void noncandidateRevocationInvalidatesHydrationAndAtomicFinalAnswer() throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var candidate = fixture.publish(owner, TEXT);
      var noncandidate = fixture.publish(owner, "Other scope member.");
      var scope = fixture.evidence.snapshot(owner, DocumentSelection.allDocuments(), TARGET);
      fixture.evidence.hydrate(scope, physicalIds(candidate));
      revoke(noncandidate.documentId());
      assertEquals(
          "scope_changed",
          fails(FailureKind.CONFLICT, () -> fixture.evidence.hydrate(scope, physicalIds(candidate)))
              .code());
      var receipt =
          fixture.evidence.finish(
              scope, answered(physicalIds(candidate).getFirst()), () -> AnswerEligibility.ELIGIBLE);
      assertEquals("abstained", receipt.outcome());
      assertEquals("scope_changed", receipt.reasonCode());
      assertEquals(2, scalar("SELECT COUNT(*) FROM query_trace_documents"));
      assertEquals(0, scalar("SELECT COUNT(*) FROM query_trace_evidence"));
      assertEquals(1, scalar("SELECT COUNT(*) FROM query_traces WHERE answer_sha256 IS NULL"));
    }
  }

  @Test
  void renameAndNewPublicationDoNotAlterFrozenAllScope() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var candidate = fixture.publish(owner, TEXT);
      var scope = fixture.evidence.snapshot(owner, DocumentSelection.allDocuments(), TARGET);
      fixture.authority.updateDocument(
          owner, candidate.documentId(), Map.of("display_name", "Friendly title"));
      fixture.publish(owner, "Published after the query started.");
      var hydrated = fixture.evidence.hydrate(scope, physicalIds(candidate));
      assertEquals(1, scope.publications().size());
      assertEquals("fixture.txt", hydrated.getFirst().filename());
      assertEquals(
          "answered",
          fixture
              .evidence
              .finish(
                  scope,
                  answered(physicalIds(candidate).getFirst()),
                  () -> AnswerEligibility.ELIGIBLE)
              .outcome());
    }
  }

  @Test
  void answeredTraceRestoresExactQuoteAndOnlyOriginalActorMayReadIt() throws Exception {
    String traceId;
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var claim = fixture.publish(owner, TEXT);
      var scope = fixture.evidence.snapshot(owner, DocumentSelection.allDocuments(), TARGET);
      var receipt =
          fixture.evidence.finish(
              scope, answered(physicalIds(claim).getFirst()), () -> AnswerEligibility.ELIGIBLE);
      traceId = receipt.traceId();
      assertEquals("answered", receipt.outcome());
      assertNull(receipt.reasonCode());
      var source = fixture.evidence.source(owner, traceId, 1);
      assertEquals(1, source.start());
      assertEquals(5, source.end());
      assertEquals(TEXT, source.evidence().page().text());
      sql(
          "INSERT INTO document_acl(document_id,principal_id,role) VALUES('"
              + claim.documentId()
              + "','reader','reader')");
      fails(
          FailureKind.NOT_FOUND,
          () -> fixture.evidence.source(new Actor("org", "reader"), receipt.traceId(), 1));
      fails(
          FailureKind.NOT_FOUND,
          () -> fixture.evidence.source(new Actor("other", "owner"), receipt.traceId(), 1));
      fails(FailureKind.NOT_FOUND, () -> fixture.evidence.source(owner, receipt.traceId(), 2));
      assertEquals(1, scalar("SELECT COUNT(*) FROM query_trace_documents"));
      assertEquals(1, scalar("SELECT COUNT(*) FROM query_trace_evidence"));
      assertEquals(hash("question"), text("SELECT question_sha256 FROM query_traces"));
      assertEquals(hash("answer"), text("SELECT answer_sha256 FROM query_traces"));
      assertFalse(
          text("SELECT sql FROM sqlite_master WHERE name='query_traces'")
              .contains("question_text"));
    }
    try (var reopened = new PublishedCorpusFixture(directory)) {
      assertEquals(1, reopened.evidence.source(owner, traceId, 1).start());
    }
  }

  @Test
  void sourceRevalidatesWholeOriginalScopeIncludingNoncitedDocuments() throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var claim = fixture.publish(owner, TEXT);
      var noncandidate = fixture.publish(owner, "Not cited.");
      var scope = fixture.evidence.snapshot(owner, DocumentSelection.allDocuments(), TARGET);
      var receipt =
          fixture.evidence.finish(
              scope, answered(physicalIds(claim).getFirst()), () -> AnswerEligibility.ELIGIBLE);
      revoke(noncandidate.documentId());
      fails(FailureKind.NOT_FOUND, () -> fixture.evidence.source(owner, receipt.traceId(), 1));
    }
  }

  @Test
  void finalRejectsForgedEvidenceAndOutOfSegmentLocatorWithoutTrace() throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var claim = fixture.publish(owner, TEXT);
      var scope = fixture.evidence.snapshot(owner, DocumentSelection.allDocuments(), TARGET);
      fails(
          FailureKind.INVALID_INPUT,
          () ->
              fixture.evidence.finish(
                  scope, answered("seg-" + "f".repeat(64)), () -> AnswerEligibility.ELIGIBLE));
      var invalidLocator =
          new TraceDraft(
              hash("question"),
              hash("answer"),
              "answered",
              null,
              "text-models-v1",
              "extract-v1",
              "support-v1",
              List.of(
                  new TraceEvidence(
                      1,
                      physicalIds(claim).getFirst(),
                      100,
                      101,
                      0.8,
                      -15.0,
                      List.of(hash("fact")))));
      fails(
          FailureKind.INVALID_INPUT,
          () -> fixture.evidence.finish(scope, invalidLocator, () -> AnswerEligibility.ELIGIBLE));
      assertEquals(0, scalar("SELECT COUNT(*) FROM query_traces"));
    }
  }

  @Test
  void traceInsertFailureRollsBackScopeAndEvidenceAndNeverReturnsSuccess() throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var claim = fixture.publish(owner, TEXT);
      var scope = fixture.evidence.snapshot(owner, DocumentSelection.allDocuments(), TARGET);
      sql(
          "CREATE TRIGGER test_trace_failure BEFORE INSERT ON query_traces BEGIN SELECT RAISE(ABORT,'test-only'); END");
      var problem =
          fails(
              FailureKind.UNAVAILABLE,
              () ->
                  fixture.evidence.finish(
                      scope,
                      answered(physicalIds(claim).getFirst()),
                      () -> AnswerEligibility.ELIGIBLE));
      assertFalse(problem.toString().contains("test-only"));
      assertEquals(0, scalar("SELECT COUNT(*) FROM query_traces"));
      assertEquals(0, scalar("SELECT COUNT(*) FROM query_trace_documents"));
      assertEquals(0, scalar("SELECT COUNT(*) FROM query_trace_evidence"));
      sql("DROP TRIGGER test_trace_failure");
      assertEquals(
          "answered",
          fixture
              .evidence
              .finish(
                  scope, answered(physicalIds(claim).getFirst()), () -> AnswerEligibility.ELIGIBLE)
              .outcome());
    }
  }

  @Test
  void traceAndChildrenAreAppendOnlyAndSealedAgainstLateAdditions() throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var claim = fixture.publish(owner, TEXT);
      var scope = fixture.evidence.snapshot(owner, DocumentSelection.allDocuments(), TARGET);
      fixture.evidence.finish(
          scope, answered(physicalIds(claim).getFirst()), () -> AnswerEligibility.ELIGIBLE);
      for (String table :
          List.of("query_traces", "query_trace_documents", "query_trace_evidence")) {
        assertThrows(SQLException.class, () -> sql("DELETE FROM " + table));
      }
      assertThrows(SQLException.class, () -> sql("UPDATE query_traces SET reason_code='changed'"));
      assertThrows(SQLException.class, () -> sql("UPDATE query_trace_documents SET ordinal=99"));
      assertThrows(SQLException.class, () -> sql("UPDATE query_trace_evidence SET start_offset=0"));
      assertThrows(
          SQLException.class,
          () ->
              sql(
                  "INSERT INTO query_trace_documents(trace_id,ordinal,publication_id) SELECT trace_id,99,publication_id FROM query_trace_documents"));
      assertEquals(1, scalar("SELECT COUNT(*) FROM query_trace_evidence"));
    }
  }

  @Test
  void deadlineIsCheckedAfterAcquiringStoreMonitorAndDropsAnswerEvidence() throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var claim = fixture.publish(owner, TEXT);
      var scope = fixture.evidence.snapshot(owner, DocumentSelection.allDocuments(), TARGET);
      var started = new CountDownLatch(1);
      var allowed = new AtomicBoolean(true);
      var outcome = new AtomicReference<String>();
      var failure = new AtomicReference<Throwable>();
      Thread worker;
      synchronized (fixture.authority.store()) {
        worker =
            Thread.ofVirtual()
                .start(
                    () -> {
                      started.countDown();
                      try {
                        outcome.set(
                            fixture
                                .evidence
                                .finish(
                                    scope,
                                    answered(physicalIds(claim).getFirst()),
                                    () ->
                                        allowed.get()
                                            ? AnswerEligibility.ELIGIBLE
                                            : AnswerEligibility.PROCESSING_TIMEOUT)
                                .reasonCode());
                      } catch (Throwable error) {
                        failure.set(error);
                      }
                    });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        allowed.set(false);
      }
      worker.join(5000);
      assertFalse(worker.isAlive());
      assertNull(failure.get());
      assertEquals("processing_timeout", outcome.get());
      assertEquals(0, scalar("SELECT COUNT(*) FROM query_trace_evidence"));
      assertEquals(1, scalar("SELECT COUNT(*) FROM query_traces WHERE answer_sha256 IS NULL"));
    }
  }

  @Test
  void initialConfigurationRejectionCannotRecoverIntoAnsweredAtFinalCheck() throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var claim = fixture.publish(owner, TEXT);
      var scope = fixture.evidence.snapshot(owner, DocumentSelection.allDocuments(), TARGET);
      var checks = new AtomicInteger();
      var receipt =
          fixture.evidence.finish(
              scope,
              answered(physicalIds(claim).getFirst()),
              () ->
                  checks.incrementAndGet() == 1
                      ? AnswerEligibility.CONFIGURATION_CHANGED
                      : AnswerEligibility.ELIGIBLE);
      assertEquals(2, checks.get());
      assertEquals("abstained", receipt.outcome());
      assertEquals("configuration_changed", receipt.reasonCode());
      assertEquals(1, scalar("SELECT COUNT(*) FROM query_trace_documents"));
      assertEquals(0, scalar("SELECT COUNT(*) FROM query_trace_evidence"));
      assertNull(text("SELECT answer_sha256 FROM query_traces"));
    }
  }

  @Test
  void finalConfigurationChangeClearsAlreadyValidatedAnswerEvidence() throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var claim = fixture.publish(owner, TEXT);
      var scope = fixture.evidence.snapshot(owner, DocumentSelection.allDocuments(), TARGET);
      var checks = new AtomicInteger();
      var receipt =
          fixture.evidence.finish(
              scope,
              answered(physicalIds(claim).getFirst()),
              () ->
                  checks.incrementAndGet() == 1
                      ? AnswerEligibility.ELIGIBLE
                      : AnswerEligibility.CONFIGURATION_CHANGED);
      assertEquals(2, checks.get());
      assertEquals("abstained", receipt.outcome());
      assertEquals("configuration_changed", receipt.reasonCode());
      assertEquals(1, scalar("SELECT COUNT(*) FROM query_trace_documents"));
      assertEquals(0, scalar("SELECT COUNT(*) FROM query_trace_evidence"));
      assertNull(text("SELECT answer_sha256 FROM query_traces"));
    }
  }

  @Test
  void nullEligibilityAtEitherCheckRollsBackEntireTrace() throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var claim = fixture.publish(owner, TEXT);
      var scope = fixture.evidence.snapshot(owner, DocumentSelection.allDocuments(), TARGET);
      for (int nullCheck : List.of(1, 2)) {
        var checks = new AtomicInteger();
        fails(
            FailureKind.INVALID_INPUT,
            () ->
                fixture.evidence.finish(
                    scope,
                    answered(physicalIds(claim).getFirst()),
                    () ->
                        checks.incrementAndGet() == nullCheck ? null : AnswerEligibility.ELIGIBLE));
        assertEquals(nullCheck, checks.get());
        assertEquals(0, scalar("SELECT COUNT(*) FROM query_traces"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM query_trace_documents"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM query_trace_evidence"));
      }
    }
  }

  private TraceDraft answered(String physicalId) {
    return new TraceDraft(
        hash("question"),
        hash("answer"),
        "answered",
        null,
        "text-models-v1",
        "extract-v1",
        "support-v1",
        List.of(new TraceEvidence(1, physicalId, 1, 5, 0.8, -15.0, List.of(hash("fact")))));
  }

  private TraceDraft abstained(String reason) {
    return new TraceDraft(
        hash("question"),
        null,
        "abstained",
        reason,
        "text-models-v1",
        "extract-v1",
        "support-v1",
        List.of());
  }

  private static String hash(String text) {
    return ModelValues.sha256(text.getBytes(StandardCharsets.UTF_8));
  }

  private static ApplicationException fails(FailureKind expected, Runnable action) {
    var error = assertThrows(ApplicationException.class, action::run);
    assertEquals(expected, error.kind());
    return error;
  }

  private void revoke(String documentId) throws SQLException {
    try (var db =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement =
            db.prepareStatement(
                "DELETE FROM document_acl WHERE document_id=? AND principal_id=?")) {
      statement.setString(1, documentId);
      statement.setString(2, owner.principalId());
      assertEquals(1, statement.executeUpdate());
    }
  }

  private void sql(String command) throws SQLException {
    try (var db =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = db.createStatement()) {
      statement.execute("PRAGMA foreign_keys=ON");
      statement.execute(command);
    }
  }

  private int scalar(String command) throws SQLException {
    return Integer.parseInt(text(command));
  }

  private String text(String command) throws SQLException {
    try (var db =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = db.createStatement();
        var result = statement.executeQuery(command)) {
      assertTrue(result.next());
      return result.getString(1);
    }
  }
}
