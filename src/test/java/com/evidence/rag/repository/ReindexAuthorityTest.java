package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ProjectionAttempt;
import com.evidence.rag.model.domain.QualifiedProjectionTarget;
import com.evidence.rag.model.entity.DocumentRemovalEntity;
import com.evidence.rag.model.entity.IndexPublicationEntity;
import com.evidence.rag.support.AuthorityTestContext;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** SQL publication boundaries use the real Store and old public indexing interfaces. */
class ReindexAuthorityTest {
  @TempDir Path directory;

  @Test
  void duplicateInitialAndTwoPendingTasksCannotBypassTheOriginalIndexContract() throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      var store = authority.store();
      ReindexSqlFixture.requireVersionTwentyFour(store);
      var base = ReindexSqlFixture.publish(authority);
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    ReindexSqlFixture.insert(
                        store,
                        base,
                        UUID.randomUUID().toString(),
                        0,
                        null,
                        base.claim().sourceSha256(),
                        base.claim().parserRevision(),
                        base.claim().target());
                    return null;
                  }));
      String first = ReindexSqlFixture.queue(store, base, 1);
      assertThrows(RuntimeException.class, () -> ReindexSqlFixture.queue(store, base, 2));
      assertEquals(base.publicationId(), ReindexSqlFixture.active(store, base.documentId()));
      assertEquals(
          1,
          store.transaction(
              () ->
                  store.count(
                      "SELECT COUNT(*) FROM indexing_jobs WHERE document_id=? AND state='queued' AND id=?",
                      base.documentId(),
                      first)));
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute("UPDATE indexing_jobs SET rebuild_sequence=2 WHERE id=?", first);
                    return null;
                  }));
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute(
                        "UPDATE indexing_jobs SET base_publication_id=NULL WHERE id=?", first);
                    return null;
                  }));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "source",
        "parser",
        "embedding",
        "projection",
        "model",
        "dimensions",
        "base",
        "fractional-sequence"
      })
  void rawRebuildInsertionCannotClaimAnUnrelatedSourceProfileOrBase(String damage)
      throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      var store = authority.store();
      ReindexSqlFixture.requireVersionTwentyFour(store);
      var base = ReindexSqlFixture.publish(authority);
      var target = base.claim().target();
      var proposed =
          new IndexTarget(
              damage.equals("embedding") ? "unrelated-embedding" : target.embeddingIdentity(),
              damage.equals("projection") ? "c".repeat(64) : target.projectionIdentity(),
              damage.equals("model") ? "unrelated-model" : target.modelRevision(),
              damage.equals("dimensions") ? 3 : target.dimensions());
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    ReindexSqlFixture.insert(
                        store,
                        base,
                        UUID.randomUUID().toString(),
                        damage.equals("fractional-sequence") ? 1.5 : 1,
                        damage.equals("base") ? "missing-publication" : base.publicationId(),
                        damage.equals("source") ? "c".repeat(64) : base.claim().sourceSha256(),
                        damage.equals("parser")
                            ? "unrelated-parser"
                            : base.claim().parserRevision(),
                        proposed);
                    return null;
                  }));
      assertEquals(1, store.transaction(() -> store.count("SELECT COUNT(*) FROM indexing_jobs")));
      assertEquals(base.publicationId(), ReindexSqlFixture.active(store, base.documentId()));
    }
  }

  @Test
  void incompleteNewPublicationRollsBackAndOnlyTheFullReceiptCanSwitchActive() throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      var store = authority.store();
      ReindexSqlFixture.requireVersionTwentyFour(store);
      var base = ReindexSqlFixture.publish(authority);
      ReindexSqlFixture.queue(store, base, 1);
      var claim = authority.claimIndexing(ReindexSqlFixture.OWNER.workspaceId()).orElseThrow();
      assertTrue(claim.items().size() > 1);
      String partial = UUID.randomUUID().toString();
      var receipt = ReindexSqlFixture.receipt(claim);
      var publication =
          new IndexPublicationEntity(
              partial,
              claim.jobId(),
              claim.documentId(),
              claim.revisionId(),
              claim.attempt(),
              claim.projectionGenerationId(),
              claim.sourceSha256(),
              claim.parserRevision(),
              claim.target(),
              receipt.manifestSha256(),
              claim.items().size(),
              Instant.now().toString());
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    var repository = new IndexingRepository(store);
                    repository.insertPublication(publication);
                    var item = claim.items().getFirst();
                    String physical =
                        RetrievalProjection.physicalSegmentId(
                            claim.projectionGenerationId(), item.evidenceId());
                    repository.insertPublicationEntry(
                        partial,
                        item.evidenceId(),
                        physical,
                        ReindexSqlFixture.digests(claim).get(physical));
                    repository.activatePublication(claim.documentId(), partial, claim.revisionId());
                    repository.markIndexed(claim.jobId(), Instant.now().toString());
                    return null;
                  }));
      assertEquals(
          0,
          store.transaction(
              () -> store.count("SELECT COUNT(*) FROM index_publications WHERE id=?", partial)));
      assertEquals(base.publicationId(), ReindexSqlFixture.active(store, base.documentId()));
      assertTrue(authority.isIndexingClaimCurrent(claim));
      assertTrue(authority.completeIndexing(claim, ReindexSqlFixture.digests(claim), receipt));
      String current = ReindexSqlFixture.active(store, base.documentId());
      assertNotEquals(base.publicationId(), current);
      assertEquals(
          2, store.transaction(() -> store.count("SELECT COUNT(*) FROM index_publications")));
      assertEquals(
          2,
          store.transaction(
              () -> store.count("SELECT COUNT(*) FROM indexing_jobs WHERE state='indexed'")));
      assertFalse(authority.completeIndexing(claim, ReindexSqlFixture.digests(claim), receipt));
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute(
                        "UPDATE active_corpus_publications SET publication_id=? WHERE document_id=?",
                        base.publicationId(),
                        base.documentId());
                    return null;
                  }));
      assertEquals(current, ReindexSqlFixture.active(store, base.documentId()));
    }
  }

  @Test
  void successRetiresOnlyOldBasePendingSynopsisAndTheNewSynopsisSlotIsUsable() throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      var store = authority.store();
      ReindexSqlFixture.requireVersionTwentyFour(store);
      var base = ReindexSqlFixture.publish(authority);
      store.transaction(
          () -> {
            insertSynopsis(
                store,
                "old-pending",
                base.documentId(),
                base.claim().revisionId(),
                base.publicationId());
            return null;
          });
      ReindexSqlFixture.queue(store, base, 1);
      var claim = authority.claimIndexing(ReindexSqlFixture.OWNER.workspaceId()).orElseThrow();
      assertTrue(
          authority.completeIndexing(
              claim, ReindexSqlFixture.digests(claim), ReindexSqlFixture.receipt(claim)));
      String current = ReindexSqlFixture.active(store, base.documentId());
      store.transaction(
          () -> {
            assertEquals(
                1,
                store.count(
                    "SELECT COUNT(*) FROM synopsis_tasks WHERE id='old-pending' AND state='unavailable' AND error_code='source_changed' AND claim_token_sha256 IS NULL"));
            insertSynopsis(store, "new-pending", base.documentId(), claim.revisionId(), current);
            assertEquals(
                2,
                store.count(
                    "SELECT COUNT(*) FROM synopsis_tasks WHERE document_id=?", base.documentId()));
            assertEquals(
                1,
                store.count(
                    "SELECT COUNT(*) FROM synopsis_tasks WHERE id='new-pending' AND state='queued'"));
            return null;
          });
    }
  }

  @Test
  void failedRebuildNeverRetiresTheOldPendingSynopsis() throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      var store = authority.store();
      ReindexSqlFixture.requireVersionTwentyFour(store);
      var base = ReindexSqlFixture.publish(authority);
      store.transaction(
          () -> {
            insertSynopsis(
                store,
                "old-pending",
                base.documentId(),
                base.claim().revisionId(),
                base.publicationId());
            return null;
          });
      ReindexSqlFixture.queue(store, base, 1);
      var claim = authority.claimIndexing(ReindexSqlFixture.OWNER.workspaceId()).orElseThrow();
      assertTrue(authority.indexing().failIndexing(claim, "indexing_failed"));
      assertEquals(base.publicationId(), ReindexSqlFixture.active(store, base.documentId()));
      assertEquals(
          1,
          store.transaction(
              () ->
                  store.count(
                      "SELECT COUNT(*) FROM synopsis_tasks WHERE id='old-pending' AND state='queued' AND error_code IS NULL")));
    }
  }

  @Test
  void realMaintenancePurgeClearsPayloadButPreservesBothPublicationHistoriesAndAttemptInventory()
      throws Exception {
    String document;
    try (var authority = new AuthorityTestContext(directory)) {
      var store = authority.store();
      ReindexSqlFixture.requireVersionTwentyFour(store);
      var base = ReindexSqlFixture.publish(authority);
      document = base.documentId();
      ReindexSqlFixture.queue(store, base, 1);
      var claim = authority.claimIndexing(ReindexSqlFixture.OWNER.workspaceId()).orElseThrow();
      assertTrue(
          authority.completeIndexing(
              claim, ReindexSqlFixture.digests(claim), ReindexSqlFixture.receipt(claim)));
      store.transaction(
          () -> {
            var cleanup = new DocumentCleanupRepository(store);
            for (var attempt : List.of(base.claim(), claim)) {
              var target =
                  new QualifiedProjectionTarget(
                      "http://127.0.0.1:19530",
                      "default",
                      "java_reindex_fixture",
                      attempt.workspaceId(),
                      attempt.target().embeddingIdentity(),
                      attempt.target().dimensions(),
                      attempt.target().projectionIdentity());
              cleanup.registerProjectionAttempt(
                  new ProjectionAttempt(
                      attempt.documentId(),
                      attempt.workspaceId(),
                      attempt.revisionId(),
                      attempt.sourceSha256(),
                      attempt.projectionGenerationId(),
                      "legacy",
                      target,
                      false));
              cleanup.markProjectionWriteIssued(attempt.projectionGenerationId(), "legacy");
            }
            new DocumentLifecycleRepository(store)
                .insertRemoval(
                    new DocumentRemovalEntity(
                        document,
                        ReindexSqlFixture.OWNER.workspaceId(),
                        ReindexSqlFixture.OWNER.principalId(),
                        Instant.now().toString()));
            return null;
          });
      CleanupMaintenanceFixture.purge(store, ReindexSqlFixture.OWNER, document);
      store.transaction(
          () -> {
            assertEquals(
                0,
                store.count(
                    "SELECT length(original_blob) FROM corpus_documents WHERE document_id=?",
                    document));
            assertEquals(
                0,
                store.count(
                    "SELECT COALESCE(SUM(length(text)),0) FROM corpus_segments WHERE revision_id=?",
                    claim.revisionId()));
            assertEquals(
                2,
                store.count(
                    "SELECT COUNT(*) FROM indexing_jobs WHERE document_id=? AND state='indexed'",
                    document));
            assertEquals(
                2,
                store.count(
                    "SELECT COUNT(*) FROM index_publications WHERE document_id=?", document));
            assertEquals(
                2,
                store.count(
                    "SELECT COUNT(*) FROM indexing_attempts a JOIN indexing_jobs j ON j.id=a.job_id WHERE j.document_id=?",
                    document));
            assertEquals(
                2,
                store.count(
                    "SELECT COUNT(*) FROM cleanup_projection_attempts WHERE document_id=? AND route='legacy' AND write_issued=1",
                    document));
            assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
            return null;
          });
    }
    try (var reopened = new SqliteAuthorityStore(directory)) {
      ReindexSqlFixture.requireVersionTwentyFour(reopened);
      assertEquals(
          2,
          reopened.transaction(
              () ->
                  reopened.count(
                      "SELECT COUNT(*) FROM index_publications WHERE document_id=?", document)));
    }
  }

  private static void insertSynopsis(
      SqliteAuthorityStore store,
      String task,
      String document,
      String revision,
      String publication) {
    String now = Instant.now().toString();
    store.execute(
        "INSERT INTO synopsis_tasks(id,workspace_id,document_id,revision_id,publication_id,model_revision,policy_revision,state,created_by,created_at,updated_at) VALUES(?,?,?,?,?,?,?,'queued',?,?,?)",
        task,
        ReindexSqlFixture.OWNER.workspaceId(),
        document,
        revision,
        publication,
        "synthetic-synopsis-v1",
        "synthetic-policy-v1",
        ReindexSqlFixture.OWNER.principalId(),
        now,
        now);
  }
}
