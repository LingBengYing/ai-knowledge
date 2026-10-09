package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.FileSynopsis;
import com.evidence.rag.model.domain.SynopsisDraft;
import com.evidence.rag.model.domain.SynopsisEvidence;
import com.evidence.rag.model.domain.SynopsisInput;
import com.evidence.rag.support.PublishedCorpusFixture;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SynopsisRepositoryTest {
  static final Actor OWNER = new Actor("org", "owner");
  static final String NOW = "2026-09-20T06:00:00Z";
  static final String MODEL = "synopsis-model-v1";
  static final String POLICY = "synopsis-policy-v1";
  // Public synthetic digest only; Repository never receives the private worker credential.
  static final String CLAIM_HASH = "a".repeat(64);
  @TempDir Path directory;

  @Test
  void versionThirteenAddsIndependentSynopsisRelationsWithRealForeignKeys() {
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(
                HistoricalSchemaV25Fixture.CURRENT_VERSION, store.count("PRAGMA user_version"));
            assertEquals(
                4,
                store.count(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('synopsis_tasks','synopsis_input_evidence','synopsis_entries','synopsis_references')"));
            assertTrue(
                store.count(
                        "SELECT COUNT(*) FROM pragma_foreign_key_list('synopsis_tasks') WHERE \"table\"='index_publications'")
                    > 0);
            assertTrue(
                store.count(
                        "SELECT COUNT(*) FROM pragma_foreign_key_list('synopsis_references') WHERE \"table\"='synopsis_input_evidence'")
                    > 0);
            return null;
          });
    }
  }

  @Test
  void completeSynopsisSealsWholeInputAndOrderedReferencesAcrossReopen() {
    FileSynopsis expected;
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var input = input(fixture);
      expected = synopsis(input);
      var store = fixture.authority.store();
      var repository = new SynopsisRepository(store);
      store.transaction(
          () -> {
            repository.insertTask("synopsis-one", OWNER, input.publication(), MODEL, POLICY, NOW);
            assertEquals(1, repository.pendingCount(OWNER.workspaceId()));
            assertFalse(repository.hasProcessing(OWNER.workspaceId()));
            assertEquals(List.of("synopsis-one"), repository.queuedIds(OWNER.workspaceId()));
            assertEquals(
                "synopsis-one",
                repository
                    .findReusable(
                        input.publication().documentId(),
                        input.publication().publicationId(),
                        MODEL,
                        POLICY)
                    .orElseThrow()
                    .id());
            assertTrue(repository.markProcessing("synopsis-one", CLAIM_HASH, input, NOW));
            assertFalse(repository.markProcessing("synopsis-one", CLAIM_HASH, input, NOW));
            assertTrue(repository.hasProcessing(OWNER.workspaceId()));
            var task = repository.findTask("synopsis-one").orElseThrow();
            assertEquals(OWNER, task.creator());
            assertEquals(input.publication(), task.publication());
            assertEquals(input.fingerprint(), task.inputFingerprint());
            assertFalse(repository.complete("synopsis-one", "b".repeat(64), expected, NOW));
            assertTrue(repository.complete("synopsis-one", CLAIM_HASH, expected, NOW));
            assertEquals(expected, repository.findSynopsis("synopsis-one").orElseThrow());
            assertEquals(0, repository.pendingCount(OWNER.workspaceId()));
            assertEquals("available", repository.findTask("synopsis-one").orElseThrow().state());
            assertNull(repository.findTask("synopsis-one").orElseThrow().claimHash());
            assertFalse(repository.terminate("synopsis-one", "cancelled", "source_changed", NOW));
            assertEquals(1, store.count("SELECT COUNT(*) FROM synopsis_input_evidence"));
            assertEquals(3, store.count("SELECT COUNT(*) FROM synopsis_entries"));
            assertEquals(3, store.count("SELECT COUNT(*) FROM synopsis_references"));
            assertThrows(
                RuntimeException.class,
                () -> store.execute("UPDATE synopsis_entries SET text='rewritten'"));
            assertThrows(
                RuntimeException.class, () -> store.execute("DELETE FROM synopsis_input_evidence"));
            return null;
          });
    }
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(
                expected, new SynopsisRepository(store).findSynopsis("synopsis-one").orElseThrow());
            assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
            return null;
          });
    }
  }

  @Test
  void onePendingExecutionAndRestartFailurePreserveIndexedDocumentAndPermitExplicitRetry() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var input = input(fixture);
      var store = fixture.authority.store();
      var repository = new SynopsisRepository(store);
      store.transaction(
          () -> {
            repository.insertTask("first", OWNER, input.publication(), MODEL, POLICY, NOW);
            assertThrows(
                RuntimeException.class,
                () ->
                    repository.insertTask(
                        "duplicate", OWNER, input.publication(), MODEL, POLICY, NOW));
            assertTrue(repository.markProcessing("first", CLAIM_HASH, input, NOW));
            assertEquals(1, repository.recoverProcessing(OWNER.workspaceId(), NOW));
            assertEquals(0, repository.recoverProcessing(OWNER.workspaceId(), NOW));
            var stopped = repository.findTask("first").orElseThrow();
            assertEquals("unavailable", stopped.state());
            assertEquals("worker_interrupted", stopped.errorCode());
            assertNull(stopped.claimHash());
            assertFalse(repository.complete("first", CLAIM_HASH, synopsis(input), NOW));
            assertTrue(repository.findSynopsis("first").isEmpty());
            assertTrue(
                repository
                    .findReusable(
                        input.publication().documentId(),
                        input.publication().publicationId(),
                        MODEL,
                        POLICY)
                    .isEmpty());
            repository.insertTask("retry", OWNER, input.publication(), MODEL, POLICY, NOW);
            assertTrue(
                repository.terminate("retry", "unavailable", "input_capacity_exceeded", NOW));
            assertEquals(0, store.count("SELECT COUNT(*) FROM synopsis_entries"));
            assertEquals(1, store.count("SELECT COUNT(*) FROM active_corpus_publications"));
            assertEquals(
                1, store.count("SELECT COUNT(*) FROM indexing_jobs WHERE state='indexed'"));
            return null;
          });
    }
  }

  static SynopsisInput input(PublishedCorpusFixture fixture) {
    var claim = fixture.publish(OWNER, "预算42万元。");
    var publication =
        fixture
            .evidence
            .snapshot(OWNER, DocumentSelection.allDocuments(), PublishedCorpusFixture.TARGET)
            .publications()
            .getFirst();
    return new SynopsisInput(
        publication,
        List.of(
            new SynopsisEvidence(
                PublishedCorpusFixture.physicalIds(claim).getFirst(),
                SynopsisEvidence.Kind.TEXT,
                new SynopsisEvidence.Text("预算42万元。"),
                null)));
  }

  static FileSynopsis synopsis(SynopsisInput input) {
    var source = input.evidence().getFirst();
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
                        new SynopsisDraft.Item(section, "预算42万元。", List.of(source.id())),
                        List.of(reference),
                        null))
            .toList();
    return new FileSynopsis(input.publication(), input.fingerprint(), MODEL, POLICY, entries, null);
  }
}
