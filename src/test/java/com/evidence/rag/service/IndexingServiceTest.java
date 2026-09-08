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
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.IngestionClaim;
import com.evidence.rag.model.domain.ParsedText;
import com.evidence.rag.model.domain.SyntheticDocument;
import com.evidence.rag.model.domain.TextPage;
import com.evidence.rag.model.domain.TextSegment;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.web.HttpProblemMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexingServiceTest {
  @TempDir Path directory;
  private final Actor owner = new Actor("org", "owner");
  private final Actor reader = new Actor("org", "reader");
  private final IndexTarget target =
      new IndexTarget("embedding-identity", "b".repeat(64), "model-v1", 2);

  @Test
  void onlyFullVerifiedRevisionPublishesAndMetadataKeepsEvidenceIdentity() {
    String document;
    String publication;
    try (var authority = new AuthorityTestContext(directory)) {
      var parsed = parsed(authority);
      document = parsed.documentId();
      var before = first(authority.listDocuments(owner, Map.of()));
      assertTrue(before.containsKey("registered_revision_id"));
      assertNull(before.get("registered_revision_id"));
      assertEquals("not_indexed", before.get("index_status"));
      assertEquals(true, before.get("can_index"));
      var job = authority.createIndexing(owner, document, target);
      assertEquals("queued", job.get("state"));
      var claim = authority.claimIndexing(owner.workspaceId()).orElseThrow();
      assertEquals(parsed.revisionId(), claim.revisionId());
      assertEquals(2, claim.segments().size());
      assertTrue(authority.isIndexingClaimCurrent(claim));
      assertTrue(authority.claimIndexing(owner.workspaceId()).isEmpty());
      assertNull(first(authority.listDocuments(owner, Map.of())).get("active_revision_id"));
      var digests = digests(claim);
      assertTrue(authority.completeIndexing(claim, digests, receipt(claim, digests)));
      assertFalse(authority.completeIndexing(claim, digests, receipt(claim, digests)));
      var view = first(authority.listDocuments(owner, Map.of()));
      assertEquals("parsed", view.get("status"));
      assertEquals("indexed", view.get("index_status"));
      assertEquals(claim.revisionId(), view.get("active_revision_id"));
      assertNull(view.get("registered_revision_id"));
      assertEquals(false, view.get("can_answer"));
      assertEquals(false, view.get("can_index"));
      publication = (String) view.get("index_publication_id");
      assertNotNull(publication);
      assertEquals("parsed", authority.ingestionStatus(owner, parsed.jobId()).get("state"));
      fails(409, () -> authority.createIndexing(owner, claim.documentId(), target));
      fails(409, () -> authority.cancelIndexing(owner, claim.jobId()));
      fails(409, () -> authority.retryIndexing(owner, claim.jobId(), target));
      var evidence = authority.parsedEvidence(owner, document);
      var edited =
          authority.updateDocument(
              owner, document, Map.of("display_name", "整理标题", "tags", List.of("tag")));
      assertEquals("original.txt", edited.get("filename"));
      assertEquals(view.get("media_info"), edited.get("media_info"));
      assertEquals(publication, edited.get("index_publication_id"));
      assertEquals(evidence, authority.parsedEvidence(owner, document));
      assertTrue(authority.claimIndexing(owner.workspaceId()).isEmpty());
      var audit = authority.auditEvents(new Actor("org", "system:indexing")).toString();
      assertTrue(audit.contains("indexing_published"));
      assertFalse(audit.contains("secret-policy"));
      assertFalse(audit.contains(claim.token()));
      assertFalse(claim.toString().contains(claim.token()));
      assertFalse(claim.segments().toString().contains("secret-policy"));
      assertFalse(job.toString().contains(target.embeddingIdentity()));
    }
    try (var reopened = new AuthorityTestContext(directory)) {
      var view = first(reopened.listDocuments(owner, Map.of()));
      assertEquals(publication, view.get("index_publication_id"));
      assertEquals("indexed", view.get("index_status"));
      assertNotNull(view.get("active_revision_id"));
      assertEquals(document, view.get("document_id"));
    }
  }

  @Test
  void syntheticRegistrationNeverAppearsAsPublishedActiveRevision() throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      authority.registerSyntheticDocument(
          owner,
          new SyntheticDocument(
              "synthetic", "demo.txt", "document", "text/plain", "registration", "a".repeat(64), 1),
          Map.of());
      var view = first(authority.listDocuments(owner, Map.of()));
      assertNull(view.get("active_revision_id"));
      assertEquals("registration", view.get("registered_revision_id"));
      assertNull(view.get("index_publication_id"));
      assertEquals("not_indexed", view.get("index_status"));
      assertEquals(false, view.get("can_index"));
      assertEquals(false, view.get("can_answer"));
      assertEquals(
          1,
          scalar(
              "SELECT COUNT(*) FROM documents WHERE id='synthetic' AND active_revision_id='registration'"));
      assertEquals(0, scalar("SELECT COUNT(*) FROM active_corpus_publications"));
      assertEquals(0, scalar("SELECT COUNT(*) FROM index_publications"));
    }
  }

  @Test
  void unparsedSyntheticUnknownAndReaderCannotCreateAndAclIsRechecked() throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      var unparsed = upload(authority);
      fails(
          409, () -> authority.createIndexing(owner, (String) unparsed.get("document_id"), target));
      authority.registerSyntheticDocument(
          owner,
          new SyntheticDocument(
              "synthetic", "demo.txt", "document", "text/plain", "registration", "a".repeat(64), 1),
          Map.of());
      fails(409, () -> authority.createIndexing(owner, "synthetic", target));
      fails(404, () -> authority.createIndexing(owner, "missing", target));
      var ingestion = authority.claimIngestion("org").orElseThrow();
      assertTrue(authority.completeIngestion(ingestion, evidence()));
      String doc = ingestion.documentId();
      sql("INSERT INTO document_acl VALUES('" + doc + "','reader','reader')");
      fails(404, () -> authority.createIndexing(reader, doc, target));
      fails(404, () -> authority.createIndexing(new Actor("other", "owner"), doc, target));
      var job = authority.createIndexing(owner, doc, target);
      String id = (String) job.get("task_id");
      fails(409, () -> authority.createIndexing(owner, doc, target));
      assertEquals(false, authority.indexingStatus(reader, id).get("can_cancel"));
      fails(404, () -> authority.cancelIndexing(reader, id));
      fails(404, () -> authority.retryIndexing(reader, id, target));
      fails(404, () -> authority.indexingStatus(new Actor("other", "owner"), id));
      assertTrue(authority.claimIndexing("other").isEmpty());
      sql(
          "UPDATE document_acl SET role='reader' WHERE document_id='"
              + doc
              + "' AND principal_id='owner'");
      assertTrue(authority.claimIndexing("org").isEmpty());
      assertEquals("failed", authority.indexingStatus(owner, id).get("state"));
      sql(
          "UPDATE document_acl SET role='owner' WHERE document_id='"
              + doc
              + "' AND principal_id='owner'");
      authority.retryIndexing(owner, id, target);
      var claim = authority.claimIndexing("org").orElseThrow();
      sql("DELETE FROM document_acl WHERE document_id='" + doc + "' AND principal_id='owner'");
      assertFalse(authority.isIndexingClaimCurrent(claim));
      assertFalse(
          authority.completeIndexing(claim, digests(claim), receipt(claim, digests(claim))));
      fails(404, () -> authority.indexingStatus(owner, id));
      assertNull(first(authority.listDocuments(reader, Map.of())).get("active_revision_id"));
    }
  }

  @Test
  void partialExtraForgedManifestAndTargetNeverPublishAndAuditFailureRollsBack() throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      var parsed = parsed(authority);
      authority.createIndexing(owner, parsed.documentId(), target);
      var claim = authority.claimIndexing("org").orElseThrow();
      var digests = digests(claim);
      var partial = new LinkedHashMap<>(digests);
      partial.remove(
          RetrievalProjection.physicalSegmentId(
              claim.projectionGenerationId(), claim.segments().getFirst().segmentId()));
      fails(422, () -> authority.completeIndexing(claim, partial, receipt(claim, partial)));
      var extra = new LinkedHashMap<>(digests);
      extra.put("unexpected", "d".repeat(64));
      fails(422, () -> authority.completeIndexing(claim, extra, receipt(claim, extra)));
      fails(
          422,
          () ->
              authority.completeIndexing(
                  claim,
                  digests,
                  new VerifiedRevision(target.projectionIdentity(), "f".repeat(64), 2)));
      fails(
          422,
          () ->
              authority.completeIndexing(
                  claim,
                  digests,
                  new VerifiedRevision(
                      "c".repeat(64), receipt(claim, digests).manifestSha256(), 2)));
      fails(
          422,
          () ->
              authority.completeIndexing(
                  claim,
                  digests,
                  new VerifiedRevision(
                      target.projectionIdentity(), receipt(claim, digests).manifestSha256(), 1)));
      fails(422, () -> authority.completeIndexing(claim, null, null));
      assertTrue(authority.isIndexingClaimCurrent(claim));
      assertEquals(0, scalar("SELECT COUNT(*) FROM index_publications"));
      sql(
          "CREATE TRIGGER injected_index_audit_failure BEFORE INSERT ON management_audit BEGIN SELECT RAISE(ABORT,'secret_internal_failure'); END");
      assertFalse(
          fails(503, () -> authority.completeIndexing(claim, digests, receipt(claim, digests)))
              .getMessage()
              .contains("secret_internal_failure"));
      assertEquals(0, scalar("SELECT COUNT(*) FROM index_publications"));
      assertEquals(0, scalar("SELECT COUNT(*) FROM active_corpus_publications"));
      assertTrue(authority.isIndexingClaimCurrent(claim));
      sql("DROP TRIGGER injected_index_audit_failure");
      assertTrue(authority.completeIndexing(claim, digests, receipt(claim, digests)));
      for (String statement :
          List.of(
              "UPDATE index_publications SET manifest_sha256='forged'",
              "DELETE FROM index_publications",
              "UPDATE active_corpus_publications SET publication_id='forged'",
              "DELETE FROM active_corpus_publications",
              "UPDATE corpus_documents SET active_revision_id='forged'",
              "UPDATE corpus_segments SET text='forged'",
              "UPDATE indexing_jobs SET embedding_identity='forged'",
              "DELETE FROM indexing_jobs")) {
        assertThrows(SQLException.class, () -> sql(statement), statement);
      }
    }
  }

  @Test
  void cancelRetryAndRecoveryFenceAllOldAttemptsAndRequireSameTarget() {
    IndexClaim abandoned;
    String queued;
    try (var authority = new AuthorityTestContext(directory)) {
      var parsed = parsed(authority);
      authority.createIndexing(owner, parsed.documentId(), target);
      var old = authority.claimIndexing("org").orElseThrow();
      assertEquals("cancelled", authority.cancelIndexing(owner, old.jobId()).get("state"));
      assertFalse(authority.completeIndexing(old, digests(old), receipt(old, digests(old))));
      fails(
          409,
          () ->
              authority.retryIndexing(
                  owner,
                  old.jobId(),
                  new IndexTarget(
                      "changed", target.projectionIdentity(), target.modelRevision(), 2)));
      assertEquals(2, authority.retryIndexing(owner, old.jobId(), target).get("attempt"));
      abandoned = authority.claimIndexing("org").orElseThrow();
      assertFalse(authority.failIndexing(old, "indexing_failed"));
      var another = parsed(authority);
      queued =
          (String) authority.createIndexing(owner, another.documentId(), target).get("task_id");
      assertTrue(authority.claimIndexing("org").isEmpty());
    }
    try (var authority = new AuthorityTestContext(directory)) {
      assertEquals(
          "worker_interrupted",
          authority.indexingStatus(owner, abandoned.jobId()).get("error_code"));
      assertFalse(authority.isIndexingClaimCurrent(abandoned));
      assertEquals("queued", authority.indexingStatus(owner, queued).get("state"));
      authority.cancelIndexing(owner, queued);
      assertEquals(3, authority.retryIndexing(owner, abandoned.jobId(), target).get("attempt"));
      var finalClaim = authority.claimIndexing("org").orElseThrow();
      fails(422, () -> authority.failIndexing(finalClaim, "unsafe secret detail"));
      assertTrue(authority.failIndexing(finalClaim, "indexing_timeout"));
      assertEquals(false, authority.indexingStatus(owner, finalClaim.jobId()).get("can_retry"));
      assertEquals(
          "indexing_retry_limit",
          fails(409, () -> authority.retryIndexing(owner, finalClaim.jobId(), target)).code());
    }
  }

  @Test
  void everyClaimIdentitySnapshotAndAttemptAreValidatedAndDefensivelyCopied() {
    try (var authority = new AuthorityTestContext(directory)) {
      var parsed = parsed(authority);
      authority.createIndexing(owner, parsed.documentId(), target);
      var claim = authority.claimIndexing("org").orElseThrow();
      var original = new ArrayList<>(claim.segments());
      var copied =
          new IndexClaim(
              claim.jobId(),
              claim.documentId(),
              claim.revisionId(),
              claim.workspaceId(),
              claim.attempt(),
              claim.token(),
              claim.sourceSha256(),
              claim.parserRevision(),
              target,
              original,
              claim.projectionGenerationId());
      original.clear();
      assertEquals(2, copied.segments().size());
      assertThrows(UnsupportedOperationException.class, () -> copied.segments().clear());
      assertTrue(authority.isIndexingClaimCurrent(copied));
      for (String field :
          List.of(
              "job",
              "document",
              "revision",
              "workspace",
              "attempt",
              "token",
              "null-token",
              "short-token",
              "source",
              "parser",
              "target",
              "segments",
              "generation")) {
        var forged =
            new IndexClaim(
                field.equals("job") ? "missing" : claim.jobId(),
                field.equals("document") ? "another-document" : claim.documentId(),
                field.equals("revision") ? "another-revision" : claim.revisionId(),
                field.equals("workspace") ? "other" : claim.workspaceId(),
                field.equals("attempt") ? 2 : claim.attempt(),
                field.equals("token")
                    ? "x".repeat(72)
                    : field.equals("null-token")
                        ? null
                        : field.equals("short-token") ? "short" : claim.token(),
                field.equals("source") ? "f".repeat(64) : claim.sourceSha256(),
                field.equals("parser") ? "other-parser" : claim.parserRevision(),
                field.equals("target")
                    ? new IndexTarget(
                        "other",
                        target.projectionIdentity(),
                        target.modelRevision(),
                        target.dimensions())
                    : target,
                field.equals("segments") ? List.of(claim.segments().getFirst()) : claim.segments(),
                field.equals("generation") ? "another-generation" : claim.projectionGenerationId());
        assertFalse(authority.isIndexingClaimCurrent(forged), field);
        assertFalse(authority.failIndexing(forged, "indexing_failed"), field);
        assertFalse(
            authority.completeIndexing(forged, digests(claim), receipt(claim, digests(claim))),
            field);
      }
      assertFalse(authority.isIndexingClaimCurrent(null));
      assertFalse(authority.failIndexing(null, "indexing_failed"));
      assertFalse(authority.completeIndexing(null, null, null));
      fails(422, () -> authority.failIndexing(claim, null));
      var wrongDigest = digests(claim);
      wrongDigest.put(
          RetrievalProjection.physicalSegmentId(
              claim.projectionGenerationId(), claim.segments().getFirst().segmentId()),
          "private raw secret");
      fails(
          422,
          () -> authority.completeIndexing(claim, wrongDigest, receipt(claim, digests(claim))));
      assertTrue(authority.isIndexingClaimCurrent(claim));
      assertTrue(authority.completeIndexing(claim, digests(claim), receipt(claim, digests(claim))));
    }
  }

  @Test
  void invalidInternalInputsStaySafeAndDoNotCreateTasks() {
    for (String value : new String[] {null, "", "secret invalid identity", "x".repeat(129)}) {
      fails(422, () -> new IndexTarget(value, "projection", "revision", 2));
      fails(422, () -> new IndexTarget("embedding", value, "revision", 2));
    }
    for (String revision :
        new String[] {null, "", "latest", "DEFAULT", "unknown", "secret\nvalue", "r".repeat(161)}) {
      fails(422, () -> new IndexTarget("embedding", "projection", revision, 2));
    }
    fails(422, () -> new IndexTarget("embedding", "projection", "revision", 1));
    fails(422, () -> new IndexTarget("embedding", "projection", "revision", 8193));
    assertEquals("IndexTarget[redacted]", target.toString());
    try (var authority = new AuthorityTestContext(directory)) {
      var parsed = parsed(authority);
      fails(422, () -> authority.createIndexing(null, parsed.documentId(), target));
      fails(422, () -> authority.createIndexing(owner, parsed.documentId(), null));
      fails(422, () -> authority.createIndexing(owner, "", target));
      fails(422, () -> authority.indexingStatus(null, "missing"));
      fails(422, () -> authority.indexingStatus(owner, ""));
      fails(422, () -> authority.retryIndexing(owner, "missing", null));
      fails(404, () -> authority.indexingStatus(owner, "missing"));
      var job = authority.createIndexing(owner, parsed.documentId(), target);
      String id = (String) job.get("task_id");
      fails(409, () -> authority.retryIndexing(owner, id, target));
      assertEquals("cancelled", authority.cancelIndexing(owner, id).get("state"));
      fails(409, () -> authority.cancelIndexing(owner, id));
      for (var changed :
          List.of(
              new IndexTarget(
                  target.embeddingIdentity(), "c".repeat(64), target.modelRevision(), 2),
              new IndexTarget(
                  target.embeddingIdentity(), target.projectionIdentity(), "model-v2", 2),
              new IndexTarget(
                  target.embeddingIdentity(),
                  target.projectionIdentity(),
                  target.modelRevision(),
                  3))) {
        fails(409, () -> authority.retryIndexing(owner, id, changed));
      }
      authority.retryIndexing(owner, id, target);
      var claim = authority.claimIndexing("org").orElseThrow();
      fails(
          422,
          () ->
              new IndexClaim(
                  claim.jobId(),
                  claim.documentId(),
                  claim.revisionId(),
                  claim.workspaceId(),
                  1,
                  claim.token(),
                  claim.sourceSha256(),
                  claim.parserRevision(),
                  target,
                  null,
                  claim.projectionGenerationId()));
      fails(
          422,
          () ->
              new IndexClaim(
                  claim.jobId(),
                  claim.documentId(),
                  claim.revisionId(),
                  claim.workspaceId(),
                  1,
                  claim.token(),
                  claim.sourceSha256(),
                  claim.parserRevision(),
                  target,
                  List.of(),
                  claim.projectionGenerationId()));
      fails(
          422,
          () ->
              new IndexClaim(
                  claim.jobId(),
                  claim.documentId(),
                  claim.revisionId(),
                  claim.workspaceId(),
                  1,
                  claim.token(),
                  claim.sourceSha256(),
                  claim.parserRevision(),
                  null,
                  claim.segments(),
                  claim.projectionGenerationId()));
      assertTrue(authority.failIndexing(claim, "index_configuration_changed"));
      assertEquals(
          "index_configuration_changed", authority.indexingStatus(owner, id).get("error_code"));
    }
  }

  @Test
  void revokedCreatorCanBeFailedButCannotRetryThroughAnotherEditor() throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      var parsed = parsed(authority);
      var editor = new Actor("org", "editor");
      sql("INSERT INTO document_acl VALUES('" + parsed.documentId() + "','editor','editor')");
      authority.createIndexing(owner, parsed.documentId(), target);
      var claim = authority.claimIndexing("org").orElseThrow();
      sql(
          "UPDATE document_acl SET role='reader' WHERE document_id='"
              + parsed.documentId()
              + "' AND principal_id='owner'");
      assertTrue(authority.failIndexing(claim, "indexing_failed"));
      assertEquals(
          "authorization_changed",
          authority.indexingStatus(editor, claim.jobId()).get("error_code"));
      fails(409, () -> authority.retryIndexing(editor, claim.jobId(), target));
      assertNull(first(authority.listDocuments(editor, Map.of())).get("active_revision_id"));
    }
  }

  @Test
  void retryGenerationIsolatesLateWritesAndReceiptsWhileKeepingSourceIdentity() throws Exception {
    IndexClaim firstAttempt;
    IndexClaim nextAttempt;
    try (var authority = new AuthorityTestContext(directory)) {
      var parsed = parsed(authority);
      authority.createIndexing(owner, parsed.documentId(), target);
      firstAttempt = authority.claimIndexing("org").orElseThrow();
      var originalEvidence = authority.parsedEvidence(owner, parsed.documentId());
      authority.cancelIndexing(owner, firstAttempt.jobId());
      authority.retryIndexing(owner, firstAttempt.jobId(), target);
      nextAttempt = authority.claimIndexing("org").orElseThrow();
      assertNotEquals(firstAttempt.projectionGenerationId(), nextAttempt.projectionGenerationId());
      assertEquals(firstAttempt.revisionId(), nextAttempt.revisionId());
      assertEquals(firstAttempt.sourceSha256(), nextAttempt.sourceSha256());
      assertEquals(firstAttempt.segments(), nextAttempt.segments());
      assertEquals(originalEvidence, authority.parsedEvidence(owner, parsed.documentId()));
      var oldDigests = digests(firstAttempt);
      var newDigests = digests(nextAttempt);
      assertTrue(java.util.Collections.disjoint(oldDigests.keySet(), newDigests.keySet()));
      assertFalse(
          authority.completeIndexing(firstAttempt, oldDigests, receipt(firstAttempt, oldDigests)));
      fails(
          422,
          () ->
              authority.completeIndexing(
                  nextAttempt, oldDigests, receipt(firstAttempt, oldDigests)));
      fails(
          422,
          () ->
              authority.completeIndexing(
                  nextAttempt, newDigests, receipt(firstAttempt, newDigests)));
      var forgedGeneration =
          new IndexClaim(
              nextAttempt.jobId(),
              nextAttempt.documentId(),
              nextAttempt.revisionId(),
              nextAttempt.workspaceId(),
              nextAttempt.attempt(),
              nextAttempt.token(),
              nextAttempt.sourceSha256(),
              nextAttempt.parserRevision(),
              target,
              nextAttempt.segments(),
              firstAttempt.projectionGenerationId());
      assertFalse(authority.isIndexingClaimCurrent(forgedGeneration));
      assertFalse(
          authority.completeIndexing(
              forgedGeneration, newDigests, receipt(nextAttempt, newDigests)));
      assertEquals(2, scalar("SELECT COUNT(*) FROM indexing_attempts"));
      assertEquals(0, scalar("SELECT COUNT(*) FROM index_publications"));
      assertTrue(
          authority.completeIndexing(nextAttempt, newDigests, receipt(nextAttempt, newDigests)));
      var view = first(authority.listDocuments(owner, Map.of()));
      assertEquals(nextAttempt.revisionId(), view.get("active_revision_id"));
      assertEquals(
          1,
          scalar(
              "SELECT COUNT(*) FROM index_publications WHERE projection_generation_id='"
                  + nextAttempt.projectionGenerationId()
                  + "' AND revision_id='"
                  + nextAttempt.revisionId()
                  + "'"));
      assertEquals(2, scalar("SELECT COUNT(*) FROM index_publication_entries"));
      for (var segment : nextAttempt.segments()) {
        String physical =
            RetrievalProjection.physicalSegmentId(
                nextAttempt.projectionGenerationId(), segment.segmentId());
        assertEquals(
            1,
            scalar(
                "SELECT COUNT(*) FROM index_publication_entries WHERE source_segment_id='"
                    + segment.segmentId()
                    + "' AND physical_segment_id='"
                    + physical
                    + "' AND entry_sha256='"
                    + newDigests.get(physical)
                    + "'"));
      }
      for (String statement :
          List.of(
              "UPDATE indexing_attempts SET projection_generation_id='forged'",
              "DELETE FROM indexing_attempts",
              "UPDATE indexing_jobs SET projection_generation_id='forged'",
              "UPDATE index_publications SET projection_generation_id='forged'",
              "UPDATE index_publication_entries SET entry_sha256='forged'",
              "DELETE FROM index_publication_entries")) {
        assertThrows(SQLException.class, () -> sql(statement), statement);
      }
    }
    try (var authority = new AuthorityTestContext(directory)) {
      assertFalse(authority.isIndexingClaimCurrent(firstAttempt));
      assertEquals("indexed", authority.indexingStatus(owner, nextAttempt.jobId()).get("state"));
      assertEquals(
          nextAttempt.revisionId(),
          first(authority.listDocuments(owner, Map.of())).get("active_revision_id"));
      assertEquals(2, scalar("SELECT COUNT(*) FROM indexing_attempts"));
      assertEquals(2, scalar("SELECT COUNT(*) FROM index_publication_entries"));
    }
  }

  @Test
  void abandonedAttemptRetainsImmutableGenerationAndRetryClaimsFreshNamespace() throws Exception {
    IndexClaim old;
    try (var authority = new AuthorityTestContext(directory)) {
      var parsed = parsed(authority);
      authority.createIndexing(owner, parsed.documentId(), target);
      old = authority.claimIndexing("org").orElseThrow();
      assertEquals(36, old.projectionGenerationId().length());
      assertNotEquals(old.revisionId(), old.projectionGenerationId());
      assertEquals(
          1,
          scalar(
              "SELECT COUNT(*) FROM indexing_attempts WHERE projection_generation_id='"
                  + old.projectionGenerationId()
                  + "'"));
    }
    try (var authority = new AuthorityTestContext(directory)) {
      assertEquals(
          "worker_interrupted", authority.indexingStatus(owner, old.jobId()).get("error_code"));
      assertFalse(authority.isIndexingClaimCurrent(old));
      assertTrue(authority.claimIndexing("org").isEmpty());
      authority.retryIndexing(owner, old.jobId(), target);
      var next = authority.claimIndexing("org").orElseThrow();
      assertNotEquals(old.projectionGenerationId(), next.projectionGenerationId());
      assertEquals(old.segments(), next.segments());
      assertEquals(old.revisionId(), next.revisionId());
      assertEquals(2, scalar("SELECT COUNT(*) FROM indexing_attempts"));
      assertEquals(
          1,
          scalar(
              "SELECT COUNT(*) FROM indexing_attempts WHERE attempt=1 AND projection_generation_id='"
                  + old.projectionGenerationId()
                  + "'"));
      assertEquals(
          1,
          scalar(
              "SELECT COUNT(*) FROM indexing_attempts WHERE attempt=2 AND projection_generation_id='"
                  + next.projectionGenerationId()
                  + "'"));
      for (String statement :
          List.of(
              "UPDATE indexing_attempts SET projection_generation_id='forged'",
              "DELETE FROM indexing_attempts",
              "UPDATE indexing_jobs SET projection_generation_id='"
                  + old.projectionGenerationId()
                  + "'",
              "INSERT INTO indexing_attempts VALUES('"
                  + next.jobId()
                  + "',3,'00000000-0000-0000-0000-000000000003','synthetic-time')")) {
        assertThrows(SQLException.class, () -> sql(statement), statement);
      }
      assertTrue(authority.isIndexingClaimCurrent(next));
      assertFalse(
          authority
              .indexingStatus(owner, next.jobId())
              .toString()
              .contains(next.projectionGenerationId()));
      assertFalse(next.toString().contains(next.projectionGenerationId()));
    }
  }

  private Map<String, Object> upload(AuthorityTestContext authority) {
    return authority.uploadDocument(
        owner, "original.txt", "text/plain", "secret-policy二".getBytes(StandardCharsets.UTF_8));
  }

  private IngestionClaim parsed(AuthorityTestContext authority) {
    upload(authority);
    var claim = authority.claimIngestion(owner.workspaceId()).orElseThrow();
    assertTrue(authority.completeIngestion(claim, evidence()));
    return claim;
  }

  private static ParsedText evidence() {
    return new ParsedText(
        List.of(new TextPage(1, "secret-policy二")),
        List.of(new TextSegment(0, 1, 0, 13, "secret-policy"), new TextSegment(1, 1, 13, 14, "二")));
  }

  private static Map<String, String> digests(IndexClaim claim) {
    var result = new LinkedHashMap<String, String>();
    claim
        .segments()
        .forEach(
            segment ->
                result.put(
                    RetrievalProjection.physicalSegmentId(
                        claim.projectionGenerationId(), segment.segmentId()),
                    "a".repeat(64)));
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

  @SuppressWarnings("unchecked")
  private static Map<String, Object> first(Map<String, Object> response) {
    return ((List<Map<String, Object>>) response.get("items")).getFirst();
  }

  private static ApplicationException fails(int status, Runnable action) {
    var problem = assertThrows(ApplicationException.class, action::run);
    assertEquals(status, HttpProblemMapper.status(problem));
    return problem;
  }

  private void sql(String sql) throws SQLException {
    try (var db =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = db.createStatement()) {
      statement.execute(sql);
    }
  }

  private int scalar(String sql) throws SQLException {
    try (var db =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = db.createStatement();
        var result = statement.executeQuery(sql)) {
      assertTrue(result.next());
      return result.getInt(1);
    }
  }
}
