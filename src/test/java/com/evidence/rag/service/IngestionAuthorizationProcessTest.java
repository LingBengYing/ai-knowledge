package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.job.IngestionJob;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.dto.TaskResult;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.worker.parser.ProcessTextParser;
import com.evidence.rag.worker.parser.SlowParserFixture;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real scheduler, temporary authority database and actual parser JVM authorization regressions. */
@SuppressWarnings("try")
class IngestionAuthorizationProcessTest {
  private static final Duration PARSER_DEADLINE = Duration.ofSeconds(30);
  private static final Duration TRANSITION_BOUND = Duration.ofSeconds(5);
  private static final String EVIDENCE = "合成撤权证据：恢复写权限后须显式重试。";

  @TempDir Path directory;
  private final Actor creator = new Actor("org", "creator");
  private final Actor observer = new Actor("org", "observer");

  @Test
  void revocationStopsActualChildBeforeItsDeadlineAndRetryStartsOnlyAfterChildExit()
      throws Exception {
    Path database = directory.resolve("db");
    Path pidFile = directory.resolve("revoked-child.pid");
    var parserCalls = new AtomicInteger();
    var slowPid = new AtomicLong();
    var overlappingChildren = new AtomicBoolean();
    try (var authority = new AuthorityTestContext(database)) {
      var service = authority.ingestion();
      var task = upload(service, creator, "revoked.txt");
      setRole(database, task.documentId(), observer, "editor");
      var processor =
          new IngestionTaskProcessor(
              service,
              creator.workspaceId(),
              PARSER_DEADLINE,
              limit -> {
                if (parserCalls.getAndIncrement() == 0) {
                  return SlowParserFixture.parser(limit, pidFile);
                }
                overlappingChildren.set(isAlive(slowPid.get()));
                return new ProcessTextParser(limit);
              });
      try (var job = new IngestionJob(processor)) {
        long pid = awaitPid(pidFile);
        slowPid.set(pid);
        assertTrue(isAlive(pid));
        assertEquals("processing", service.ingestionStatus(observer, task.taskId()).state());
        assertEquals(1, parserCalls.get());

        setRole(database, task.documentId(), creator, "reader");
        long cancellationUntil = System.nanoTime() + TRANSITION_BOUND.toNanos();
        awaitUntil(
            cancellationUntil,
            () -> "cancelled".equals(service.ingestionStatus(observer, task.taskId()).state()),
            "Revoked processing task must cancel before the 30-second parser deadline");
        var cancelled = service.ingestionStatus(observer, task.taskId());
        assertNull(cancelled.errorCode());
        assertEquals(1, cancelled.attempt());
        assertFalse(cancelled.canRetry());
        assertNoEvidence(database, service, task);

        // Retry as soon as cancellation is observable; the scheduler must still await old cleanup.
        setRole(database, task.documentId(), creator, "owner");
        var retry = service.retryIngestion(observer, task.taskId());
        assertEquals("queued", retry.state());
        assertEquals(2, retry.attempt());
        assertEquals(task.revisionId(), retry.revisionId());
        assertEquals(task.filename(), retry.filename());
        awaitUntil(
            cancellationUntil,
            () -> !isAlive(pid),
            "Actual parser child must exit within five seconds of revocation");
        await(
            () -> "parsed".equals(service.ingestionStatus(observer, task.taskId()).state()),
            "Restored creator authorization must permit a successful explicit retry");

        assertEquals(2, parserCalls.get());
        assertFalse(overlappingChildren.get(), "Retry parser must not overlap the revoked child");
        assertFalse(isAlive(pid));
        var completed = service.ingestionStatus(observer, task.taskId());
        assertEquals(2, completed.attempt());
        assertNull(completed.errorCode());
        var parsed = service.parsedEvidence(observer, task.documentId());
        assertEquals(EVIDENCE, parsed.pages().getFirst().text());
        assertEquals(EVIDENCE, parsed.segments().getFirst().text());
      }
    }
  }

  @Test
  void revokedQueuedCreatorStartsNoParserAndTheJobStillProcessesLaterAuthorizedWork()
      throws Exception {
    Path database = directory.resolve("db");
    var parserCalls = new AtomicInteger();
    try (var authority = new AuthorityTestContext(database)) {
      var service = authority.ingestion();
      var revoked = upload(service, creator, "queued-revoked.txt");
      setRole(database, revoked.documentId(), observer, "editor");
      setRole(database, revoked.documentId(), creator, null);
      try (var job = new IngestionJob(countingProcessor(service, parserCalls))) {
        await(
            () -> "cancelled".equals(service.ingestionStatus(observer, revoked.taskId()).state()),
            "A queued task whose creator lost access must be cancelled without parsing");
        assertEquals(0, parserCalls.get(), "Revoked original bytes must not enter a parser");
        var cancelled = service.ingestionStatus(observer, revoked.taskId());
        assertNull(cancelled.errorCode());
        assertEquals(1, cancelled.attempt());
        assertNoEvidence(database, service, revoked);

        var allowed = upload(service, observer, "authorized-later.txt");
        await(
            () -> "parsed".equals(service.ingestionStatus(observer, allowed.taskId()).state()),
            "Cancelling unauthorized queued work must not stop the ingestion scheduler");
        assertEquals(1, parserCalls.get());
        assertEquals(
            EVIDENCE,
            service.parsedEvidence(observer, allowed.documentId()).pages().getFirst().text());
        assertEquals("cancelled", service.ingestionStatus(observer, revoked.taskId()).state());
        assertNoEvidence(database, service, revoked);
      }
    }
  }

  @Test
  void directlyProcessingARevokedClaimRechecksAuthorizationBeforeCreatingTheParser()
      throws Exception {
    Path database = directory.resolve("db");
    var parserCalls = new AtomicInteger();
    try (var authority = new AuthorityTestContext(database)) {
      var service = authority.ingestion();
      var task = upload(service, creator, "revoked-before-process.txt");
      setRole(database, task.documentId(), observer, "editor");
      var claim = service.claimIngestion(creator.workspaceId()).orElseThrow();
      setRole(database, task.documentId(), creator, null);

      countingProcessor(service, parserCalls).process(claim);

      assertEquals(0, parserCalls.get(), "TaskProcessor must revalidate before its parser factory");
      var cancelled = service.ingestionStatus(observer, task.taskId());
      assertEquals("cancelled", cancelled.state());
      assertNull(cancelled.errorCode());
      assertEquals(1, cancelled.attempt());
      assertNoEvidence(database, service, task);
    }
  }

  @Test
  void anOldClaimCannotStartAParserOrCommitIntoTheExplicitRetryAttempt() throws Exception {
    Path database = directory.resolve("db");
    var parserCalls = new AtomicInteger();
    try (var authority = new AuthorityTestContext(database)) {
      var service = authority.ingestion();
      var task = upload(service, creator, "stale-claim.txt");
      setRole(database, task.documentId(), observer, "editor");
      var old = service.claimIngestion(creator.workspaceId()).orElseThrow();
      service.cancelIngestion(observer, task.taskId());
      assertEquals(2, service.retryIngestion(observer, task.taskId()).attempt());
      var current = service.claimIngestion(creator.workspaceId()).orElseThrow();
      assertEquals(old.revisionId(), current.revisionId());
      assertArrayEquals(old.content(), current.content());
      assertNotEquals(old.token(), current.token());
      var lateParsed = new TextParser().parse(old.filename(), old.mimeType(), old.content());
      var processor = countingProcessor(service, parserCalls);

      processor.process(old);

      assertEquals(0, parserCalls.get(), "A stale attempt must be rejected before parser creation");
      assertFalse(service.isIngestionClaimCurrent(old));
      assertFalse(service.completeIngestion(old, lateParsed));
      assertFalse(service.failIngestion(old, "parser_failed"));
      assertTrue(service.isIngestionClaimCurrent(current));
      assertEquals("processing", service.ingestionStatus(observer, task.taskId()).state());
      assertNoEvidence(database, service, task);

      processor.process(current);

      assertEquals(1, parserCalls.get());
      assertEquals("parsed", service.ingestionStatus(observer, task.taskId()).state());
      assertEquals(2, service.ingestionStatus(observer, task.taskId()).attempt());
      assertEquals(lateParsed, service.parsedEvidence(observer, task.documentId()));
      assertFalse(service.completeIngestion(old, lateParsed));
    }
  }

  private IngestionTaskProcessor countingProcessor(
      IngestionService service, AtomicInteger parserCalls) {
    return new IngestionTaskProcessor(
        service,
        creator.workspaceId(),
        PARSER_DEADLINE,
        limit -> {
          parserCalls.incrementAndGet();
          return new ProcessTextParser(limit);
        });
  }

  private static TaskResult upload(IngestionService service, Actor actor, String filename) {
    return service.uploadDocument(
        actor, filename, "text/plain", EVIDENCE.getBytes(StandardCharsets.UTF_8));
  }

  // ACL mutation is a fixture-only external authorization event, not a production management API.
  private static void setRole(Path database, String documentId, Actor actor, String role)
      throws SQLException {
    try (var connection = openTestDatabase(database)) {
      if (role == null) {
        try (var statement =
            connection.prepareStatement(
                "DELETE FROM document_acl WHERE document_id=? AND principal_id=?")) {
          statement.setString(1, documentId);
          statement.setString(2, actor.principalId());
          assertEquals(1, statement.executeUpdate());
        }
      } else {
        try (var statement =
            connection.prepareStatement(
                "INSERT INTO document_acl(document_id,principal_id,role) VALUES(?,?,?)"
                    + " ON CONFLICT(document_id,principal_id) DO UPDATE SET role=excluded.role")) {
          statement.setString(1, documentId);
          statement.setString(2, actor.principalId());
          statement.setString(3, role);
          assertEquals(1, statement.executeUpdate());
        }
      }
    }
  }

  private void assertNoEvidence(Path database, IngestionService service, TaskResult task)
      throws SQLException {
    assertEquals(
        "not_found",
        assertThrows(
                ApplicationException.class,
                () -> service.parsedEvidence(observer, task.documentId()))
            .code());
    // A missing public parsed pointer alone would not detect accidentally committed orphan rows.
    try (var connection = openTestDatabase(database);
        var statement =
            connection.prepareStatement(
                "SELECT (SELECT COUNT(*) FROM corpus_pages WHERE revision_id=?) AS pages,"
                    + " (SELECT COUNT(*) FROM corpus_segments WHERE revision_id=?) AS segments,"
                    + " parsed_revision_id,active_revision_id FROM corpus_documents"
                    + " WHERE document_id=?")) {
      statement.setString(1, task.revisionId());
      statement.setString(2, task.revisionId());
      statement.setString(3, task.documentId());
      try (var rows = statement.executeQuery()) {
        assertTrue(rows.next());
        assertEquals(0, rows.getInt("pages"));
        assertEquals(0, rows.getInt("segments"));
        assertNull(rows.getString("parsed_revision_id"));
        assertNull(rows.getString("active_revision_id"));
        assertFalse(rows.next());
      }
    }
  }

  private static Connection openTestDatabase(Path directory) throws SQLException {
    Path database = directory.resolve("java-library.db");
    assertTrue(
        Files.isRegularFile(database), "Fixture must only open its existing temporary database");
    var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
    try (var statement = connection.createStatement()) {
      statement.execute("PRAGMA busy_timeout=5000");
    } catch (SQLException failure) {
      connection.close();
      throw failure;
    }
    return connection;
  }

  private static long awaitPid(Path path) throws Exception {
    long until = System.nanoTime() + TRANSITION_BOUND.toNanos();
    while (System.nanoTime() < until) {
      if (Files.exists(path)) {
        String value = Files.readString(path);
        if (value.matches("[0-9]+")) {
          return Long.parseLong(value);
        }
      }
      Thread.sleep(10);
    }
    throw new AssertionError("Controlled parser child did not start");
  }

  private static boolean isAlive(long pid) {
    return pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
  }

  private static void await(BooleanSupplier condition, String message) throws InterruptedException {
    awaitUntil(System.nanoTime() + TRANSITION_BOUND.toNanos(), condition, message);
  }

  private static void awaitUntil(long until, BooleanSupplier condition, String message)
      throws InterruptedException {
    while (System.nanoTime() < until) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError(message);
  }
}
