package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.ImageTextRegion;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.SyntheticDocument;
import com.evidence.rag.model.domain.TextPage;
import com.evidence.rag.support.PublishedCorpusFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ImageRegionMigrationTest {
  @TempDir Path directory;

  @Test
  void newAuthorityUsesVersionSixWithAnImageRegionSidecar() throws Exception {
    try (var ignored = new SqliteAuthorityStore(directory)) {
      // Opening the public Store must complete the supported migration chain.
    }
    try (var database =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = database.createStatement()) {
      try (var version = statement.executeQuery("PRAGMA user_version")) {
        assertTrue(version.next());
        assertEquals(HistoricalSchemaV25Fixture.CURRENT_VERSION, version.getInt(1));
      }
      try (var format = statement.executeQuery("SELECT version FROM format_info")) {
        assertTrue(format.next());
        assertEquals(HistoricalSchemaV25Fixture.CURRENT_VERSION, format.getInt(1));
      }
      try (var table =
          statement.executeQuery(
              "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='image_text_regions'")) {
        assertTrue(table.next());
        assertEquals(1, table.getInt(1));
      }
    }
  }

  @Test
  void v5UpgradeKeepsPublicationAndAnIndependentlyReadableBackupAcrossReopen() throws Exception {
    var actor = new Actor("org", "owner");
    List<PublicationVersion> before;
    try (var fixture = new PublishedCorpusFixture(directory)) {
      fixture.publish(actor, "Retained legacy authority evidence.");
      before =
          fixture
              .evidence
              .snapshot(actor, DocumentSelection.allDocuments(), PublishedCorpusFixture.TARGET)
              .publications();
    }
    restoreVersionFive();
    assertEquals(5, scalar(database(), "PRAGMA user_version"));

    for (int opening = 0; opening < 2; opening++) {
      try (var fixture = new PublishedCorpusFixture(directory)) {
        assertEquals(
            before,
            fixture
                .evidence
                .snapshot(actor, DocumentSelection.allDocuments(), PublishedCorpusFixture.TARGET)
                .publications());
      }
      assertEquals(
          HistoricalSchemaV25Fixture.CURRENT_VERSION, scalar(database(), "PRAGMA user_version"));
      assertEquals(1, backups().size());
    }
    Path backup = backups().getFirst();
    assertEquals(5, scalar(backup, "PRAGMA user_version"));
    assertEquals(5, scalar(backup, "SELECT version FROM format_info"));
    assertEquals(1, scalar(backup, "SELECT COUNT(*) FROM active_corpus_publications"));
    assertEquals(
        0, scalar(backup, "SELECT COUNT(*) FROM sqlite_master WHERE name='image_text_regions'"));
    Path restored = Files.createDirectory(directory.resolve("restored"));
    Files.copy(backup, restored.resolve("java-library.db"));
    try (var fixture = new PublishedCorpusFixture(restored)) {
      assertEquals(
          before,
          fixture
              .evidence
              .snapshot(actor, DocumentSelection.allDocuments(), PublishedCorpusFixture.TARGET)
              .publications());
    }
    assertEquals(5, scalar(backup, "PRAGMA user_version"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "DROP TABLE image_text_regions",
        "ALTER TABLE image_text_regions RENAME COLUMN left_pixel TO missing_left",
        "DROP TRIGGER image_text_regions_identity",
        "DROP TRIGGER image_text_regions_frozen",
        "DROP TRIGGER image_text_regions_no_replace",
        "DROP TRIGGER image_text_regions_no_update",
        "DROP TRIGGER image_text_regions_no_delete",
        "DROP TABLE document_tombstones"
      })
  void damagedV6OrInheritedV5IsRejectedBeforeUse(String mutation) throws Exception {
    try (var ignored = new SqliteAuthorityStore(directory)) {
      // Corrupt only the new temporary fixture, never a historical migration method.
    }
    VisualLibraryMigrationTest.restoreVersionSix(directory);
    sql(mutation);
    var failure =
        assertThrows(IllegalStateException.class, () -> new SqliteAuthorityStore(directory));
    assertNull(failure.getCause());
    assertEquals(6, scalar(database(), "PRAGMA user_version"));
    assertTrue(backups().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "UPDATE image_text_regions SET right_pixel=9",
        "DELETE FROM image_text_regions",
        "INSERT OR REPLACE INTO image_text_regions VALUES('revision',1,0,0,5,0,0,10,10)"
      })
  void storedRegionsCannotBeUpdatedDeletedOrReplacedEvenBeforeParsed(String mutation)
      throws Exception {
    pendingRegion();
    assertThrows(SQLException.class, () -> sql(mutation));
    try (var ignored = new SqliteAuthorityStore(directory)) {
      // A refused mutation must leave a reopenable, unchanged region record.
    }
    assertEquals(1, scalar(database(), "SELECT COUNT(*) FROM image_text_regions"));
    assertEquals(10, scalar(database(), "SELECT right_pixel FROM image_text_regions"));
  }

  @Test
  void parsedRevisionRejectsLateRegionAppend() throws Exception {
    pendingRegion();
    try (var store = new SqliteAuthorityStore(directory)) {
      var ingestion = new IngestionRepository(store);
      store.transaction(
          () -> {
            ingestion.markParsed("job", "document", "revision", 1, 0, "2026-09-09T00:00:00Z");
            return null;
          });
    }
    assertThrows(
        SQLException.class,
        () -> sql("INSERT INTO image_text_regions VALUES('revision',1,1,6,12,10,0,20,10)"));
    assertEquals(1, scalar(database(), "SELECT COUNT(*) FROM image_text_regions"));
  }

  @Test
  void v6DdlConflictRollsBackNewTableAndRetainsV5Backup() throws Exception {
    try (var ignored = new SqliteAuthorityStore(directory)) {
      // Build the unchanged historical v5 fixture by removing only this new sidecar.
    }
    restoreVersionFive();
    sql(
        "CREATE TRIGGER image_text_regions_frozen BEFORE UPDATE ON documents WHEN 0 BEGIN SELECT RAISE(ABORT,'fixture conflict'); END");

    assertThrows(IllegalStateException.class, () -> new SqliteAuthorityStore(directory));

    assertEquals(5, scalar(database(), "PRAGMA user_version"));
    assertEquals(5, scalar(database(), "SELECT version FROM format_info"));
    assertEquals(
        0,
        scalar(
            database(),
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='image_text_regions'"));
    assertEquals(1, backups().size());
    assertEquals(5, scalar(backups().getFirst(), "PRAGMA user_version"));
  }

  private void pendingRegion() {
    try (var store = new SqliteAuthorityStore(directory)) {
      var management = new ManagementRepository(store);
      var ingestion = new IngestionRepository(store);
      store.transaction(
          () -> {
            var owner = new Actor("org", "owner");
            String now = "2026-09-09T00:00:00Z";
            management.insertDocument(
                owner,
                new SyntheticDocument(
                    "document", "fixture.png", "image", "image/png", "revision", "a".repeat(64), 1),
                now);
            ingestion.insertOriginal(
                "document",
                "revision",
                "image-schema-fixture",
                "a".repeat(64),
                new byte[] {1},
                now);
            ingestion.insertJob("job", "document", "revision", owner.principalId(), now);
            ingestion.markProcessing("job", "b".repeat(64), now);
            ingestion.insertPage("revision", new TextPage(1, "first second"), "c".repeat(64));
            ingestion.insertImageRegion("revision", 0, new ImageTextRegion(0, 5, 0, 0, 10, 10));
            return null;
          });
    }
  }

  private void restoreVersionFive() throws SQLException {
    VisualLibraryMigrationTest.restoreVersionSix(directory);
    sql("DROP TABLE image_text_regions");
    sql("UPDATE format_info SET version=5");
    sql("PRAGMA user_version=5");
  }

  private Path database() {
    return directory.resolve("java-library.db");
  }

  private List<Path> backups() throws Exception {
    try (var paths = Files.list(directory)) {
      return paths
          .filter(
              path ->
                  path.getFileName().toString().startsWith("java-library.v5-before-v6-")
                      && path.toString().endsWith(".db"))
          .toList();
    }
  }

  private void sql(String command) throws SQLException {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
        var statement = connection.createStatement()) {
      statement.execute("PRAGMA foreign_keys=ON");
      statement.execute(command);
    }
  }

  private static long scalar(Path path, String command) throws SQLException {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        var statement = connection.createStatement();
        var result = statement.executeQuery(command)) {
      assertTrue(result.next());
      return result.getLong(1);
    }
  }
}
