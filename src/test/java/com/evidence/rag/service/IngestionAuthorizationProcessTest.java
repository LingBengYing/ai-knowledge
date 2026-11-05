package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.dto.TaskResult;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.worker.parser.ProcessTextParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
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
