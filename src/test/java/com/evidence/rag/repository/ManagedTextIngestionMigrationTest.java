package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.IngestionClaim;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.IngestionService;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.tool.parser.TextParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real v22 files upgrade without rewriting task history or weakening its state machine. */
class ManagedTextIngestionMigrationTest {
  private static final Actor OWNER = new Actor("org-main", "owner");
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"text_configuration_required", "media_text_configuration_mismatch"})
  void oldV22HistoryAndGuardsSurviveWhileOnlyTheTwoConfigurationFailuresBecomePersistable(
      String safeCode) throws Exception {
    IngestionClaim processing;
    List<Map<String, Object>> jobs;
    List<Map<String, Object>> schemaObjects;
    List<Map<String, Object>> columns;
    List<Map<String, Object>> foreignKeys;
    byte[] content = "synthetic v22 task history".getBytes(StandardCharsets.UTF_8);
    try (var authority = new AuthorityTestContext(directory)) {
      var ingestion = authority.ingestion();
      ingestion.uploadDocument(OWNER, "parsed.txt", "text/plain", content);
      var parsed = ingestion.claimIngestion(OWNER.workspaceId()).orElseThrow();
      assertTrue(
          ingestion.completeIngestion(
              parsed, new TextParser().parse("parsed.txt", "text/plain", content)));
      ingestion.uploadDocument(OWNER, "failed.txt", "text/plain", content);
      assertTrue(
          ingestion.failIngestion(
              ingestion.claimIngestion(OWNER.workspaceId()).orElseThrow(), "parser_failed"));
      var cancelled = ingestion.uploadDocument(OWNER, "cancelled.txt", "text/plain", content);
      assertEquals("cancelled", ingestion.cancelIngestion(OWNER, cancelled.taskId()).state());
      ingestion.uploadDocument(OWNER, "processing.txt", "text/plain", content);
      processing = ingestion.claimIngestion(OWNER.workspaceId()).orElseThrow();
      ingestion.uploadDocument(OWNER, "queued.txt", "text/plain", content);
      var store = authority.store();
      jobs = store.transaction(() -> store.rows("SELECT * FROM ingestion_jobs ORDER BY id"));
      schemaObjects =
          store.transaction(
              () ->
                  store.rows(
                      "SELECT type,name,sql FROM sqlite_master WHERE type IN ('table','index','trigger') AND sql IS NOT NULL AND name!='ingestion_jobs' ORDER BY type,name"));
      columns =
          store.transaction(() -> store.rows("SELECT * FROM pragma_table_info('ingestion_jobs')"));
      foreignKeys =
          store.transaction(
              () -> store.rows("SELECT * FROM pragma_foreign_key_list('ingestion_jobs')"));
      assertEquals(
          List.of("cancelled", "failed", "parsed", "processing", "queued"),
          store
              .transaction(() -> store.rows("SELECT state FROM ingestion_jobs ORDER BY state"))
              .stream()
              .map(row -> (String) row.get("state"))
              .toList());
    }
    ModelConfigurationV22Fixture.restoreVersionTwentyTwo(directory);
    Path database = directory.resolve("java-library.db");
    assertEquals(22, scalar(database, "PRAGMA user_version"));
    assertEquals(22, scalar(database, "SELECT version FROM format_info"));
    assertThrows(SQLException.class, () -> updateFailure(database, processing.jobId(), safeCode));
    assertEquals(
        1, scalar(database, "SELECT COUNT(*) FROM ingestion_jobs WHERE state='processing'"));

    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(
                HistoricalSchemaV25Fixture.CURRENT_VERSION, store.count("PRAGMA user_version"));
            assertEquals(
                HistoricalSchemaV25Fixture.CURRENT_VERSION,
                store.count("SELECT version FROM format_info"));
            assertEquals(jobs, store.rows("SELECT * FROM ingestion_jobs ORDER BY id"));
            assertEquals(
                schemaObjects,
                store.rows(
                    "SELECT type,name,sql FROM sqlite_master WHERE type IN ('table','index','trigger') AND sql IS NOT NULL AND name!='ingestion_jobs' ORDER BY type,name"));
            assertEquals(columns, store.rows("SELECT * FROM pragma_table_info('ingestion_jobs')"));
            assertEquals(
                foreignKeys, store.rows("SELECT * FROM pragma_foreign_key_list('ingestion_jobs')"));
            assertEquals(1, store.count("PRAGMA foreign_keys"));
            assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
            new AuthoritySchema(store).verifyVersionThirtyTwo();
            return null;
          });
      assertArrayEquals(
          content,
          store.transaction(
              () -> new IngestionRepository(store).original(processing.documentId())));
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute(
                        "UPDATE ingestion_jobs SET created_by='replacement' WHERE id=?",
                        processing.jobId());
                    return null;
                  }));
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute(
                        "UPDATE ingestion_jobs SET state='failed',claim_token_sha256=NULL,error_code='unrecognized_configuration_error' WHERE id=?",
                        processing.jobId());
                    return null;
                  }));
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute("DELETE FROM ingestion_jobs WHERE id=?", processing.jobId());
                    return null;
                  }));
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute(
                        "UPDATE ingestion_jobs SET state='queued',claim_token_sha256=NULL,attempt=4 WHERE id=?",
                        processing.jobId());
                    return null;
                  }));
      var ingestion =
          new IngestionService(
              store,
              new IngestionRepository(store),
              new ManagementRepository(store),
              new DocumentPermissionPolicy());
      assertTrue(ingestion.failIngestion(processing, safeCode));
      var failed = ingestion.ingestionStatus(OWNER, processing.jobId());
      assertEquals("failed", failed.state());
      assertEquals(safeCode, failed.errorCode());
      assertTrue(failed.canRetry());
      assertFalse(ingestion.isIngestionClaimCurrent(processing));
      assertNull(
          store.transaction(
              () ->
                  store
                      .rows(
                          "SELECT claim_token_sha256 FROM ingestion_jobs WHERE id=?",
                          processing.jobId())
                      .getFirst()
                      .get("claim_token_sha256")));
      assertArrayEquals(
          content,
          store.transaction(
              () -> new IngestionRepository(store).original(processing.documentId())));
      var backups = store.managedBackups();
      assertTrue(backups.known());
      HistoricalSchemaV25Fixture.assertMigrationBackups(directory, backups.files(), 22);
      var originalBackups =
          backups.files().stream()
              .filter(file -> file.relativePath().startsWith("java-library.v22-before-v23-"))
              .toList();
      assertEquals(1, originalBackups.size());
      var backup = originalBackups.getFirst();
      assertTrue(backup.relativePath().startsWith("java-library.v22-before-v23-"));
      Path snapshot = store.libraryPath().getParent().resolve(backup.relativePath());
      assertEquals(Files.size(snapshot), backup.sizeBytes());
      assertEquals(ModelValues.sha256(Files.readAllBytes(snapshot)), backup.sha256());
      assertEquals(22, scalar(snapshot, "PRAGMA user_version"));
      assertEquals(5, scalar(snapshot, "SELECT COUNT(*) FROM ingestion_jobs"));
      assertThrows(SQLException.class, () -> updateFailure(snapshot, processing.jobId(), safeCode));
    }
    try (var reopened = new SqliteAuthorityStore(directory)) {
      reopened.transaction(
          () -> {
            assertEquals(
                HistoricalSchemaV25Fixture.CURRENT_VERSION, reopened.count("PRAGMA user_version"));
            assertEquals(
                1,
                reopened.count(
                    "SELECT COUNT(*) FROM ingestion_jobs WHERE id=? AND state='failed' AND error_code=? AND claim_token_sha256 IS NULL",
                    processing.jobId(),
                    safeCode));
            assertEquals(5, reopened.count("SELECT COUNT(*) FROM ingestion_jobs"));
            assertEquals(0, reopened.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
            return null;
          });
      HistoricalSchemaV25Fixture.assertMigrationBackups(
          directory, reopened.managedBackups().files(), 22);
    }
  }

  @Test
  void v23StartupStillRejectsAChangedTaskTransitionGuardInsteadOfTrustingOnlyTheVersion()
      throws Exception {
    try (var ignored = new SqliteAuthorityStore(directory)) {}
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement()) {
      statement.execute("DROP TRIGGER ingestion_transition");
    }
    assertThrows(IllegalStateException.class, () -> new SqliteAuthorityStore(directory));
  }

  private static long scalar(Path database, String sql) throws SQLException {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var statement = connection.createStatement();
        var result = statement.executeQuery(sql)) {
      assertTrue(result.next());
      return result.getLong(1);
    }
  }

  private static void updateFailure(Path database, String job, String code) throws SQLException {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var statement =
            connection.prepareStatement(
                "UPDATE ingestion_jobs SET state='failed',claim_token_sha256=NULL,error_code=? WHERE id=?")) {
      statement.setString(1, code);
      statement.setString(2, job);
      statement.executeUpdate();
    }
  }
}
