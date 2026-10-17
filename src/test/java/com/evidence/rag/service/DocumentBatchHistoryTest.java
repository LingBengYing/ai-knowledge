package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.*;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.BatchImportFile;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.dto.DocumentBatchCommand;
import com.evidence.rag.support.AuthorityTestContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** v41: import history list, state-filtered pages, folder paths and per-file times. */
class DocumentBatchHistoryTest {
  @TempDir Path directory;
  final Actor actor = new Actor("org-main", "owner");

  static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  static DocumentBatchCommand.File file(String name, byte[] data, String path) {
    return new DocumentBatchCommand.File(name, data.length, ModelValues.sha256(data), path);
  }

  DocumentBatchCommand command(String request, List<DocumentBatchCommand.File> files) {
    return new DocumentBatchCommand(request, files, 100, null);
  }

  @Test
  void historyListsTheWorkspacesBatchesNewestFirstWithPagingAndNothingFromOtherWorkspaces()
      throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      var batches = new DocumentBatchService(authority.store(), authority.ingestion(), 10000);
      var ids = new java.util.ArrayList<String>();
      for (int i = 0; i < 3; i++) {
        ids.add(
            batches
                .create(
                    actor, command("history-" + i, List.of(file("a.txt", bytes("h" + i), null))))
                .batchId());
        Thread.sleep(5);
      }
      var first = batches.list(actor, 0, 2);
      assertEquals(3, first.total());
      assertEquals(2, first.items().size());
      assertEquals(
          List.of(ids.get(2), ids.get(1)), first.items().stream().map(i -> i.batchId()).toList());
      var rest = batches.list(actor, 2, 2);
      assertEquals(List.of(ids.get(0)), rest.items().stream().map(i -> i.batchId()).toList());
      var item = rest.items().getFirst();
      assertEquals("awaiting_upload", item.state());
      assertEquals(1, item.total());
      assertEquals(1L, item.counts().get("awaiting_upload"));
      assertNotNull(item.createdAt());
      assertNotNull(item.updatedAt());
      assertNull(item.haltedReason());
      // Batches belong to the workspace, as every other batch operation authorizes them.
      var colleague = new Actor("org-main", "colleague");
      assertEquals(3, batches.list(colleague, 0, 20).total());
      var stranger = new Actor("other-org", "owner");
      assertEquals(0, batches.list(stranger, 0, 20).total());
      assertTrue(batches.list(stranger, 0, 20).items().isEmpty());
      for (int[] invalid : new int[][] {{-1, 20}, {0, 0}, {0, 101}})
        assertThrows(ApplicationException.class, () -> batches.list(actor, invalid[0], invalid[1]));
      // A cancel is a change the history shows.
      String before = batches.list(actor, 2, 1).items().getFirst().updatedAt();
      Thread.sleep(5);
      batches.cancel(actor, ids.get(0));
      var cancelled = batches.list(actor, 2, 1).items().getFirst();
      assertEquals("cancelled", cancelled.state());
      assertEquals("batch_cancelled", cancelled.haltedReason());
      assertTrue(cancelled.updatedAt().compareTo(before) > 0, cancelled.updatedAt());
    }
  }

  @Test
  void stateFilterPagesOnlyMatchingFilesWhileCountsDescribeTheWholeBatch() {
    try (var authority = new AuthorityTestContext(directory)) {
      var batches = new DocumentBatchService(authority.store(), authority.ingestion(), 10000);
      byte[] same = bytes("same content"), other = bytes("other content");
      var files = new java.util.ArrayList<DocumentBatchCommand.File>();
      for (int i = 0; i < 5; i++) files.add(file("same-" + i + ".txt", same, null));
      files.add(file("other.txt", other, null));
      var batch = batches.create(actor, command("filtered", files));
      String id = batch.batchId();
      assertEquals(4L, batch.counts().get("duplicate"));
      batches.upload(actor, id, 0, same);
      var duplicates = batches.status(actor, id, List.of("duplicate"), 0, 3);
      assertEquals(4, duplicates.matched());
      assertEquals(6, duplicates.total());
      assertEquals(List.of("duplicate"), duplicates.stateFilter());
      assertEquals(List.of(1, 2, 3), duplicates.items().stream().map(f -> f.ordinal()).toList());
      assertEquals(
          List.of(4),
          batches.status(actor, id, List.of("duplicate"), 3, 3).items().stream()
              .map(f -> f.ordinal())
              .toList());
      var active = batches.status(actor, id, List.of("queued", "awaiting_upload"), 0, 100);
      assertEquals(2, active.matched());
      assertEquals(List.of(0, 5), active.items().stream().map(f -> f.ordinal()).toList());
      assertEquals(0, batches.status(actor, id, List.of("failed", "cancelled"), 0, 100).matched());
      assertTrue(batches.status(actor, id, List.of("failed"), 0, 100).items().isEmpty());
      var unfiltered = batches.status(actor, id, 0, 100);
      assertEquals(6, unfiltered.matched());
      assertEquals(List.of(), unfiltered.stateFilter());
      for (var invalid :
          List.of(List.of("bogus"), List.of(""), List.of("failed", "failed"), List.of("FAILED")))
        assertThrows(ApplicationException.class, () -> batches.status(actor, id, invalid, 0, 100));
    }
  }

  @Test
  void folderPathsAreValidatedStoredAndKeepPathlessManifestsUnchanged() {
    try (var authority = new AuthorityTestContext(directory)) {
      var batches = new DocumentBatchService(authority.store(), authority.ingestion(), 10000);
      byte[] a = bytes("alpha"), b = bytes("beta");
      var batch =
          batches.create(
              actor,
              command(
                  "paths",
                  List.of(
                      file("a.txt", a, "资料/第一章/a.txt"),
                      file("b.txt", b, "b.txt"),
                      file("c.txt", bytes("gamma"), null))));
      assertEquals(
          java.util.Arrays.asList("资料/第一章/a.txt", null, null),
          batch.items().stream().map(f -> f.relativePath()).toList());
      // Same manifest replayed: same batch. A different path is a different manifest.
      assertEquals(
          batch.batchId(),
          batches
              .create(
                  actor,
                  command(
                      "paths",
                      List.of(
                          file("a.txt", a, "资料/第一章/a.txt"),
                          file("b.txt", b, null),
                          file("c.txt", bytes("gamma"), "c.txt"))))
              .batchId());
      assertThrows(
          ApplicationException.class,
          () ->
              batches.create(
                  actor,
                  command(
                      "paths",
                      List.of(
                          file("a.txt", a, "资料/a.txt"),
                          file("b.txt", b, null),
                          file("c.txt", bytes("gamma"), null)))));
      for (String invalid :
          List.of(
              "../a.txt",
              "x/../a.txt",
              "./a.txt",
              "/abs/a.txt",
              "x//a.txt",
              "x\\a.txt",
              "x/\u0007/a.txt",
              "x/\na.txt",
              "x/b.txt",
              "",
              "d/".repeat(512) + "a.txt"))
        assertThrows(
            ApplicationException.class,
            () ->
                batches.create(
                    actor,
                    command("invalid-" + invalid.hashCode(), List.of(file("a.txt", a, invalid)))),
            invalid);
      assertEquals(
          "d/".repeat(509) + "a.txt",
          BatchImportFile.relativePath("d/".repeat(509) + "a.txt", "a.txt"));
    }
  }

  @Test
  void receivedAndFinishedTimesFeedTheRecentThroughputWindow() {
    try (var authority = new AuthorityTestContext(directory)) {
      var batches = new DocumentBatchService(authority.store(), authority.ingestion(), 10000);
      byte[] one = bytes("one"), two = bytes("two");
      Instant before = Instant.now().minusSeconds(1);
      String id =
          batches
              .create(
                  actor,
                  command("times", List.of(file("1.txt", one, null), file("2.txt", two, null))))
              .batchId();
      var fresh = batches.status(actor, id, 0, 100);
      assertNull(fresh.items().getFirst().startedAt());
      assertNull(fresh.items().getFirst().finishedAt());
      assertEquals(0, fresh.throughput().finished());
      assertTrue(fresh.throughput().windowSeconds() <= 600);
      assertNotNull(fresh.createdAt());
      assertNotNull(fresh.updatedAt());
      batches.upload(actor, id, 0, one);
      var received = batches.status(actor, id, 0, 100).items().getFirst();
      assertNotNull(received.startedAt());
      assertTrue(Instant.parse(received.startedAt()).isAfter(before));
      assertNull(received.finishedAt());
      // Cancelling stops the queued parse: that file is finished, the unuploaded one is not.
      batches.cancel(actor, id);
      var page = batches.status(actor, id, 0, 100);
      assertEquals("cancelled", page.items().get(0).state());
      assertNotNull(page.items().get(0).finishedAt());
      assertTrue(Instant.parse(page.items().get(0).finishedAt()).isAfter(before));
      assertNull(page.items().get(1).finishedAt());
      assertEquals(1, page.throughput().finished());
      assertNotNull(page.throughput().measuredAt());
      // Resuming requeues it: it is no longer finished, and leaves the window's count.
      batches.resume(actor, id, null);
      var resumed = batches.status(actor, id, 0, 100);
      assertEquals("queued", resumed.items().getFirst().state());
      assertNull(resumed.items().getFirst().finishedAt());
      assertEquals(0, resumed.throughput().finished());
    }
  }
}
