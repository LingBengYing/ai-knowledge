package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.*;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.dto.DocumentBatchCommand;
import com.evidence.rag.model.dto.DocumentBatchResult;
import com.evidence.rag.service.DocumentBatchService;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.tool.parser.TextParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 2 #7, #9 and #10 against a real authority: SHA-256 duplicates are skipped and settled, the
 * trigger-maintained file state always equals the four-table derivation the page used to compute,
 * and claims take interactive uploads first, then batches in turn.
 */
class BatchThroughputRepositoryTest {
  @TempDir Path directory;
  final Actor actor = new Actor("org-main", "owner");

  static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  static DocumentBatchCommand.File file(String name, byte[] data) {
    return new DocumentBatchCommand.File(name, data.length, ModelValues.sha256(data));
  }

  static DocumentBatchCommand manifest(
      String key, Boolean include, DocumentBatchCommand.File... f) {
    return new DocumentBatchCommand(key, List.of(f), 100, include);
  }

  static DocumentBatchResult.File item(DocumentBatchResult result, int ordinal) {
    return result.items().stream().filter(i -> i.ordinal() == ordinal).findFirst().orElseThrow();
  }

  /** Cached state rows must equal the full derivation for every file. */
  static void assertCacheMatches(SqliteAuthorityStore store) {
    store.transaction(
        () -> {
          compareCache(store);
          return null;
        });
  }

  private static void compareCache(SqliteAuthorityStore store) {
    var derived = new HashMap<String, String>();
    for (var row :
        store.rows(
            "SELECT f.batch_id||':'||f.ordinal AS k,"
                + BatchThroughputSchema.STATE
                + " AS s "
                + BatchThroughputSchema.INPUTS)) {
      derived.put((String) row.get("k"), (String) row.get("s"));
    }
    var cached = new HashMap<String, String>();
    for (var row :
        store.rows(
            "SELECT batch_id||':'||ordinal AS k,state AS s FROM document_import_file_states")) {
      cached.put((String) row.get("k"), (String) row.get("s"));
    }
    assertEquals(derived.keySet(), cached.keySet());
    cached.forEach(
        (key, state) -> {
          if (!state.equals("duplicate")) assertEquals(derived.get(key), state, key);
        });
  }

  @Test
  void duplicatesOfLiveDocumentsAndEarlierManifestFilesAreSkippedAndCountAsDone() {
    var a = bytes("Synthetic duplicate source A");
    var b = bytes("Synthetic duplicate source B");
    try (var authority = new AuthorityTestContext(directory)) {
      var batches = new DocumentBatchService(authority.store(), authority.ingestion(), 1000);
      var first = batches.create(actor, manifest("dedupe-first", null, file("a.txt", a)));
      String existing = batches.upload(actor, first.batchId(), 0, a).documentId();

      var second =
          batches.create(
              actor,
              manifest(
                  "dedupe-second",
                  null,
                  file("a-copy.txt", a),
                  file("b.txt", b),
                  file("b-copy.txt", b)));
      assertEquals("duplicate", item(second, 0).state());
      assertNull(item(second, 0).documentId(), "a skipped file owns no document");
      assertEquals(existing, item(second, 0).duplicateOfDocumentId());
      assertEquals("awaiting_upload", item(second, 1).state());
      assertEquals("duplicate", item(second, 2).state());
      assertEquals(1, item(second, 2).duplicateOfOrdinal());
      assertNull(item(second, 2).documentId());
      assertEquals(2L, second.counts().get("duplicate"));
      assertEquals("awaiting_upload", second.state());
      var refused =
          assertThrows(
              ApplicationException.class, () -> batches.upload(actor, second.batchId(), 0, a));
      assertEquals("batch_file_duplicate", refused.code());
      String uploaded = batches.upload(actor, second.batchId(), 1, b).documentId();
      var after = batches.status(actor, second.batchId(), 0, 100);
      assertEquals(
          uploaded,
          item(after, 2).duplicateOfDocumentId(),
          "in-batch duplicate links its original");
      assertEquals(0L, after.counts().getOrDefault("awaiting_upload", 0L));

      // A manifest of only duplicates is complete at once.
      var only = batches.create(actor, manifest("dedupe-only", null, file("again.txt", a)));
      assertEquals("completed", only.state());

      // Opting out imports the copy as a new document; one request id never changes its manifest.
      assertThrows(
          ApplicationException.class,
          () -> batches.create(actor, manifest("dedupe-only", true, file("again.txt", a))));
      var included = batches.create(actor, manifest("dedupe-included", true, file("again.txt", a)));
      assertNotEquals(only.batchId(), included.batchId());
      assertEquals("awaiting_upload", item(included, 0).state());
      String copy = batches.upload(actor, included.batchId(), 0, a).documentId();
      assertNotEquals(existing, copy);
      assertEquals(
          only.batchId(),
          batches.create(actor, manifest("dedupe-only", false, file("again.txt", a))).batchId(),
          "false is the default and keeps the old digest");
      assertCacheMatches(authority.store());

      // A deleted document is no longer a duplicate target.
      authority
          .store()
          .transaction(
              () -> {
                for (String id : List.of(existing, copy)) {
                  authority
                      .store()
                      .execute(
                          "INSERT INTO document_tombstones(document_id,workspace_id,requested_by,requested_at) VALUES(?,?,?,?)",
                          id,
                          "org-main",
                          "owner",
                          Instant.now().toString());
                }
                return null;
              });
      var later = batches.create(actor, manifest("dedupe-later", null, file("a.txt", a)));
      assertEquals("awaiting_upload", item(later, 0).state());
      assertEquals("deleted", item(batches.status(actor, first.batchId(), 0, 10), 0).state());
      assertCacheMatches(authority.store());
    }
  }

  @Test
  void cachedStateFollowsParseFailureRetryCancelResumeAndRestart() {
    var files = new ArrayList<DocumentBatchCommand.File>();
    var data = new ArrayList<byte[]>();
    for (int i = 0; i < 4; i++) {
      data.add(bytes("Synthetic state source " + i));
      files.add(file("s" + i + ".txt", data.get(i)));
    }
    String id;
    try (var authority = new AuthorityTestContext(directory)) {
      var store = authority.store();
      var batches = new DocumentBatchService(store, authority.ingestion(), 1000);
      id =
          batches
              .create(actor, new DocumentBatchCommand("state-cache", files, 100, null))
              .batchId();
      assertCacheMatches(store);
      for (int i = 0; i < 3; i++) batches.upload(actor, id, i, data.get(i));
      assertCacheMatches(store);
      var claim = authority.claimIngestion("org-main").orElseThrow();
      assertCacheMatches(store);
      assertTrue(authority.failIngestion(claim, "parser_failed"));
      assertCacheMatches(store);
      assertEquals(1L, batches.status(actor, id, 0, 10).counts().getOrDefault("failed", 0L));
      batches.retryFailed(actor, id);
      assertCacheMatches(store);
      var next = authority.claimIngestion("org-main").orElseThrow();
      assertTrue(
          authority.completeIngestion(
              next, new TextParser().parse("s.txt", "text/plain", bytes("Synthetic parsed"))));
      assertCacheMatches(store);
      batches.cancel(actor, id);
      assertCacheMatches(store);
      batches.resume(actor, id, null);
      assertCacheMatches(store);
      // An interactive upload outside the batch does not disturb batch rows.
      authority.uploadDocument(actor, "solo.txt", "text/plain", bytes("Synthetic solo"));
      assertCacheMatches(store);
      var counts = batches.status(actor, id, 0, 10).counts();
      assertEquals(4L, counts.values().stream().mapToLong(Long::longValue).sum());
    }
    try (var reopened = new AuthorityTestContext(directory)) {
      assertCacheMatches(reopened.store());
      var batches = new DocumentBatchService(reopened.store(), reopened.ingestion(), 1000);
      assertEquals(
          1L, batches.status(actor, id, 0, 10).counts().getOrDefault("awaiting_upload", 0L));
    }
  }

  @Test
  void parseClaimsTakeInteractiveUploadsFirstThenAlternateBatches() {
    try (var authority = new AuthorityTestContext(directory)) {
      var batches = new DocumentBatchService(authority.store(), authority.ingestion(), 1000);
      var big = new ArrayList<DocumentBatchCommand.File>();
      var bigData = new ArrayList<byte[]>();
      for (int i = 0; i < 6; i++) {
        bigData.add(bytes("Synthetic big batch " + i));
        big.add(file("big" + i + ".txt", bigData.get(i)));
      }
      String bigId =
          batches.create(actor, new DocumentBatchCommand("fair-big", big, 100, null)).batchId();
      for (int i = 0; i < 6; i++) batches.upload(actor, bigId, i, bigData.get(i));
      var smallData = List.of(bytes("Synthetic small 0"), bytes("Synthetic small 1"));
      String smallId =
          batches
              .create(
                  actor,
                  new DocumentBatchCommand(
                      "fair-small",
                      List.of(
                          file("small0.txt", smallData.get(0)),
                          file("small1.txt", smallData.get(1))),
                      100,
                      null))
              .batchId();
      for (int i = 0; i < 2; i++) batches.upload(actor, smallId, i, smallData.get(i));
      String solo =
          (String)
              authority
                  .uploadDocument(actor, "solo.txt", "text/plain", bytes("Synthetic solo upload"))
                  .get("document_id");

      var order = new ArrayList<String>();
      for (int i = 0; i < 9; i++) {
        var claim = authority.claimIngestion("org-main").orElseThrow();
        var batch =
            authority
                .store()
                .transaction(
                    () ->
                        authority
                            .store()
                            .rows(
                                "SELECT batch_id FROM document_import_files WHERE ingestion_task_id=?",
                                claim.jobId()));
        order.add(
            batch.isEmpty()
                ? "solo"
                : batch.getFirst().get("batch_id").equals(bigId) ? "big" : "small");
        if (i == 0) assertEquals(solo, claim.documentId());
      }
      assertEquals("solo", order.getFirst());
      // Batches alternate while both have work; the big one then drains.
      var middle = order.subList(1, 5);
      for (int i = 1; i < middle.size(); i++) {
        assertNotEquals(middle.get(i - 1), middle.get(i), "batches alternate: " + order);
      }
      assertEquals(2, middle.stream().filter("small"::equals).count());
      assertEquals(List.of("big", "big", "big", "big"), order.subList(5, 9));
    }
  }

  @Test
  void mediaRequestsHaveTheirOwnLaneAndABusyDispatchReturnsToPending() {
    try (var authority = new AuthorityTestContext(directory)) {
      var store = authority.store();
      String document =
          (String)
              authority
                  .uploadDocument(actor, "media.txt", "text/plain", bytes("Synthetic media"))
                  .get("document_id");
      var requests = new ImportIndexRepository(store);
      store.transaction(
          () -> {
            requests.insert(actor, document, "synthetic-sound-revision", "sound", null, null);
            return null;
          });
      var corpus = store.transaction(() -> requests.claim(false));
      assertTrue(corpus.isEmpty() || !corpus.get().pipeline().equals("sound"));
      var media = store.transaction(() -> requests.claim(true)).orElseThrow();
      assertEquals("sound", media.pipeline());
      assertTrue(store.transaction(() -> requests.claim(true)).isEmpty(), "dispatching is held");
      store.transaction(
          () -> {
            requests.release(media.revisionId());
            return null;
          });
      assertEquals(
          media.revisionId(),
          store.transaction(() -> requests.claim(true)).orElseThrow().revisionId());
    }
  }

  @Test
  void queueCommitsSignalWaitersAndTheWorkspaceOriginalCapIsConfigurable() {
    try (var authority = new AuthorityTestContext(directory)) {
      var store = authority.store();
      assertEquals(10L * 1024 * 1024 * 1024, store.workspaceOriginalBytes());
      assertThrows(IllegalArgumentException.class, () -> store.configureWorkspaceOriginalBytes(1));
      var wakeup = com.evidence.rag.model.domain.TaskWakeup.global();
      long before = wakeup.generation();
      authority.uploadDocument(actor, "signal.txt", "text/plain", bytes("Synthetic signal"));
      assertTrue(wakeup.generation() > before, "a committed queue insert signals");
      long quiet = wakeup.generation();
      store.transaction(() -> store.count("SELECT COUNT(*) FROM ingestion_jobs"));
      assertEquals(quiet, wakeup.generation(), "a read-only transaction does not");

      store.configureWorkspaceOriginalBytes(1024 * 1024);
      byte[] large = new byte[700 * 1024];
      java.util.Arrays.fill(large, (byte) 'a');
      authority.uploadDocument(actor, "large-a.txt", "text/plain", large);
      large[0] = 'b';
      var refused =
          assertThrows(
              ApplicationException.class,
              () -> authority.uploadDocument(actor, "large-b.txt", "text/plain", large));
      assertNotNull(refused.code());
      store.configureWorkspaceOriginalBytes(2L * 1024 * 1024);
      authority.uploadDocument(actor, "large-b.txt", "text/plain", large);
    }
  }
}
