package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.*;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.dto.DocumentBatchAppendCommand;
import com.evidence.rag.model.dto.DocumentBatchCommand;
import com.evidence.rag.support.AuthorityTestContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** v42: open batches grow by appended manifest chunks while files upload, then are sealed. */
class DocumentBatchStreamingTest {
  @TempDir Path directory;
  final Actor actor = new Actor("org-main", "owner");

  static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  static DocumentBatchAppendCommand.Entry entry(int ordinal, String name, byte[] data) {
    return entry(ordinal, name, data, null);
  }

  static DocumentBatchAppendCommand.Entry entry(
      int ordinal, String name, byte[] data, String path) {
    return new DocumentBatchAppendCommand.Entry(
        ordinal, name, data.length, ModelValues.sha256(data), path);
  }

  static DocumentBatchAppendCommand append(DocumentBatchAppendCommand.Entry... entries) {
    return new DocumentBatchAppendCommand(List.of(entries));
  }

  static String code(Runnable action) {
    return assertThrows(ApplicationException.class, action::run).code();
  }

  @Test
  void entriesAppendInChunksUploadBeforeTheLastChunkAndTheBatchCompletesOnlyAfterSeal()
      throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      var batches = new DocumentBatchService(authority.store(), authority.ingestion(), 10000);
      var open = batches.create(actor, DocumentBatchCommand.open("open-1", 3, 50, null));
      String id = open.batchId();
      assertTrue(open.open());
      assertEquals(3, open.expectedTotal());
      assertEquals(0, open.total());
      // An open batch with nothing registered waits for uploads; it is never completed.
      assertEquals("awaiting_upload", open.state());
      assertTrue(open.availableActions().contains("seal"));
      assertTrue(open.availableActions().contains("cancel"));
      // The same request ID and options return the same batch; different options conflict.
      assertEquals(
          id, batches.create(actor, DocumentBatchCommand.open("open-1", 3, 50, null)).batchId());
      assertEquals(
          "batch_upload_conflict",
          code(() -> batches.create(actor, DocumentBatchCommand.open("open-1", 4, 50, null))));

      var first = batches.append(actor, id, append(entry(0, "a.txt", bytes("a"), "dir/a.txt")));
      assertEquals(1, first.registered());
      assertTrue(first.open());
      assertEquals("awaiting_upload", first.items().getFirst().state());
      // The first chunk uploads before the rest of the manifest exists.
      batches.upload(actor, id, 0, bytes("a"));
      var middle = batches.status(actor, id, 0, 100);
      assertEquals(1, middle.total());
      assertEquals("processing", middle.state());
      assertEquals("dir/a.txt", middle.items().getFirst().relativePath());

      batches.append(
          actor, id, append(entry(1, "b.txt", bytes("b")), entry(2, "c.txt", bytes("c"))));
      assertEquals(3, batches.status(actor, id, 0, 100).total());
      var list = batches.list(actor, 0, 20).items().getFirst();
      assertTrue(list.open());
      assertEquals(3, list.expectedTotal());

      var sealed = batches.seal(actor, id);
      assertEquals(1, sealed.affected());
      assertFalse(sealed.batch().open());
      assertFalse(sealed.batch().availableActions().contains("seal"));
      assertEquals(3, sealed.batch().expectedTotal());
      // Sealing again is harmless.
      assertEquals(0, batches.seal(actor, id).affected());
      assertFalse(batches.list(actor, 0, 20).items().getFirst().open());
    }
  }

  @Test
  void anOpenBatchWhoseFilesAreAllDoneIsNotCompletedUntilSealed() throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      var batches = new DocumentBatchService(authority.store(), authority.ingestion(), 10000);
      // A document already in the library: appended copies of it are skipped (settled at once).
      String library =
          batches
              .create(
                  actor,
                  new DocumentBatchCommand(
                      "library",
                      List.of(
                          new DocumentBatchCommand.File(
                              "x.txt", 1, ModelValues.sha256(bytes("x")))),
                      10))
              .batchId();
      batches.upload(actor, library, 0, bytes("x"));
      String id =
          batches.create(actor, DocumentBatchCommand.open("open-dup", 2, null, null)).batchId();
      var appended = batches.append(actor, id, append(entry(0, "copy.txt", bytes("x"))));
      assertEquals("duplicate", appended.items().getFirst().state());
      assertNotNull(appended.items().getFirst().duplicateOfDocumentId());
      var before = batches.status(actor, id, 0, 100);
      assertEquals(1L, before.counts().get("duplicate"));
      assertEquals(
          "awaiting_upload", before.state(), "every registered file is done, but more may come");
      assertEquals("awaiting_upload", batches.list(actor, 0, 1).items().getFirst().state());
      // Sealed with fewer files than announced: the batch is complete as it stands.
      var sealed = batches.seal(actor, id).batch();
      assertEquals("completed", sealed.state());
      assertEquals(2, sealed.expectedTotal());
      assertEquals(1, sealed.total());
    }
  }

  @Test
  void appendsAreIdempotentPerOrdinalAndRejectConflictsGapsOverflowAndSealedOrCancelledBatches()
      throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      var batches = new DocumentBatchService(authority.store(), authority.ingestion(), 10000);
      String id = batches.create(actor, DocumentBatchCommand.open("idem", 3, null, true)).batchId();
      var chunk = append(entry(0, "a.txt", bytes("a")), entry(1, "b.txt", bytes("b")));
      batches.append(actor, id, chunk);
      // A replay of a lost reply succeeds and changes nothing.
      var replay = batches.append(actor, id, chunk);
      assertEquals(2, replay.registered());
      assertEquals(List.of(0, 1), replay.items().stream().map(i -> i.ordinal()).toList());
      // A partly registered chunk: the known prefix is checked, the rest is added.
      var overlap =
          batches.append(
              actor, id, append(entry(1, "b.txt", bytes("b")), entry(2, "c.txt", bytes("c"))));
      assertEquals(3, overlap.registered());
      assertEquals(3, batches.status(actor, id, 0, 100).total());
      // The same ordinal with different bytes, name or path conflicts.
      assertEquals(
          "batch_upload_conflict",
          code(() -> batches.append(actor, id, append(entry(1, "b.txt", bytes("B"))))));
      assertEquals(
          "batch_upload_conflict",
          code(() -> batches.append(actor, id, append(entry(1, "bb.txt", bytes("b"))))));
      assertEquals(
          "batch_upload_conflict",
          code(() -> batches.append(actor, id, append(entry(1, "b.txt", bytes("b"), "d/b.txt")))));
      // More than announced is refused.
      assertEquals(
          "invalid_request",
          invalidCode(() -> batches.append(actor, id, append(entry(3, "d.txt", bytes("d"))))));

      String other =
          batches.create(actor, DocumentBatchCommand.open("gap", 5, null, true)).batchId();
      var gap =
          assertThrows(
              ApplicationException.class,
              () -> batches.append(actor, other, append(entry(1, "b.txt", bytes("b")))));
      assertEquals("batch_manifest_gap", gap.code());
      assertEquals(FailureKind.CONFLICT, gap.kind());
      // Ordinals within one request must be consecutive.
      assertThrows(
          ApplicationException.class,
          () ->
              batches.append(
                  actor,
                  other,
                  append(entry(0, "a.txt", bytes("a")), entry(2, "c.txt", bytes("c")))));
      batches.append(actor, other, append(entry(0, "a.txt", bytes("a"))));
      batches.seal(actor, other);
      assertEquals(
          "batch_sealed",
          code(() -> batches.append(actor, other, append(entry(1, "b.txt", bytes("b"))))));
      // A replay after the seal still succeeds.
      assertEquals(
          1, batches.append(actor, other, append(entry(0, "a.txt", bytes("a")))).registered());

      String cancelled =
          batches.create(actor, DocumentBatchCommand.open("cancel", 5, null, true)).batchId();
      batches.append(actor, cancelled, append(entry(0, "a.txt", bytes("a"))));
      var cancel = batches.cancel(actor, cancelled);
      assertEquals("cancelled", cancel.batch().state());
      assertEquals(
          "batch_action_conflict",
          code(() -> batches.append(actor, cancelled, append(entry(1, "b.txt", bytes("b"))))));
      // Resume clears the cancel; registration continues.
      batches.resume(actor, cancelled, null);
      assertEquals(
          2, batches.append(actor, cancelled, append(entry(1, "b.txt", bytes("b")))).registered());

      // A one-shot manifest cannot be appended to or sealed, and keeps its old contract.
      String oneShot =
          batches
              .create(
                  actor,
                  new DocumentBatchCommand(
                      "one-shot",
                      List.of(
                          new DocumentBatchCommand.File(
                              "a.txt", 1, ModelValues.sha256(bytes("a")))),
                      10,
                      true))
              .batchId();
      assertEquals(
          "batch_not_open",
          code(() -> batches.append(actor, oneShot, append(entry(1, "b.txt", bytes("b"))))));
      assertEquals("batch_not_open", code(() -> batches.seal(actor, oneShot)));
      var plain = batches.status(actor, oneShot, 0, 100);
      assertFalse(plain.open());
      assertNull(plain.expectedTotal());
      assertFalse(plain.availableActions().contains("seal"));
      // Another workspace sees none of it.
      var stranger = new Actor("other-org", "owner");
      assertThrows(ApplicationException.class, () -> batches.append(stranger, id, chunk));
      assertThrows(ApplicationException.class, () -> batches.seal(stranger, id));
    }
  }

  static String invalidCode(Runnable action) {
    var failure = assertThrows(ApplicationException.class, action::run);
    assertEquals(FailureKind.INVALID_INPUT, failure.kind());
    return "invalid_request";
  }

  @Test
  void invalidOpenRequestsAreRejected() throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      var batches = new DocumentBatchService(authority.store(), authority.ingestion(), 100);
      for (var command :
          List.of(
              DocumentBatchCommand.open("zero", 0, null, null),
              DocumentBatchCommand.open("over", 101, null, null),
              new DocumentBatchCommand("no-total", null, null, null, true, null),
              new DocumentBatchCommand(
                  "files",
                  List.of(new DocumentBatchCommand.File("a.txt", 1, "a".repeat(64))),
                  null,
                  null,
                  true,
                  2),
              // An announced total without open is not a one-shot manifest either.
              new DocumentBatchCommand(
                  "total-only",
                  List.of(new DocumentBatchCommand.File("a.txt", 1, "a".repeat(64))),
                  null,
                  null,
                  null,
                  2)))
        assertThrows(ApplicationException.class, () -> batches.create(actor, command));
      String id = batches.create(actor, DocumentBatchCommand.open("ok", 100, null, null)).batchId();
      var tooMany =
          new DocumentBatchAppendCommand(
              IntStream.range(0, DocumentBatchService.MAX_APPEND + 1)
                  .mapToObj(i -> entry(i, "f" + i + ".txt", bytes("f" + i)))
                  .toList());
      assertThrows(ApplicationException.class, () -> batches.append(actor, id, tooMany));
      var bad = new ArrayList<DocumentBatchAppendCommand.Entry>();
      bad.add(new DocumentBatchAppendCommand.Entry(null, "a.txt", 1, "a".repeat(64), null));
      assertThrows(
          ApplicationException.class,
          () -> batches.append(actor, id, new DocumentBatchAppendCommand(bad)));
      assertThrows(
          ApplicationException.class,
          () ->
              batches.append(
                  actor,
                  id,
                  append(
                      new DocumentBatchAppendCommand.Entry(0, "a.txt", 1, "A".repeat(64), null))));
      assertThrows(
          ApplicationException.class,
          () -> batches.append(actor, id, append(entry(0, "a.txt", bytes("a"), "../a.txt"))));
      assertThrows(
          ApplicationException.class,
          () -> batches.append(actor, id, new DocumentBatchAppendCommand(List.of())));
      assertEquals(0, batches.status(actor, id, 0, 100).total());
    }
  }

  @Test
  void appendedEntriesAreDedupedAgainstTheLibraryEarlierChunksAndTheSameChunk() throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      var batches = new DocumentBatchService(authority.store(), authority.ingestion(), 10000);
      String library =
          batches
              .create(
                  actor,
                  new DocumentBatchCommand(
                      "library",
                      List.of(
                          new DocumentBatchCommand.File(
                              "lib.txt", 3, ModelValues.sha256(bytes("lib")))),
                      10))
              .batchId();
      batches.upload(actor, library, 0, bytes("lib"));
      String id =
          batches.create(actor, DocumentBatchCommand.open("dedupe", 6, null, null)).batchId();
      var first =
          batches.append(
              actor,
              id,
              append(entry(0, "a.txt", bytes("a")), entry(1, "lib-copy.txt", bytes("lib"))));
      assertEquals(
          List.of("awaiting_upload", "duplicate"),
          first.items().stream().map(i -> i.state()).toList());
      var second =
          batches.append(
              actor,
              id,
              append(
                  entry(2, "a-again.txt", bytes("a")),
                  entry(3, "n.txt", bytes("n")),
                  entry(4, "n2.txt", bytes("n"))));
      assertEquals(
          List.of("duplicate", "awaiting_upload", "duplicate"),
          second.items().stream().map(i -> i.state()).toList());
      assertEquals(0, second.items().get(0).duplicateOfOrdinal(), "repeats an earlier chunk");
      assertEquals(3, second.items().get(2).duplicateOfOrdinal(), "repeats the same chunk");
      // Once the earlier file is uploaded, a later copy names its document.
      batches.upload(actor, id, 0, bytes("a"));
      var third = batches.append(actor, id, append(entry(5, "a-third.txt", bytes("a"))));
      assertEquals("duplicate", third.items().getFirst().state());
      assertNotNull(third.items().getFirst().duplicateOfDocumentId());
      var status = batches.status(actor, id, 0, 100);
      assertEquals(4L, status.counts().get("duplicate"));
      assertThrows(ApplicationException.class, () -> batches.upload(actor, id, 2, bytes("a")));
      // Opting in keeps every copy.
      String all =
          batches.create(actor, DocumentBatchCommand.open("keep", 2, null, true)).batchId();
      var kept =
          batches.append(
              actor, all, append(entry(0, "a.txt", bytes("a")), entry(1, "b.txt", bytes("a"))));
      assertEquals(
          List.of("awaiting_upload", "awaiting_upload"),
          kept.items().stream().map(i -> i.state()).toList());
    }
  }

  @Test
  void anOpenBatchWithManyChunksMatchesTheOneShotManifestAndStaysFast() throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      var batches = new DocumentBatchService(authority.store(), authority.ingestion(), 10000);
      int total = 3827;
      String id =
          batches.create(actor, DocumentBatchCommand.open("big", total, null, null)).batchId();
      long started = System.nanoTime();
      for (int base = 0; base < total; base += 200) {
        var entries = new ArrayList<DocumentBatchAppendCommand.Entry>();
        for (int i = base; i < Math.min(total, base + 200); i++)
          entries.add(
              entry(
                  i,
                  "ted-" + i + ".xml",
                  bytes("<doc>" + (i % 3000) + "</doc>"),
                  "ted/ted-" + i + ".xml"));
        batches.append(actor, id, new DocumentBatchAppendCommand(entries));
      }
      long millis = (System.nanoTime() - started) / 1_000_000;
      batches.seal(actor, id);
      var status = batches.status(actor, id, 0, 1);
      assertEquals(total, status.total());
      assertEquals(827L, status.counts().get("duplicate"));
      assertEquals(3000L, status.counts().get("awaiting_upload"));
      assertTrue(millis < 20000, "appending " + total + " entries took " + millis + " ms");
    }
  }
}
