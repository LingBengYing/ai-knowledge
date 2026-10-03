package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageVectorMigrationTest {
  @TempDir Path directory;

  @Test
  void freshFormatAddsOnlyImmutableBoundedSidecarMetadata() {
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(24, store.count("PRAGMA user_version"));
            assertEquals(24, store.count("SELECT version FROM format_info"));
            assertEquals(
                16,
                store.count("SELECT COUNT(*) FROM pragma_table_info('image_vector_publications')"));
            assertEquals(
                4,
                store.count(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name LIKE 'image_vector_publications_%'"));
            assertEquals(
                0,
                store.count(
                    "SELECT COUNT(*) FROM pragma_table_info('image_vector_publications') WHERE name IN ('content','caption','text','vector','key','endpoint')"));
            assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
            return null;
          });
    }
  }

  @Test
  void versionSixteenCreatesOneRestorableBackupAndPreservesTheOldTables() throws Exception {
    try (var ignored = new SqliteAuthorityStore(directory)) {}
    AudioVectorMigrationTest.restoreVersionSeventeen(directory);
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement()) {
      statement.execute("DROP TABLE image_vector_publications");
      statement.execute("UPDATE format_info SET version=16");
      statement.execute("PRAGMA user_version=16");
    }
    for (int opening = 0; opening < 2; opening++) {
      try (var store = new SqliteAuthorityStore(directory)) {
        store.transaction(
            () -> {
              assertEquals(24, store.count("PRAGMA user_version"));
              assertEquals(0, store.count("SELECT COUNT(*) FROM image_vector_publications"));
              assertEquals(
                  1,
                  store.count(
                      "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='image_publication_entries'"));
              return null;
            });
      }
    }
    try (var paths = Files.list(directory)) {
      var backups =
          paths
              .filter(
                  path ->
                      path.getFileName().toString().startsWith("java-library.v16-before-v17-")
                          && path.toString().endsWith(".db"))
              .toList();
      assertEquals(1, backups.size());
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + backups.getFirst());
          var statement = connection.createStatement();
          var version = statement.executeQuery("PRAGMA user_version")) {
        version.next();
        assertEquals(16, version.getInt(1));
      }
    }
  }

  @Test
  void currentFormatWithMissingSidecarGuardFailsClosed() throws Exception {
    try (var ignored = new SqliteAuthorityStore(directory)) {}
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement()) {
      statement.execute("DROP TRIGGER image_vector_publications_no_update");
    }
    assertThrows(IllegalStateException.class, () -> new SqliteAuthorityStore(directory));
  }
}
