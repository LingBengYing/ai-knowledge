package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.job.IndexingJob;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.worker.indexing.ControlledIndexerFixture;
import com.evidence.rag.worker.indexing.IndexingTestServer;
import com.evidence.rag.worker.indexing.ProcessTextIndexer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// The real runtime is retained for its lifetime and explicitly closed in shutdown acceptance.
@SuppressWarnings("try")
class IndexingTaskProcessorTest {
  @TempDir Path directory;
  private final Actor owner = new Actor("org-main", "owner");

  @Test
  void immediateRetryWaitsForCancelledChildAndCannotPublishItsOldAttempt() throws Exception {
    Path pid = directory.resolve("cancel.pid");
    var starts = new AtomicInteger();
    try (var external = new IndexingTestServer();
        var authority = new AuthorityTestContext(directory.resolve("authority"));
        var runtime =
            new IndexingJob(
                new IndexingTaskProcessor(
                    authority.indexing(),
                    owner.workspaceId(),
                    external.target(),
                    Duration.ofSeconds(8),
                    limit -> {
                      if (starts.getAndIncrement() == 0) {
                        return child(external, limit, "hang", pid);
                      }
                      assertFalse(alive(pid), "A retry must not overlap the cancelled child");
                      return new ProcessTextIndexer(
                          external.settings().models(), external.settings().projection(), limit);
                    }))) {
      String document = parsed(authority, "cancel.txt");
      String task = task(authority, external, document);
      await(() -> alive(pid));
      assertEquals("cancelled", authority.cancelIndexing(owner, task).get("state"));
      assertNull(row(authority, document).get("active_revision_id"));
      assertEquals(2, authority.retryIndexing(owner, task, external.target()).get("attempt"));
      await(() -> "indexed".equals(authority.indexingStatus(owner, task).get("state")));
      assertFalse(alive(pid));
      assertEquals(2, starts.get());
      assertEquals(2, authority.indexingStatus(owner, task).get("attempt"));
      assertEquals("parsed", row(authority, document).get("status"));
      assertEquals(false, row(authority, document).get("can_answer"));
      assertNotNull(row(authority, document).get("active_revision_id"));
    }
  }

  @Test
  void shutdownTerminatesActualChildAndPersistsRetryableFailureWithoutReplayingQueue()
      throws Exception {
    Path pid = directory.resolve("shutdown.pid");
    try (var external = new IndexingTestServer();
        var authority = new AuthorityTestContext(directory.resolve("authority"));
        var runtime =
            new IndexingJob(
                new IndexingTaskProcessor(
                    authority.indexing(),
                    owner.workspaceId(),
                    external.target(),
                    Duration.ofSeconds(8),
                    limit -> child(external, limit, "hang", pid)))) {
      String document = parsed(authority, "shutdown.txt");
      String task = task(authority, external, document);
      await(() -> alive(pid));
      runtime.close();
      assertFalse(alive(pid));
      var failed = authority.indexingStatus(owner, task);
      assertEquals("failed", failed.get("state"));
      assertEquals("worker_interrupted", failed.get("error_code"));
      assertEquals(true, failed.get("can_retry"));
      assertNull(row(authority, document).get("active_revision_id"));
      String queued = task(authority, external, parsed(authority, "after-shutdown.txt"));
      Thread.sleep(350);
      assertEquals("queued", authority.indexingStatus(owner, queued).get("state"));
      assertTrue(external.requests.isEmpty());
      runtime.close();
    }
  }

  @Test
  void revokedWriterInterruptsRunningChildAndCannotPublish() throws Exception {
    Path pid = directory.resolve("revoked.pid");
    Path data = directory.resolve("authority");
    try (var external = new IndexingTestServer();
        var authority = new AuthorityTestContext(data);
        var runtime =
            new IndexingJob(
                new IndexingTaskProcessor(
                    authority.indexing(),
                    owner.workspaceId(),
                    external.target(),
                    Duration.ofSeconds(8),
                    limit -> child(external, limit, "hang", pid)))) {
      String document = parsed(authority, "revoked.txt");
      String task = task(authority, external, document);
      await(() -> alive(pid));
      // Controlled external ACL transition; no lifecycle HTTP endpoint exists in this slice.
      try (var connection =
              DriverManager.getConnection("jdbc:sqlite:" + data.resolve("java-library.db"));
          var statement =
              connection.prepareStatement(
                  "UPDATE document_acl SET role='reader' WHERE document_id=? AND principal_id=?")) {
        statement.setString(1, document);
        statement.setString(2, owner.principalId());
        assertEquals(1, statement.executeUpdate());
      }
      await(() -> "failed".equals(authority.indexingStatus(owner, task).get("state")));
      assertFalse(alive(pid));
      assertEquals(false, authority.indexingStatus(owner, task).get("can_retry"));
      assertNull(row(authority, document).get("active_revision_id"));
      assertTrue(external.requests.isEmpty());
    }
  }

  @Test
  void mismatchedFrozenTargetFailsBeforeAnyWorkerOrRemoteCall() throws Exception {
    var calls = new AtomicInteger();
    try (var external = new IndexingTestServer();
        var authority = new AuthorityTestContext(directory.resolve("authority"));
        var runtime =
            new IndexingJob(
                new IndexingTaskProcessor(
                    authority.indexing(),
                    owner.workspaceId(),
                    external.target(),
                    Duration.ofSeconds(8),
                    limit -> {
                      calls.incrementAndGet();
                      return new ProcessTextIndexer(
                          external.settings().models(), external.settings().projection(), limit);
                    }))) {
      String document = parsed(authority, "configuration.txt");
      var good = external.target();
      var changed =
          new IndexTarget(
              good.embeddingIdentity(),
              good.projectionIdentity(),
              "other-model-revision",
              good.dimensions());
      String task = (String) authority.createIndexing(owner, document, changed).get("task_id");
      await(() -> "failed".equals(authority.indexingStatus(owner, task).get("state")));
      assertEquals(
          "index_configuration_changed", authority.indexingStatus(owner, task).get("error_code"));
      assertEquals(0, calls.get());
      assertTrue(external.requests.isEmpty());
      assertNull(row(authority, document).get("active_revision_id"));
    }
  }

  @Test
  void partialRemoteWriteFailsSafelyAndExplicitSameTargetRetryPublishes() throws Exception {
    try (var external = new IndexingTestServer();
        var authority = new AuthorityTestContext(directory.resolve("authority"));
        var runtime =
            new IndexingJob(
                new IndexingTaskProcessor(
                    authority.indexing(),
                    owner.workspaceId(),
                    external.settings().models(),
                    external.settings().projection(),
                    external.target(),
                    Duration.ofSeconds(8)))) {
      external.failureMode = "partial-upsert";
      String document = parsed(authority, "partial.txt");
      String task = task(authority, external, document);
      await(() -> "failed".equals(authority.indexingStatus(owner, task).get("state")));
      assertEquals("indexing_failed", authority.indexingStatus(owner, task).get("error_code"));
      assertNull(row(authority, document).get("active_revision_id"));
      assertTrue(
          external.requests.stream()
              .anyMatch(request -> request.path().endsWith("/entities/upsert")));
      external.failureMode = "";
      authority.retryIndexing(owner, task, external.target());
      await(() -> "indexed".equals(authority.indexingStatus(owner, task).get("state")));
      assertEquals(2, authority.indexingStatus(owner, task).get("attempt"));
      assertNotNull(row(authority, document).get("index_publication_id"));
    }
  }

  @Test
  void timeoutKillsChildAndTheNextQueuedDocumentStillIndexes() throws Exception {
    var calls = new AtomicInteger();
    Path pid = directory.resolve("timeout.pid");
    try (var external = new IndexingTestServer();
        var authority = new AuthorityTestContext(directory.resolve("authority"));
        var runtime =
            new IndexingJob(
                new IndexingTaskProcessor(
                    authority.indexing(),
                    owner.workspaceId(),
                    external.target(),
                    Duration.ofSeconds(2),
                    limit ->
                        calls.getAndIncrement() == 0
                            ? child(external, limit, "hang", pid)
                            : child(external, limit, "success", pid)))) {
      String document = parsed(authority, "timeout.txt");
      String task = task(authority, external, document);
      String next = task(authority, external, parsed(authority, "next.txt"));
      await(() -> "failed".equals(authority.indexingStatus(owner, task).get("state")));
      assertEquals("indexing_timeout", authority.indexingStatus(owner, task).get("error_code"));
      assertFalse(alive(pid));
      assertNull(row(authority, document).get("active_revision_id"));
      await(() -> "indexed".equals(authority.indexingStatus(owner, next).get("state")));
      assertEquals(2, calls.get());
    }
  }

  @Test
  void unexpectedFactoryFailureIsSanitizedAndDoesNotStopScheduling() throws Exception {
    var calls = new AtomicInteger();
    try (var external = new IndexingTestServer();
        var authority = new AuthorityTestContext(directory.resolve("authority"));
        var runtime =
            new IndexingJob(
                new IndexingTaskProcessor(
                    authority.indexing(),
                    owner.workspaceId(),
                    external.target(),
                    Duration.ofSeconds(8),
                    limit -> {
                      if (calls.getAndIncrement() == 0) {
                        throw new IllegalStateException("private diagnostic must not be returned");
                      }
                      return child(external, limit, "success", directory.resolve("unused"));
                    }))) {
      String failed = task(authority, external, parsed(authority, "unexpected.txt"));
      String next = task(authority, external, parsed(authority, "success.txt"));
      await(() -> "failed".equals(authority.indexingStatus(owner, failed).get("state")));
      assertEquals("indexing_failed", authority.indexingStatus(owner, failed).get("error_code"));
      assertFalse(
          authority.indexingStatus(owner, failed).toString().contains("private diagnostic"));
      await(() -> "indexed".equals(authority.indexingStatus(owner, next).get("state")));
    }
  }

  private String parsed(AuthorityTestContext authority, String filename) {
    byte[] content = "合成上海住宿限额650元。\n中文😀保留。".getBytes(StandardCharsets.UTF_8);
    var upload = authority.uploadDocument(owner, filename, "text/plain", content);
    var claim = authority.claimIngestion(owner.workspaceId()).orElseThrow();
    assertTrue(
        authority.completeIngestion(
            claim, new TextParser().parse(filename, "text/plain", content)));
    return (String) upload.get("document_id");
  }

  private String task(
      AuthorityTestContext authority, IndexingTestServer external, String document) {
    return (String) authority.createIndexing(owner, document, external.target()).get("task_id");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> row(AuthorityTestContext authority, String document) {
    return ((List<Map<String, Object>>) authority.listDocuments(owner, Map.of()).get("items"))
        .stream()
            .filter(item -> document.equals(item.get("document_id")))
            .findFirst()
            .orElseThrow();
  }

  private static ProcessTextIndexer child(
      IndexingTestServer external, Duration timeout, String mode, Path argument) {
    return ControlledIndexerFixture.indexer(
        external.settings().models(), external.settings().projection(), timeout, mode, argument);
  }

  private static boolean alive(Path path) {
    try {
      if (!Files.exists(path)) {
        return false;
      }
      String value = Files.readString(path);
      if (!value.matches("[1-9][0-9]*")) {
        return false;
      }
      return ProcessHandle.of(Long.parseLong(value)).map(ProcessHandle::isAlive).orElse(false);
    } catch (java.io.IOException failure) {
      throw new AssertionError("Cannot read controlled child PID");
    }
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertTrue(condition.getAsBoolean(), "Expected bounded indexing scheduler state transition");
  }
}
