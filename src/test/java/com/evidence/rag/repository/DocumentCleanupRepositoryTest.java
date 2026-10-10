package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.CleanupClaim;
import com.evidence.rag.model.domain.CleanupPlan;
import com.evidence.rag.model.domain.CleanupResource;
import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.model.domain.LibraryOperationGate;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.ProjectionAttempt;
import com.evidence.rag.model.domain.QualifiedProjectionTarget;
import com.evidence.rag.model.domain.SyntheticDocument;
import com.evidence.rag.support.DocumentWithdrawal;
import com.evidence.rag.support.PublishedCorpusFixture;
import com.evidence.rag.support.SubtitleCorpusFixture;
import com.evidence.rag.support.SynopsisCorpusFixture;
import com.evidence.rag.worker.cleanup.CleanupRestoreJournal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DocumentCleanupRepositoryTest {
  private static final Actor OWNER = new Actor("org", "owner");
  private static final String NOW = "2026-10-03T10:00:00Z";
  @TempDir Path directory;

  @Test
  void allLegacyBodyKindsBecomeEmptyWhileEveryNonBodyValueAndUnrelatedDocumentRemain()
      throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var store = fixture.authority.store();
      var material = new SynopsisCorpusFixture(store, OWNER, PublishedCorpusFixture.TARGET);
      var documents = new ArrayList<String>();
      var input = SynopsisRepositoryTest.input(fixture);
      documents.add(input.publication().documentId());
      documents.add(material.image(false).documentId());
      documents.add(material.audio(List.of("保留原时间的语音。", "完整尾部。")).documentId());
      documents.add(material.video(2).documentId());
      documents.add(SubtitleCorpusFixture.publish(fixture, OWNER, 2).documentId());
      store.transaction(
          () -> {
            var synopses = new SynopsisRepository(store);
            synopses.insertTask(
                "cleanup-synopsis",
                OWNER,
                input.publication(),
                SynopsisRepositoryTest.MODEL,
                SynopsisRepositoryTest.POLICY,
                NOW);
            assertTrue(
                synopses.markProcessing(
                    "cleanup-synopsis", SynopsisRepositoryTest.CLAIM_HASH, input, NOW));
            assertTrue(
                synopses.complete(
                    "cleanup-synopsis",
                    SynopsisRepositoryTest.CLAIM_HASH,
                    SynopsisRepositoryTest.synopsis(input),
                    NOW));
            return null;
          });
      var keep = fixture.publish(OWNER, "另一个文件的正文必须仍然可读。");
      var keepBefore = bodies(store, keep.documentId());
      var represented = new java.util.HashSet<String>();
      for (String document : documents) {
        var before = nonBodies(store, document);
        var claim = claim(store, document);
        var plan = store.transaction(() -> new DocumentCleanupRepository(store).sealPlan(claim));
        plan.payloadRows().forEach(p -> represented.add(p.tableName()));
        assertTrue(plan.payloadRows().stream().anyMatch(p -> p.sizeBytes() > 0));
        CleanupRestoreJournal.intent(directory, store.libraryIdentity(), plan);
        try (var lease = store.operationGate().tryMaintenance().orElseThrow()) {
          store.purge(claim, plan, lease);
          store.purge(claim, plan, lease);
          store.compact(claim, lease);
        }
        assertEquals(before, nonBodies(store, document));
        store.transaction(
            () -> {
              new DocumentCleanupRepository(store).verifyPurged(document, claim.cleanupId());
              assertEquals(
                  plan, new DocumentCleanupRepository(store).plan(claim.cleanupId()).orElseThrow());
              return null;
            });
      }
      assertTrue(
          represented.containsAll(
              List.of(
                  "corpus_documents",
                  "corpus_pages",
                  "corpus_segments",
                  "image_evidence",
                  "audio_spans",
                  "video_frames",
                  "video_transcript_spans",
                  "video_frame_ocr",
                  "video_ocr_segments",
                  "video_subtitle_tracks",
                  "video_subtitle_cues",
                  "synopsis_entries")),
          represented.toString());
      assertEquals(keepBefore, bodies(store, keep.documentId()));
    }
    try (var reopened = new SqliteAuthorityStore(directory)) {
      reopened.transaction(
          () -> {
            new DocumentCleanupRepository(reopened).verifyPurgedRows();
            return null;
          });
    }
  }

  @Test
  void liveAndRemovedOrdinaryWritersCannotClearRefillReplaceOrMutateIdentity() throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var store = fixture.authority.store();
      var source = fixture.publish(OWNER, "不可由普通 SQL 清空的正文。");
      assertRejected(store, "UPDATE corpus_pages SET text='',payload_purged=1");
      assertRejected(store, "UPDATE corpus_documents SET original_blob=x'',payload_purged=1");
      assertRejected(store, "DELETE FROM corpus_pages");
      var claim = claim(store, source.documentId());
      var plan = store.transaction(() -> new DocumentCleanupRepository(store).sealPlan(claim));
      assertRejected(store, "UPDATE corpus_pages SET text='',payload_purged=1");
      try (var other =
          new LibraryOperationGate(directory.resolve("foreign-work"))
              .tryMaintenance()
              .orElseThrow()) {
        assertThrows(RuntimeException.class, () -> store.purge(claim, plan, other));
      }
      try (var lease = store.operationGate().tryMaintenance().orElseThrow()) {
        assertThrows(RuntimeException.class, () -> store.purge(claim, plan, lease));
        assertThrows(RuntimeException.class, () -> store.compact(claim, lease));
        CleanupRestoreJournal.intent(directory, store.libraryIdentity(), plan);
        store.purge(claim, plan, lease);
      }
      assertRejected(store, "UPDATE corpus_pages SET text='resurrected',payload_purged=0");
      assertRejected(store, "UPDATE corpus_pages SET text_sha256='" + "a".repeat(64) + "'");
      assertRejected(store, "INSERT OR REPLACE INTO corpus_pages SELECT * FROM corpus_pages");
      assertRejected(store, "DELETE FROM cleanup_payloads");
      assertRejected(store, "UPDATE cleanup_payloads SET body_sha256='" + "b".repeat(64) + "'");
    }
  }

  @Test
  void intentOnlyCrashReclaimsRunningButHighWaterRejectsSamePlanPrePurgeSnapshot()
      throws Exception {
    String document;
    CleanupClaim first;
    CleanupPlan sealed;
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var store = fixture.authority.store();
      document = fixture.publish(OWNER, "崩溃重试不可以恢复已经清除的正文。").documentId();
      first = claim(store, document);
      sealed = store.transaction(() -> new DocumentCleanupRepository(store).sealPlan(first));
      CleanupRestoreJournal.intent(directory, store.libraryIdentity(), sealed);
    }
    Path beforePurge = directory.resolve("synthetic-pre-purge.db");
    Files.copy(directory.resolve("java-library.db"), beforePurge);
    try (var store = new SqliteAuthorityStore(directory)) {
      var repository = new DocumentCleanupRepository(store);
      var resumed = store.transaction(() -> repository.claimNext(NOW).orElseThrow());
      assertEquals(first.cleanupId(), resumed.cleanupId());
      assertNotEquals(first.claimToken(), resumed.claimToken());
      assertFalse(store.transaction(() -> repository.current(first)));
      assertEquals(sealed, store.transaction(() -> repository.sealPlan(resumed)));
      try (var lease = store.operationGate().tryMaintenance().orElseThrow()) {
        store.purge(resumed, sealed, lease);
      }
    }
    assertTrue(Files.size(directory.resolve(".cleanup-restore-high-water.jsonl")) > 0);
    Files.copy(
        beforePurge, directory.resolve("java-library.db"), StandardCopyOption.REPLACE_EXISTING);
    assertThrows(IllegalStateException.class, () -> new SqliteAuthorityStore(directory));
  }

  @Test
  void missingHighWaterOrChangedSchemaPreventsPurgedAuthorityReopen() throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var store = fixture.authority.store();
      var claim = claim(store, fixture.publish(OWNER, "删除恢复屏障后不可打开。").documentId());
      var plan = store.transaction(() -> new DocumentCleanupRepository(store).sealPlan(claim));
      CleanupRestoreJournal.intent(directory, store.libraryIdentity(), plan);
      try (var lease = store.operationGate().tryMaintenance().orElseThrow()) {
        store.purge(claim, plan, lease);
      }
    }
    Path highWater = directory.resolve(".cleanup-restore-high-water.jsonl");
    byte[] original = Files.readAllBytes(highWater);
    Files.delete(highWater);
    assertThrows(IllegalStateException.class, () -> new SqliteAuthorityStore(directory));
    Files.write(highWater, original);
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement()) {
      statement.execute("DROP TRIGGER cleanup_corpus_pages_purge");
    }
    assertThrows(IllegalStateException.class, () -> new SqliteAuthorityStore(directory));
  }

  @ParameterizedTest
  @ValueSource(strings = {"duplicate", "trailing", "foreign-library", "foreign-document"})
  void restoreJournalRejectsAmbiguousOrMismatchedIntentBeforeAnyRecovery(String corruption)
      throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var store = fixture.authority.store();
      var claim = claim(store, fixture.publish(OWNER, "意图记录必须完整绑定当前库和资料。").documentId());
      var plan = store.transaction(() -> new DocumentCleanupRepository(store).sealPlan(claim));
      CleanupRestoreJournal.intent(directory, store.libraryIdentity(), plan);
    }
    Path journal = directory.resolve(".cleanup-restore-journal.jsonl");
    String original = Files.readString(journal).strip();
    String changed =
        switch (corruption) {
          case "duplicate" ->
              original.substring(0, original.length() - 1) + ",\"cleanup_id\":\"duplicate\"}";
          case "trailing" -> original + " {}";
          case "foreign-library" ->
              original.replaceFirst(
                  "\"library_id\":\"[^\"]+\"", "\"library_id\":\"other-library\"");
          case "foreign-document" ->
              original.replaceFirst(
                  "\"document_id\":\"[^\"]+\"", "\"document_id\":\"other-document\"");
          default -> throw new AssertionError(corruption);
        };
    Files.writeString(journal, changed + "\n");
    assertThrows(IllegalStateException.class, () -> new SqliteAuthorityStore(directory));
    Files.writeString(journal, original + "\n");
    try (var store = new SqliteAuthorityStore(directory)) {
      assertEquals(
          1,
          store.transaction(
              () -> store.count("SELECT COUNT(*) FROM document_cleanups WHERE state='pending'")));
    }
  }

  @Test
  void realCompactionReleasesBlobPagesOnlyAfterPayloadPurgeAndKeepsAuditSize() throws Exception {
    long before;
    String document;
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var store = fixture.authority.store();
      byte[] bytes = new byte[2 * 1024 * 1024];
      Arrays.fill(bytes, (byte) 'x');
      var upload =
          fixture.authority.ingestion().uploadDocument(OWNER, "large.txt", "text/plain", bytes);
      document = upload.documentId();
      // The removed withdrawal path also cancelled the queued parse; cleanup requires idle work.
      fixture.authority.ingestion().cancelIngestion(OWNER, upload.taskId());
      before = Files.size(store.libraryPath());
      assertEquals(
          bytes.length,
          store.transaction(() -> new IngestionRepository(store).storedBytes(OWNER.workspaceId())));
      var claim = claim(store, document);
      assertEquals(
          bytes.length,
          store.transaction(() -> new IngestionRepository(store).storedBytes(OWNER.workspaceId())));
      var plan = store.transaction(() -> new DocumentCleanupRepository(store).sealPlan(claim));
      CleanupRestoreJournal.intent(directory, store.libraryIdentity(), plan);
      try (var lease = store.operationGate().tryMaintenance().orElseThrow()) {
        store.purge(claim, plan, lease);
        store.compact(claim, lease);
        Path snapshot = store.libraryPath().getParent().resolve(".cleanup-backup-test.partial");
        store.writeManagedSnapshot(snapshot, claim, lease);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + snapshot);
            var statement = connection.createStatement();
            var row =
                statement.executeQuery("SELECT length(original_blob) FROM corpus_documents")) {
          assertTrue(row.next());
          assertEquals(0, row.getInt(1));
        }
      }
      assertEquals(
          0L,
          store.transaction(() -> new IngestionRepository(store).storedBytes(OWNER.workspaceId())));
      assertEquals(
          bytes.length,
          store.transaction(
              () -> store.count("SELECT size_bytes FROM documents WHERE id=?", document)));
      assertTrue(Files.size(store.libraryPath()) < before - bytes.length / 2);
    }
  }

  @Test
  void completedReceiptNeedsAllNineResourcesAndIsImmutableWhileAclPaginationRemainsCurrent()
      throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var store = fixture.authority.store();
      var claim = claim(store, fixture.publish(OWNER, "只在九种资源全部证明后完成。").documentId());
      var repository = new DocumentCleanupRepository(store);
      var plan = store.transaction(() -> repository.sealPlan(claim));
      CleanupRestoreJournal.intent(directory, store.libraryIdentity(), plan);
      try (var lease = store.operationGate().tryMaintenance().orElseThrow()) {
        store.purge(claim, plan, lease);
        store.compact(claim, lease);
      }
      store.transaction(
          () -> {
            for (String kind : CleanupResource.KINDS) {
              repository.setResource(claim, kind, "completed", null, NOW);
            }
            var done = repository.finish(claim, NOW);
            assertEquals("deleted", done.status());
            assertEquals("completed", done.cleanupStatus());
            assertEquals(9, done.resources().size());
            assertEquals(
                done.cleanupId(), repository.create(OWNER, claim.documentId(), NOW).cleanupId());
            assertEquals(1, repository.list(OWNER, 1, 20).total());
            assertTrue(
                repository.find(new Actor("elsewhere", "owner"), claim.documentId()).isEmpty());
            store.execute(
                "UPDATE document_acl SET role='reader' WHERE document_id=?", claim.documentId());
            assertEquals(0, repository.list(OWNER, 1, 20).total());
            assertTrue(repository.find(OWNER, claim.documentId()).isEmpty());
            return null;
          });
      assertRejected(store, "UPDATE document_cleanups SET state='pending',completed_at=NULL");
      assertRejected(store, "UPDATE cleanup_resources SET status='pending'");
    }
  }

  @Test
  void projectionAttemptCannotChangeQualifiedTargetOrUndoWriteIntent() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var store = fixture.authority.store();
      var source = fixture.publish(OWNER, "远端目标登记不可被当前配置替换。");
      var repository = new DocumentCleanupRepository(store);
      String generation = UUID.randomUUID().toString();
      var target =
          new QualifiedProjectionTarget(
              "http://127.0.0.1:19530",
              "default",
              "java_synthetic",
              OWNER.workspaceId(),
              "embed-v1",
              2,
              "a".repeat(64));
      var attempt =
          new ProjectionAttempt(
              source.documentId(),
              OWNER.workspaceId(),
              source.revisionId(),
              source.sourceSha256(),
              generation,
              "text",
              target,
              false);
      store.transaction(
          () -> {
            repository.registerProjectionAttempt(attempt);
            repository.markProjectionWriteIssued(generation, "text");
            return null;
          });
      assertRejected(store, "UPDATE cleanup_projection_attempts SET collection_name='java_other'");
      assertRejected(store, "UPDATE cleanup_projection_attempts SET write_issued=0");
      assertRejected(store, "DELETE FROM cleanup_projection_attempts");
      var claim = claim(store, source.documentId());
      var inventory = store.transaction(() -> repository.projectionInventory(claim));
      assertFalse(inventory.known());
      assertEquals(target, inventory.attempts().getFirst().target());
      assertTrue(inventory.attempts().getFirst().writeIssued());
    }
  }

  @Test
  void workspaceQuotaCountsAllThreeResidentOriginalStoresAndNeverUsesImmutableAuditSize()
      throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var store = fixture.authority.store();
      byte[] legacy = "legacy resident bytes".getBytes(StandardCharsets.UTF_8);
      String document =
          fixture
              .authority
              .ingestion()
              .uploadDocument(OWNER, "original.txt", "text/plain", legacy)
              .documentId();
      var sound =
          new DocumentOriginal(
              "sound",
              "sound-rev",
              "sound.wav",
              "audio",
              "audio/wav",
              ModelValues.sha256(new byte[128]),
              128,
              new byte[128]);
      var video =
          new DocumentOriginal(
              "video",
              "video-rev",
              "video.mp4",
              "video",
              "video/mp4",
              ModelValues.sha256(new byte[256]),
              256,
              new byte[256]);
      store.transaction(
          () -> {
            var management = new ManagementRepository(store);
            for (var value : List.of(sound, video)) {
              management.insertDocument(
                  OWNER,
                  new SyntheticDocument(
                      value.documentId(),
                      value.filename(),
                      value.documentType(),
                      value.mediaType(),
                      value.revisionId(),
                      value.sourceSha256(),
                      value.sizeBytes()),
                  NOW);
              management.insertGrant(value.documentId(), OWNER.principalId(), "owner");
            }
            new SoundRepository(store).insertOriginal(sound, NOW);
            new VideoAvRepository(store).insertOriginal(video, NOW);
            assertEquals(
                legacy.length + 128 + 256,
                new IngestionRepository(store).storedBytes(OWNER.workspaceId()));
            assertEquals(0, new IngestionRepository(store).storedBytes("different-workspace"));
            return null;
          });
      DocumentWithdrawal.withdraw(store, OWNER, sound.documentId());
      DocumentWithdrawal.withdraw(store, OWNER, document);
      assertEquals(
          legacy.length + 128 + 256,
          store.transaction(() -> new IngestionRepository(store).storedBytes(OWNER.workspaceId())));
      DocumentWithdrawal.withdraw(store, OWNER, video.documentId());
      assertEquals(
          legacy.length + 128 + 256,
          store.transaction(() -> new IngestionRepository(store).storedBytes(OWNER.workspaceId())));
      for (String table : List.of("corpus_documents", "sound_originals", "video_av_originals")) {
        assertRejected(store, "UPDATE " + table + " SET original_blob=x'' WHERE payload_purged=0");
      }
      var claim = claim(store, sound.documentId());
      var plan = store.transaction(() -> new DocumentCleanupRepository(store).sealPlan(claim));
      CleanupRestoreJournal.intent(directory, store.libraryIdentity(), plan);
      try (var lease = store.operationGate().tryMaintenance().orElseThrow()) {
        store.purge(claim, plan, lease);
      }
      assertEquals(
          legacy.length + 256,
          store.transaction(() -> new IngestionRepository(store).storedBytes(OWNER.workspaceId())));
      assertEquals(
          legacy.length + 128 + 256,
          store.transaction(() -> store.count("SELECT SUM(size_bytes) FROM documents")));
    }
  }

  static CleanupClaim claim(SqliteAuthorityStore store, String document) {
    DocumentWithdrawal.withdraw(store, OWNER, document);
    return store.transaction(
        () -> {
          var repository = new DocumentCleanupRepository(store);
          repository.create(OWNER, document, NOW);
          return repository.claimNext(NOW).orElseThrow();
        });
  }

  private static void assertRejected(SqliteAuthorityStore store, String sql) {
    assertThrows(
        RuntimeException.class,
        () ->
            store.transaction(
                () -> {
                  store.execute(sql);
                  return null;
                }));
  }

  private static List<String> nonBodies(SqliteAuthorityStore store, String document) {
    return snapshot(store, document, false);
  }

  private static List<String> bodies(SqliteAuthorityStore store, String document) {
    return snapshot(store, document, true);
  }

  private static List<String> snapshot(SqliteAuthorityStore store, String document, boolean all) {
    return store.transaction(
        () -> {
          var output = new ArrayList<String>();
          for (var table : CleanupPayloadTables.TABLES) {
            for (var row :
                store.rows(
                    "SELECT p.* FROM "
                        + table.name()
                        + " p WHERE "
                        + table.document("p")
                        + "=? ORDER BY "
                        + table.rowKey("p"),
                    document)) {
              var values = new LinkedHashMap<String, Object>();
              row.forEach(
                  (key, value) -> {
                    if (!key.equals("payload_purged")
                        && (all
                            || table.bodies().stream()
                                .noneMatch(body -> body.name().equals(key)))) {
                      values.put(
                          key, value instanceof byte[] bytes ? ModelValues.sha256(bytes) : value);
                    }
                  });
              output.add(table.name() + values);
            }
          }
          return List.copyOf(output);
        });
  }
}
