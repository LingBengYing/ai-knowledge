package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.ImageTextRegion;
import com.evidence.rag.model.domain.SyntheticDocument;
import com.evidence.rag.model.domain.TextPage;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ImageRegionSchemaTest {
  @TempDir Path directory;

  @Test
  void newAuthorityHasAnImageRegionSidecar() throws Exception {
    try (var ignored = new SqliteAuthorityStore(directory)) {}
    try (var database =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = database.createStatement()) {
      try (var version = statement.executeQuery("PRAGMA user_version")) {
        assertTrue(version.next());
        assertEquals(LibrarySchema.VERSION, version.getInt(1));
      }
      try (var format = statement.executeQuery("SELECT version FROM format_info")) {
        assertTrue(format.next());
        assertEquals(LibrarySchema.VERSION, format.getInt(1));
      }
      try (var table =
          statement.executeQuery(
              "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='image_text_regions'")) {
        assertTrue(table.next());
        assertEquals(1, table.getInt(1));
      }
    }
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

  private Path database() {
    return directory.resolve("java-library.db");
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
