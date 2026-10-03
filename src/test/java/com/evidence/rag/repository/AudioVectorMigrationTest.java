package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AudioVectorMigrationTest {
  @TempDir Path directory;

  @Test
  void freshFormatAddsCompleteImmutableMetadataOnlySidecars() {
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(24, store.count("PRAGMA user_version"));
            assertEquals(24, store.count("SELECT version FROM format_info"));
            assertEquals(
                14,
                store.count("SELECT COUNT(*) FROM pragma_table_info('audio_vector_publications')"));
            assertEquals(
                9, store.count("SELECT COUNT(*) FROM pragma_table_info('audio_vector_entries')"));
            assertEquals(
                8,
                store.count(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name LIKE 'audio_vector_%'"));
            for (String table :
                new String[] {"audio_vector_publications", "audio_vector_entries"}) {
              assertEquals(
                  0,
                  store.count(
                      "SELECT COUNT(*) FROM pragma_table_info(?) WHERE name IN ('pcm','wav','transcript','text','vector','key','endpoint')",
                      table));
            }
            assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
            return null;
          });
    }
  }

  @Test
  void versionSeventeenGetsOneRestorableBackupWithoutRewritingImageOrTextTables() throws Exception {
    try (var ignored = new SqliteAuthorityStore(directory)) {}
    restoreVersionSeventeen(directory);
    for (int opening = 0; opening < 2; opening++) {
      try (var store = new SqliteAuthorityStore(directory)) {
        store.transaction(
            () -> {
              assertEquals(24, store.count("PRAGMA user_version"));
              assertEquals(0, store.count("SELECT COUNT(*) FROM audio_vector_publications"));
              assertEquals(
                  1,
                  store.count(
                      "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='image_vector_publications'"));
              return null;
            });
      }
    }
    try (var paths = Files.list(directory)) {
      var backups =
          paths
              .filter(
                  path ->
                      path.getFileName().toString().startsWith("java-library.v17-before-v18-")
                          && path.toString().endsWith(".db"))
              .toList();
      assertEquals(1, backups.size());
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + backups.getFirst());
          var statement = connection.createStatement();
          var version = statement.executeQuery("PRAGMA user_version")) {
        assertTrue(version.next());
        assertEquals(17, version.getInt(1));
      }
    }
  }

  @Test
  void incompleteGuardSetFailsClosedOnReopen() throws Exception {
    try (var ignored = new SqliteAuthorityStore(directory)) {}
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement()) {
      statement.execute("DROP TRIGGER audio_vector_entries_sealed");
    }
    assertThrows(IllegalStateException.class, () -> new SqliteAuthorityStore(directory));
  }

  @Test
  void failedMigrationLeavesVersionSeventeenAndExistingTablesIntact() throws Exception {
    try (var ignored = new SqliteAuthorityStore(directory)) {}
    restoreVersionSeventeen(directory);
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement()) {
      statement.execute("CREATE TABLE audio_vector_publications(unexpected TEXT)");
    }
    assertThrows(IllegalStateException.class, () -> new SqliteAuthorityStore(directory));
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement();
        var rows = statement.executeQuery("PRAGMA user_version")) {
      assertTrue(rows.next());
      assertEquals(17, rows.getInt(1));
    }
  }

  /** Test-only downgrade refuses all nonempty sidecars rather than deleting published receipts. */
  static void restoreVersionSeventeen(Path directory) throws SQLException {
    SoundMigrationTest.restoreVersionEighteen(directory);
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement()) {
      int current;
      try (var version = statement.executeQuery("PRAGMA user_version")) {
        assertTrue(version.next());
        current = version.getInt(1);
      }
      if (current <= 17) {
        return;
      }
      assertEquals(18, current);
      for (String table : new String[] {"audio_vector_entries", "audio_vector_publications"}) {
        try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
          assertTrue(rows.next());
          assertEquals(0, rows.getInt(1), "Cannot discard audio vector publication history");
        }
      }
      statement.execute("PRAGMA foreign_keys=ON");
      connection.setAutoCommit(false);
      statement.execute("DROP TABLE audio_vector_entries");
      statement.execute("DROP TABLE audio_vector_publications");
      statement.execute("UPDATE format_info SET version=17");
      statement.execute("PRAGMA user_version=17");
      connection.commit();
    }
  }
}
