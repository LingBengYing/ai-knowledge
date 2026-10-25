package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.*;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.model.dto.DocumentBatchCommand;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.support.PublishedCorpusFixture;
import com.evidence.rag.tool.parser.TextParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentBatchConstructionTest {
  @TempDir Path directory;
  final Actor actor = new Actor("org-main", "owner");

  static byte[] data(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  static DocumentBatchCommand.File file(String name, String text, String path) {
    byte[] bytes = data(text);
    return new DocumentBatchCommand.File(name, bytes.length, ModelValues.sha256(bytes), path);
  }

  @Test
  void sourcesUseCurrentPublishedBatchRevisionsAndPageBeforeCompiling() throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      var service = new DocumentBatchService(authority.store(), authority.ingestion(), 10000);
      var batch =
          service.create(
              actor,
              new DocumentBatchCommand(
                  "construction",
                  List.of(
                      file("a.txt", "alpha", "manual/a.txt"),
                      file("b.txt", "beta", "manual/part/b.txt"),
                      file("c.txt", "gamma", null),
                      file("pending.txt", "pending", null)),
                  100));
      for (int i = 0; i < 3; i++) {
        service.upload(actor, batch.batchId(), i, data(List.of("alpha", "beta", "gamma").get(i)));
        publishNext(authority);
      }
      var all = service.sources(actor, batch.batchId(), "", "", 0, 2);
      assertEquals(3, all.total());
      assertEquals(2, all.items().size());
      assertEquals(List.of("a.txt", "b.txt"), all.items().stream().map(i -> i.filename()).toList());
      assertNotNull(all.items().getFirst().publicationId());
      assertEquals(
          "c.txt",
          service.sources(actor, batch.batchId(), "", "", 2, 2).items().getFirst().filename());
      assertEquals(2, service.sources(actor, batch.batchId(), "", "manual", 0, 50).total());
      assertEquals(1, service.sources(actor, batch.batchId(), "B.TXT", "manual", 0, 50).total());
      assertEquals(0, service.sources(actor, batch.batchId(), "%", "", 0, 50).total());
      assertEquals(3, all.folders().stream().mapToInt(f -> f.count()).sum());
      String withdrawn = all.items().getFirst().documentId();
      try (var connection =
              java.sql.DriverManager.getConnection(
                  "jdbc:sqlite:" + directory.resolve("java-library.db"));
          var statement =
              connection.prepareStatement("INSERT INTO document_tombstones VALUES(?,?,?,?)")) {
        statement.setString(1, withdrawn);
        statement.setString(2, actor.workspaceId());
        statement.setString(3, actor.principalId());
        statement.setString(4, "2026-10-25T00:00:00Z");
        statement.executeUpdate();
      }
      assertEquals(2, service.sources(actor, batch.batchId(), "", "", 0, 50).total());
      assertThrows(
          ApplicationException.class,
          () -> service.sources(new Actor("other", "owner"), batch.batchId(), "", "", 0, 50));
      assertThrows(
          ApplicationException.class,
          () -> service.sources(actor, batch.batchId(), "", "../manual", 0, 50));
      assertThrows(
          ApplicationException.class,
          () -> service.sources(actor, batch.batchId(), "", "", 0, 101));
    }
  }

  @Test
  void destinationAndPathsPersistButNeverMoveSkippedDuplicates() {
    String batchId, target;
    try (var authority = new AuthorityTestContext(directory)) {
      target = authority.management().createFolder(actor, "教材").folderId();
      var service = new DocumentBatchService(authority.store(), authority.ingestion(), 10000);
      var command =
          new DocumentBatchCommand(
              "placement",
              List.of(file("a.txt", "alpha", "课程/a.txt"), file("b.txt", "beta", null)),
              100,
              null,
              null,
              null,
              target,
              true);
      var batch = service.create(actor, command);
      batchId = batch.batchId();
      assertEquals(target, batch.targetFolderId());
      assertTrue(batch.preservePaths());
      var first = service.upload(actor, batchId, 0, data("alpha"));
      var second = service.upload(actor, batchId, 1, data("beta"));
      var docs =
          authority
              .management()
              .listDocuments(
                  actor, com.evidence.rag.web.converter.ManagementRequestMapper.query(Map.of()));
      assertEquals(
          "教材/课程",
          docs.items().stream()
              .filter(d -> d.documentId().equals(first.documentId()))
              .findFirst()
              .orElseThrow()
              .folderName());
      assertEquals(
          target,
          docs.items().stream()
              .filter(d -> d.documentId().equals(second.documentId()))
              .findFirst()
              .orElseThrow()
              .folderId());
      assertEquals(batchId, service.create(actor, command).batchId());
      assertThrows(
          ApplicationException.class,
          () ->
              service.create(
                  actor,
                  new DocumentBatchCommand(
                      "placement", command.files(), 100, null, null, null, target, false)));
      var duplicate =
          service.create(
              actor,
              new DocumentBatchCommand(
                  "duplicates",
                  List.of(file("copy.txt", "alpha", "another/copy.txt")),
                  100,
                  null,
                  null,
                  null,
                  target,
                  true));
      assertEquals("duplicate", duplicate.items().getFirst().state());
      assertThrows(
          ApplicationException.class,
          () -> service.upload(actor, duplicate.batchId(), 0, data("alpha")));
      assertFalse(
          authority.management().listFolders(actor).stream()
              .anyMatch(f -> f.name().equals("教材/another")));
      assertThrows(
          ApplicationException.class,
          () ->
              service.create(
                  actor,
                  new DocumentBatchCommand(
                      "long-path",
                      List.of(file("c.txt", "gamma", "x".repeat(80) + "/c.txt")),
                      100,
                      null,
                      null,
                      null,
                      target,
                      true)));
    }
    try (var authority = new AuthorityTestContext(directory)) {
      var service = new DocumentBatchService(authority.store(), authority.ingestion(), 10000);
      var recovered = service.status(actor, batchId, 0, 100);
      assertEquals(target, recovered.targetFolderId());
      assertEquals("教材", recovered.targetFolderName());
      assertTrue(recovered.preservePaths());
    }
  }

  private void publishNext(AuthorityTestContext authority) {
    var parsed = authority.ingestion().claimIngestion(actor.workspaceId()).orElseThrow();
    assertTrue(
        authority
            .ingestion()
            .completeIngestion(
                parsed, new TextParser().parse(parsed.filename(), "text/plain", parsed.content())));
    authority.indexing().createIndexing(actor, parsed.documentId(), PublishedCorpusFixture.TARGET);
    var claim = authority.indexing().claimIndexing(actor.workspaceId()).orElseThrow();
    var digests = new LinkedHashMap<String, String>();
    for (String id : PublishedCorpusFixture.physicalIds(claim)) digests.put(id, "a".repeat(64));
    var manifest =
        new RetrievalProjection.RevisionManifest(
            actor.workspaceId(), claim.documentId(), claim.projectionGenerationId(), digests);
    assertTrue(
        authority
            .indexing()
            .completeIndexing(
                claim,
                digests,
                new VerifiedRevision(
                    PublishedCorpusFixture.TARGET.projectionIdentity(),
                    manifest.sha256(),
                    digests.size())));
  }

  @Test
  void streamingAppendKeepsPlacementAndRejectsUnrepresentableFoldersBeforeUpload() {
    try (var authority = new AuthorityTestContext(directory)) {
      var service = new DocumentBatchService(authority.store(), authority.ingestion(), 10000);
      var target = authority.management().createFolder(actor, "课件");
      var command =
          new DocumentBatchCommand(
              "stream-placement", null, 100, null, true, 2, target.folderId(), true);
      String id = service.create(actor, command).batchId();
      String hash = ModelValues.sha256(data("sample"));
      assertThrows(
          ApplicationException.class,
          () ->
              service.append(
                  actor,
                  id,
                  new com.evidence.rag.model.dto.DocumentBatchAppendCommand(
                      List.of(
                          new com.evidence.rag.model.dto.DocumentBatchAppendCommand.Entry(
                              0,
                              "sample.txt",
                              data("sample").length,
                              hash,
                              "x".repeat(80) + "/sample.txt")))));
      assertEquals(0, service.status(actor, id, 0, 100).total());
      service.append(
          actor,
          id,
          new com.evidence.rag.model.dto.DocumentBatchAppendCommand(
              List.of(
                  new com.evidence.rag.model.dto.DocumentBatchAppendCommand.Entry(
                      0, "sample.txt", data("sample").length, hash, "基础/sample.txt"))));
      service.upload(actor, id, 0, data("sample"));
      assertTrue(
          authority.management().listFolders(actor).stream()
              .anyMatch(f -> f.name().equals("课件/基础")));
      service.seal(actor, id);
      assertTrue(service.status(actor, id, 0, 100).preservePaths());
      assertFalse(service.status(actor, id, 0, 100).open());
      assertThrows(
          ApplicationException.class,
          () ->
              service.create(
                  new Actor("other", "owner"),
                  new DocumentBatchCommand(
                      "wrong-org", null, 100, null, true, 1, target.folderId(), false)));
    }
  }
}
