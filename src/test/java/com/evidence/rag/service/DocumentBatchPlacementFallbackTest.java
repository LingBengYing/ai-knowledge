package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.*;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.dto.DocumentBatchCommand;
import com.evidence.rag.model.dto.DocumentResult;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.web.converter.ManagementRequestMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Placement follows the target folder as it is now and never strands an upload. */
class DocumentBatchPlacementFallbackTest {
  @TempDir Path directory;
  final Actor actor = new Actor("org-main", "owner");

  static byte[] data(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  static DocumentBatchCommand.File file(String name, String text, String path) {
    return new DocumentBatchCommand.File(
        name, data(text).length, ModelValues.sha256(data(text)), path);
  }

  static DocumentBatchCommand command(String request, String target, String path) {
    return new DocumentBatchCommand(
        request, List.of(file("a.txt", "alpha", path)), 100, null, null, null, target, true);
  }

  private static DocumentResult document(AuthorityTestContext authority, Actor actor, String id) {
    return authority
        .management()
        .listDocuments(actor, ManagementRequestMapper.query(Map.of()))
        .items()
        .stream()
        .filter(item -> item.documentId().equals(id))
        .findFirst()
        .orElseThrow();
  }

  private static long skips(AuthorityTestContext authority, Actor actor, String documentId) {
    var audit = new ManagementRepository(authority.store());
    return authority
        .store()
        .transaction(
            () ->
                audit.findAudit(actor).stream()
                    .filter(
                        event ->
                            event.entityId().equals(documentId)
                                && event.action().equals("batch_placement_skipped"))
                    .count());
  }

  @Test
  void removedTargetLeavesTheFileUnfiledAndRecordsTheSkip() {
    try (var authority = new AuthorityTestContext(directory)) {
      var service = new DocumentBatchService(authority.store(), authority.ingestion(), 10000);
      var target = authority.management().createFolder(actor, "Inbox");
      var batch = service.create(actor, command("removed-target", target.folderId(), "sub/a.txt"));
      authority.management().removeFolder(actor, target.folderId());
      var task = service.upload(actor, batch.batchId(), 0, data("alpha"));
      var placed = document(authority, actor, task.documentId());
      assertNull(placed.folderId());
      assertEquals(1, skips(authority, actor, task.documentId()));
      assertTrue(authority.management().listFolders(actor).isEmpty());
      var status = service.status(actor, batch.batchId(), 0, 100);
      assertEquals(target.folderId(), status.targetFolderId());
      assertNull(status.targetFolderName());
      // A replayed upload is idempotent and records nothing twice.
      service.upload(actor, batch.batchId(), 0, data("alpha"));
      assertEquals(1, skips(authority, actor, task.documentId()));
    }
  }

  @Test
  void renamedTargetIsFollowedForPreservedSubfoldersAndStatus() {
    try (var authority = new AuthorityTestContext(directory)) {
      var service = new DocumentBatchService(authority.store(), authority.ingestion(), 10000);
      var target = authority.management().createFolder(actor, "Old");
      var batch = service.create(actor, command("renamed-target", target.folderId(), "sub/a.txt"));
      authority.management().renameFolder(actor, target.folderId(), "New");
      var task = service.upload(actor, batch.batchId(), 0, data("alpha"));
      var names = authority.management().listFolders(actor).stream().map(f -> f.name()).toList();
      assertEquals(List.of("New", "New/sub"), names.stream().sorted().toList());
      assertEquals("New/sub", document(authority, actor, task.documentId()).folderName());
      assertEquals("New", service.status(actor, batch.batchId(), 0, 100).targetFolderName());
    }
  }

  @Test
  void renameThatNoLongerFitsTheFolderLimitFilesIntoTheTargetItself() {
    try (var authority = new AuthorityTestContext(directory)) {
      var service = new DocumentBatchService(authority.store(), authority.ingestion(), 10000);
      var target = authority.management().createFolder(actor, "T");
      var batch =
          service.create(
              actor, command("long-rename", target.folderId(), "x".repeat(70) + "/a.txt"));
      authority.management().renameFolder(actor, target.folderId(), "y".repeat(20));
      var task = service.upload(actor, batch.batchId(), 0, data("alpha"));
      assertEquals(target.folderId(), document(authority, actor, task.documentId()).folderId());
      assertEquals(1, skips(authority, actor, task.documentId()));
    }
  }
}
