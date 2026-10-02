package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.FileSynopsis;
import com.evidence.rag.model.domain.SynopsisClaim;
import com.evidence.rag.model.domain.SynopsisDraft;
import com.evidence.rag.model.domain.SynopsisEvidence;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.repository.SynopsisMaterialRepository;
import com.evidence.rag.repository.SynopsisRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.PublishedCorpusFixture;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SynopsisLibraryServiceTest {
  @TempDir Path directory;
  private final Actor owner = new Actor("org-main", "owner");
  private final AtomicReference<String> revision = new AtomicReference<>("synopsis-model-v1");

  private SynopsisLibraryService service(SqliteAuthorityStore store) {
    return new SynopsisLibraryService(
        store,
        new SynopsisRepository(store),
        new SynopsisMaterialRepository(store),
        new ManagementRepository(store),
        new DocumentPermissionPolicy(),
        revision::get);
  }

  @Test
  void fullPublicationBecomesDurableSummaryWithOriginalSourceAndIdempotentReceipt() {
    String documentId;
    String taskId;
    FileSynopsis expected;
    try (var fixture = new PublishedCorpusFixture(directory)) {
      documentId = fixture.publish(owner, "资料说明：蓝色装置采用太阳能。末尾说明：先断电再维护。").documentId();
      var library = service(fixture.authority.store());
      var receipt = library.create(owner, documentId);
      taskId = receipt.taskId();
      assertEquals("queued", receipt.state());
      assertEquals(taskId, library.create(owner, documentId).taskId());
      var claim = library.claim(owner.workspaceId()).orElseThrow();
      assertEquals("processing", library.task(owner, taskId).state());
      assertTrue(library.current(claim));
      assertTrue(library.claim(owner.workspaceId()).isEmpty());
      expected = output(claim);
      assertTrue(library.complete(claim, expected));
      assertFalse(library.current(claim));
      assertFalse(library.complete(claim, expected));
      assertEquals("available", library.task(owner, taskId).state());
      assertEquals(taskId, library.create(owner, documentId).taskId());
      assertEquals(expected, library.get(owner, documentId).synopsis());
      var original = library.source(owner, taskId, 0, 0);
      assertInstanceOf(SynopsisEvidence.Text.class, original.evidence().content());
      assertEquals(
          expected.entries().getFirst().evidence().getFirst().sha256(),
          original.evidence().sha256());
      assertArrayEquals(
          "资料说明：蓝色装置采用太阳能。末尾说明：先断电再维护。".getBytes(java.nio.charset.StandardCharsets.UTF_8),
          original.content());
    }
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var library = service(fixture.authority.store());
      assertEquals(expected, library.get(owner, documentId).synopsis());
      assertEquals(taskId, library.get(owner, documentId).synopsisId());
      assertEquals(0, library.recover(owner.workspaceId()));
      assertNotNull(library.source(owner, taskId, 2, 0));
    }
  }

  @Test
  void readersCanReadSharedDerivedArtifactButCannotGenerateIt() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      String documentId = fixture.publish(owner, "蓝色装置采用太阳能。").documentId();
      var library = service(fixture.authority.store());
      var task = library.create(owner, documentId);
      var claim = library.claim(owner.workspaceId()).orElseThrow();
      assertTrue(library.complete(claim, output(claim)));
      var reader = new Actor(owner.workspaceId(), "reader");
      fixture
          .authority
          .store()
          .transaction(
              () -> {
                new ManagementRepository(fixture.authority.store())
                    .insertGrant(documentId, reader.principalId(), "reader");
                return null;
              });
      assertEquals(task.taskId(), library.get(reader, documentId).synopsisId());
      assertNotNull(library.source(reader, task.taskId(), 0, 0));
      assertThrows(ApplicationException.class, () -> library.create(reader, documentId));
      assertThrows(
          ApplicationException.class,
          () -> library.get(new Actor("other", reader.principalId()), documentId));
      assertThrows(
          ApplicationException.class,
          () -> library.task(new Actor(owner.workspaceId(), "stranger"), task.taskId()));
    }
  }

  @Test
  void revokedCreatorCannotSealOrExposePartialSummaryAndIndexedFileIsUntouched() throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      String documentId = fixture.publish(owner, "蓝色装置采用太阳能。").documentId();
      var library = service(fixture.authority.store());
      var task = library.create(owner, documentId);
      var claim = library.claim(owner.workspaceId()).orElseThrow();
      sql("DELETE FROM document_acl WHERE document_id='" + documentId + "'");
      assertFalse(library.current(claim));
      assertFalse(library.complete(claim, output(claim)));
      assertThrows(ApplicationException.class, () -> library.get(owner, documentId));
      assertThrows(ApplicationException.class, () -> library.source(owner, task.taskId(), 0, 0));
      assertEquals(0, count("synopsis_entries"));
      assertEquals(1, count("index_publications"));
    }
  }

  @Test
  void failedSummaryCanBeExplicitlyResubmittedWithoutAutomaticRetry() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      String documentId = fixture.publish(owner, "蓝色装置采用太阳能。").documentId();
      var library = service(fixture.authority.store());
      var first = library.create(owner, documentId);
      var claim = library.claim(owner.workspaceId()).orElseThrow();
      var failure =
          new FileSynopsis(
              claim.publication(),
              claim.input().fingerprint(),
              claim.modelRevision(),
              claim.policyRevision(),
              List.of(),
              "unsupported_claims");
      assertTrue(library.complete(claim, failure));
      assertEquals("unavailable", library.task(owner, first.taskId()).state());
      assertEquals("unsupported_claims", library.task(owner, first.taskId()).errorCode());
      assertTrue(library.claim(owner.workspaceId()).isEmpty());
      assertThrows(ApplicationException.class, () -> library.get(owner, documentId));
      assertNotEquals(first.taskId(), library.create(owner, documentId).taskId());
      assertNotNull(library.claim(owner.workspaceId()).orElseThrow());
    }
  }

  @Test
  void restartMarksRunningUnavailableAndDoesNotReplayModels() {
    String documentId;
    String taskId;
    try (var fixture = new PublishedCorpusFixture(directory)) {
      documentId = fixture.publish(owner, "蓝色装置采用太阳能。").documentId();
      var library = service(fixture.authority.store());
      taskId = library.create(owner, documentId).taskId();
      library.claim(owner.workspaceId()).orElseThrow();
    }
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var library = service(fixture.authority.store());
      assertEquals(1, library.recover(owner.workspaceId()));
      assertEquals("worker_interrupted", library.task(owner, taskId).errorCode());
      assertEquals("unavailable", library.task(owner, taskId).state());
      assertEquals(0, library.recover(owner.workspaceId()));
      assertTrue(library.claim(owner.workspaceId()).isEmpty());
      assertNotEquals(taskId, library.create(owner, documentId).taskId());
    }
  }

  @Test
  void modelRevisionChangeFencesClaimsAndOldResults() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      String documentId = fixture.publish(owner, "蓝色装置采用太阳能。").documentId();
      var library = service(fixture.authority.store());
      library.create(owner, documentId);
      var claim = library.claim(owner.workspaceId()).orElseThrow();
      revision.set("synopsis-model-v2");
      assertFalse(library.current(claim));
      assertFalse(library.complete(claim, output(claim)));
      library.create(owner, documentId);
      var next = library.claim(owner.workspaceId()).orElseThrow();
      assertTrue(library.complete(next, output(next)));
      revision.set("synopsis-model-v3");
      assertThrows(ApplicationException.class, () -> library.get(owner, documentId));
      assertThrows(ApplicationException.class, () -> library.source(owner, next.taskId(), 0, 0));
    }
  }

  @Test
  void forgedClaimOrReferenceCannotPublishAndLeavesNoPartialEntries() throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      String documentId = fixture.publish(owner, "蓝色装置采用太阳能。").documentId();
      var library = service(fixture.authority.store());
      library.create(owner, documentId);
      var claim = library.claim(owner.workspaceId()).orElseThrow();
      var forged =
          new SynopsisClaim(
              claim.taskId(),
              claim.creator(),
              claim.input(),
              claim.modelRevision(),
              claim.policyRevision(),
              "wrong-token");
      assertFalse(library.current(forged));
      assertFalse(library.complete(forged, output(claim)));
      var good = output(claim);
      var badEntries =
          good.entries().stream()
              .map(
                  entry ->
                      new FileSynopsis.Entry(
                          entry.item(),
                          entry.evidence().stream()
                              .map(
                                  ref ->
                                      new FileSynopsis.Reference(
                                          ref.id(), "0".repeat(64), ref.kind(), ref.time()))
                              .toList(),
                          entry.interval()))
              .toList();
      var invalid =
          new FileSynopsis(
              good.publication(),
              good.inputFingerprint(),
              good.modelRevision(),
              good.policyRevision(),
              badEntries,
              null);
      assertFalse(library.complete(claim, invalid));
      assertEquals(0, count("synopsis_entries"));
      assertEquals(1, count("index_publications"));
    }
  }

  @Test
  void queuedTaskWithRevokedWriterIsConsumedSafelyWithoutClaim() throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      String documentId = fixture.publish(owner, "蓝色装置采用太阳能。").documentId();
      var library = service(fixture.authority.store());
      library.create(owner, documentId);
      sql("UPDATE document_acl SET role='reader' WHERE document_id='" + documentId + "'");
      assertTrue(library.claim(owner.workspaceId()).isEmpty());
      assertEquals(0, count("synopsis_entries"));
    }
  }

  private static FileSynopsis output(SynopsisClaim claim) {
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
                        new SynopsisDraft.Item(section, "蓝色装置采用太阳能。", List.of(source.id())),
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

  private void sql(String statement) throws Exception {
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var query = connection.createStatement()) {
      query.executeUpdate(statement);
    }
  }

  private long count(String table) throws Exception {
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var query = connection.createStatement();
        var result = query.executeQuery("SELECT COUNT(*) FROM " + table)) {
      result.next();
      return result.getLong(1);
    }
  }
}
