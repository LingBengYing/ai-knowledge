package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.EvidenceScope;
import com.evidence.rag.support.PublishedCorpusFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SynopsisMigrationTest {
  @TempDir Path directory;

  @Test
  void versionTwelveUpgradeRetainsTextAndOcrPublicationsAndOneRestorableBackup() throws Exception {
    EvidenceScope expected;
    long ocrEntries;
    try (var fixture = new PublishedCorpusFixture(directory)) {
      fixture.publish(VideoOcrRepositoryTest.OWNER, "Retained before synopsis migration.");
      VideoOcrRepositoryTest.publish(fixture);
      expected = VideoOcrRepositoryTest.scope(fixture);
      ocrEntries =
          fixture
              .authority
              .store()
              .transaction(
                  () ->
                      fixture
                          .authority
                          .store()
                          .count("SELECT COUNT(*) FROM video_ocr_publication_entries"));
      assertTrue(ocrEntries > 0);
    }
    restoreVersionTwelve(directory);
    for (int reopening = 0; reopening < 2; reopening++) {
      try (var fixture = new PublishedCorpusFixture(directory)) {
        assertEquals(expected, VideoOcrRepositoryTest.scope(fixture));
        fixture
            .authority
            .store()
            .transaction(
                () -> {
                  assertEquals(25, fixture.authority.store().count("PRAGMA user_version"));
                  assertEquals(
                      ocrEntries,
                      fixture
                          .authority
                          .store()
                          .count("SELECT COUNT(*) FROM video_ocr_publication_entries"));
                  assertEquals(
                      0, fixture.authority.store().count("SELECT COUNT(*) FROM synopsis_tasks"));
                  assertEquals(
                      0,
                      fixture
                          .authority
                          .store()
                          .count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
                  return null;
                });
      }
    }
    List<Path> backups;
    try (var files = Files.list(directory)) {
      backups =
          files
              .filter(
                  path ->
                      path.getFileName().toString().startsWith("java-library.v12-before-v13-")
                          && path.toString().endsWith(".db"))
              .toList();
    }
    assertEquals(1, backups.size());
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + backups.getFirst());
        var statement = connection.createStatement()) {
      try (var version = statement.executeQuery("PRAGMA user_version")) {
        assertTrue(version.next());
        assertEquals(12, version.getInt(1));
      }
      try (var tables =
          statement.executeQuery(
              "SELECT COUNT(*) FROM sqlite_master WHERE name='synopsis_tasks'")) {
        assertTrue(tables.next());
        assertEquals(0, tables.getInt(1));
      }
    }
    Path restored = Files.createDirectory(directory.resolve("restored"));
    Files.copy(backups.getFirst(), restored.resolve("java-library.db"));
    try (var fixture = new PublishedCorpusFixture(restored)) {
      assertEquals(expected, VideoOcrRepositoryTest.scope(fixture));
    }
  }

  /** Only empty synthetic synopsis sidecars may be removed to build a genuine v12 fixture. */
  static void restoreVersionTwelve(Path directory) throws SQLException {
    SynopsisHierarchyMigrationTest.restoreVersionThirteen(directory);
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement()) {
      statement.execute("PRAGMA foreign_keys=ON");
      try (var version = statement.executeQuery("PRAGMA user_version")) {
        assertTrue(version.next());
        assertEquals(13, version.getInt(1));
      }
      var tables =
          List.of(
              "synopsis_references",
              "synopsis_entries",
              "synopsis_input_evidence",
              "synopsis_tasks");
      for (String table : tables) {
        try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
          assertTrue(rows.next());
          assertEquals(0, rows.getInt(1), "Migration fixture must not discard synopsis history");
        }
      }
      for (String table : tables) {
        statement.execute("DROP TABLE " + table);
      }
      statement.execute("UPDATE format_info SET version=12");
      statement.execute("PRAGMA user_version=12");
    }
  }
}
