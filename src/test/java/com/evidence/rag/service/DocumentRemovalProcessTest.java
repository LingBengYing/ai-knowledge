package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.job.IndexingJob;
import com.evidence.rag.job.IngestionJob;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.repository.DocumentLifecycleRepository;
import com.evidence.rag.repository.IndexingRepository;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.worker.indexing.ControlledIndexerFixture;
import com.evidence.rag.worker.indexing.IndexingTestServer;
import com.evidence.rag.worker.indexing.ProcessTextIndexer;
import com.evidence.rag.worker.parser.ProcessTextParser;
import com.evidence.rag.worker.parser.SlowParserFixture;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("try")
class DocumentRemovalProcessTest {
  private static final Actor OWNER = new Actor("org-main", "owner");
  @TempDir Path directory;

  @Test
  void removedClaimCannotStartAnotherIndexWorker() {
    try (var authority = new AuthorityTestContext(directory)) {
      String document = parsed(authority, "removed.txt");
      var target = new IndexTarget("embedding-v1", "projection-v1", "models-v1", 2);
      authority.createIndexing(OWNER, document, target);
      var claim = authority.claimIndexing(OWNER.workspaceId()).orElseThrow();
      assertTrue(authority.isIndexingClaimCurrent(claim));
      lifecycle(authority).removeDocument(OWNER, document);
      assertFalse(authority.isIndexingClaimCurrent(claim));
      var starts = new AtomicInteger();
      var processor =
          new IndexingTaskProcessor(
              authority.indexing(),
              OWNER.workspaceId(),
              target,
              Duration.ofSeconds(30),
              limit -> {
                starts.incrementAndGet();
                throw new IllegalStateException("Controlled factory must not be entered");
              });

      processor.process(claim);

      assertEquals(0, starts.get(), "Removed original evidence must not enter a worker factory");
      assertFalse(authority.isIndexingClaimCurrent(claim));
    }
  }

  @Test
  void revokedClaimFailsWithoutAWorkerAndDoesNotBlockTheNextAuthorizedDocument() throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      String revoked = parsed(authority, "revoked.txt");
      var target = new IndexTarget("embedding-v1", "projection-v1", "models-v1", 2);
      var task = authority.indexing().createIndexing(OWNER, revoked, target);
      var claim = authority.claimIndexing(OWNER.workspaceId()).orElseThrow();
      // Test-only external ACL event; the removal path must retain existing revocation behavior.
      try (var database =
              DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
          var statement =
              database.prepareStatement(
                  "UPDATE document_acl SET role='reader' WHERE document_id=? AND principal_id=?")) {
        statement.setString(1, revoked);
        statement.setString(2, OWNER.principalId());
        assertEquals(1, statement.executeUpdate());
      }
      var starts = new AtomicInteger();
      var processor =
          new IndexingTaskProcessor(
              authority.indexing(),
              OWNER.workspaceId(),
              target,
              Duration.ofSeconds(30),
              limit -> {
                starts.incrementAndGet();
                throw new IllegalStateException("Controlled factory must not be entered");
              });
      processor.process(claim);
      String next = parsed(authority, "next.txt");
      authority.createIndexing(OWNER, next, target);
      assertAll(
          () -> assertEquals(0, starts.get()),
          () ->
              assertEquals(
                  "failed", authority.indexing().indexingStatus(OWNER, task.taskId()).state()),
          () ->
              assertEquals(
                  "authorization_changed",
                  authority.indexing().indexingStatus(OWNER, task.taskId()).errorCode()),
          () -> {
            var nextClaim = authority.claimIndexing(OWNER.workspaceId());
            assertTrue(nextClaim.isPresent(), "Revoked processing work must not block the queue");
            assertEquals(next, nextClaim.orElseThrow().documentId());
          });
    }
  }

  @Test
  void removalStopsTheActualParserBeforeItsDeadlineAndNextWorkWaitsForItsExit() throws Exception {
    Path database = directory.resolve("authority");
    Path pidFile = directory.resolve("parser.pid");
    var starts = new AtomicInteger();
    var overlapping = new AtomicBoolean();
    try (var authority = new AuthorityTestContext(database)) {
      var first =
          authority
              .ingestion()
              .uploadDocument(
                  OWNER, "removed.txt", "text/plain", "合成移除证据。".getBytes(StandardCharsets.UTF_8));
      var processor =
          new IngestionTaskProcessor(
              authority.ingestion(),
              OWNER.workspaceId(),
              Duration.ofSeconds(30),
              limit -> {
                if (starts.getAndIncrement() == 0) return SlowParserFixture.parser(limit, pidFile);
                overlapping.set(alive(pidFile));
                return new ProcessTextParser(limit);
              });
      try (var job = new IngestionJob(processor)) {
        await(() -> alive(pidFile), "Actual parser child must start");
        assertEquals(
            "processing", authority.ingestion().ingestionStatus(OWNER, first.taskId()).state());
        long removalUntil = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        lifecycle(authority).removeDocument(OWNER, first.documentId());
        assertNotFound(() -> authority.ingestion().ingestionStatus(OWNER, first.taskId()));
        var next =
            authority
                .ingestion()
                .uploadDocument(
                    OWNER, "next.txt", "text/plain", "下一份正常解析证据。".getBytes(StandardCharsets.UTF_8));
        awaitUntil(
            removalUntil,
            () -> !alive(pidFile),
            "Removal must terminate parser within five seconds, before its 30-second deadline");
        await(
            () ->
                "parsed"
                    .equals(authority.ingestion().ingestionStatus(OWNER, next.taskId()).state()),
            "Subsequent authorized parsing must complete");
        assertEquals(2, starts.get());
        assertFalse(overlapping.get());
        var old =
            authority
                .store()
                .transaction(
                    () ->
                        new IngestionRepository(authority.store())
                            .findInternalTask(first.taskId())
                            .orElseThrow());
        assertEquals("cancelled", old.state());
        assertEquals(
            0,
            scalar(
                database,
                "SELECT COUNT(*) FROM corpus_pages WHERE revision_id=?",
                first.revisionId()));
        assertEquals(
            0,
            scalar(
                database,
                "SELECT COUNT(*) FROM corpus_segments WHERE revision_id=?",
                first.revisionId()));
        assertNotFound(() -> authority.parsedEvidence(OWNER, first.documentId()));
        assertEquals(
            "下一份正常解析证据。",
            authority.parsedEvidence(OWNER, next.documentId()).pages().getFirst().text());
      }
      assertFalse(alive(pidFile));
    }
  }

  @Test
  void removalStopsTheActualIndexerBeforeItsDeadlineAndNextWorkDoesNotOverlap() throws Exception {
    Path database = directory.resolve("authority");
    Path pidFile = directory.resolve("indexer.pid");
    var starts = new AtomicInteger();
    var overlapping = new AtomicBoolean();
    try (var external = new IndexingTestServer();
        var authority = new AuthorityTestContext(database)) {
      String first = parsed(authority, "removed.txt");
      var task = authority.indexing().createIndexing(OWNER, first, external.target());
      var processor =
          new IndexingTaskProcessor(
              authority.indexing(),
              OWNER.workspaceId(),
              external.target(),
              Duration.ofSeconds(30),
              limit -> {
                if (starts.getAndIncrement() == 0) {
                  return ControlledIndexerFixture.indexer(
                      external.settings().models(),
                      external.settings().projection(),
                      limit,
                      "hang",
                      pidFile);
                }
                overlapping.set(alive(pidFile));
                return new ProcessTextIndexer(
                    external.settings().models(), external.settings().projection(), limit);
              });
      try (var job = new IndexingJob(processor)) {
        await(() -> alive(pidFile), "Actual indexing child must start");
        assertEquals(
            "processing", authority.indexing().indexingStatus(OWNER, task.taskId()).state());
        assertTrue(external.requests.isEmpty());
        long removalUntil = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        lifecycle(authority).removeDocument(OWNER, first);
        assertNotFound(() -> authority.indexing().indexingStatus(OWNER, task.taskId()));
        String next = parsed(authority, "next.txt");
        var nextTask = authority.indexing().createIndexing(OWNER, next, external.target());
        awaitUntil(
            removalUntil,
            () -> !alive(pidFile),
            "Removal must terminate indexer within five seconds, before its 30-second deadline");
        await(
            () ->
                "indexed"
                    .equals(authority.indexing().indexingStatus(OWNER, nextTask.taskId()).state()),
            "Subsequent authorized indexing must publish through the real worker");
        assertEquals(2, starts.get());
        assertFalse(overlapping.get());
        var old =
            authority
                .store()
                .transaction(
                    () ->
                        new IndexingRepository(authority.store())
                            .findInternalTask(task.taskId())
                            .orElseThrow());
        assertEquals("cancelled", old.state());
        assertEquals(
            0,
            scalar(database, "SELECT COUNT(*) FROM index_publications WHERE document_id=?", first));
        assertEquals(
            0,
            scalar(
                database,
                "SELECT COUNT(*) FROM active_corpus_publications WHERE document_id=?",
                first));
        assertEquals(
            1,
            scalar(
                database,
                "SELECT COUNT(*) FROM active_corpus_publications WHERE document_id=?",
                next));
        assertFalse(external.requests.isEmpty());
      }
      assertFalse(alive(pidFile));
    }
  }

  private static void assertNotFound(Runnable action) {
    assertEquals(
        FailureKind.NOT_FOUND, assertThrows(ApplicationException.class, action::run).kind());
  }

  private static long scalar(Path directory, String query, String id) throws Exception {
    Path database = directory.resolve("java-library.db");
    assertTrue(Files.isRegularFile(database));
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var statement = connection.prepareStatement(query)) {
      statement.setString(1, id);
      try (var result = statement.executeQuery()) {
        assertTrue(result.next());
        return result.getLong(1);
      }
    }
  }

  private static boolean alive(Path path) {
    try {
      if (!Files.isRegularFile(path)) return false;
      String value = Files.readString(path);
      return value.matches("[0-9]+")
          && ProcessHandle.of(Long.parseLong(value)).map(ProcessHandle::isAlive).orElse(false);
    } catch (java.io.IOException failure) {
      throw new AssertionError("Could not read the controlled child PID", failure);
    }
  }

  private static void await(BooleanSupplier condition, String message) throws InterruptedException {
    awaitUntil(System.nanoTime() + Duration.ofSeconds(5).toNanos(), condition, message);
  }

  private static void awaitUntil(long until, BooleanSupplier condition, String message)
      throws InterruptedException {
    while (System.nanoTime() < until) {
      if (condition.getAsBoolean()) return;
      Thread.sleep(10);
    }
    throw new AssertionError(message);
  }

  private static String parsed(AuthorityTestContext authority, String filename) {
    byte[] content = "合成生命周期证据。".getBytes(StandardCharsets.UTF_8);
    authority.uploadDocument(OWNER, filename, "text/plain", content);
    var claim = authority.claimIngestion(OWNER.workspaceId()).orElseThrow();
    assertTrue(
        authority.completeIngestion(
            claim, new TextParser().parse(filename, "text/plain", content)));
    return claim.documentId();
  }

  private static DocumentLifecycleService lifecycle(AuthorityTestContext authority) {
    var store = authority.store();
    return new DocumentLifecycleService(
        store,
        new DocumentLifecycleRepository(store),
        new ManagementRepository(store),
        new IngestionRepository(store),
        new IndexingRepository(store),
        new DocumentPermissionPolicy());
  }
}
