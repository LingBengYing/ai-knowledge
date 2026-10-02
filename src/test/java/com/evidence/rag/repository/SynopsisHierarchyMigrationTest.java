package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.FileSynopsis;
import com.evidence.rag.model.entity.SynopsisTaskEntity;
import com.evidence.rag.support.PublishedCorpusFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SynopsisHierarchyMigrationTest {
  @TempDir Path directory;

  @Test
  void versionThirteenUpgradeKeepsAvailableSynopsisProcessingIdentityAndOneRestorableBackup()
      throws Exception {
    FileSynopsis expected;
    SynopsisTaskEntity processing;
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var input = SynopsisRepositoryTest.input(fixture);
      expected = SynopsisRepositoryTest.synopsis(input);
      processing =
          fixture
              .authority
              .store()
              .transaction(
                  () -> {
                    var repository = new SynopsisRepository(fixture.authority.store());
                    repository.insertTask(
                        "available-history",
                        SynopsisRepositoryTest.OWNER,
                        input.publication(),
                        SynopsisRepositoryTest.MODEL,
                        SynopsisRepositoryTest.POLICY,
                        SynopsisRepositoryTest.NOW);
                    assertTrue(
                        repository.markProcessing(
                            "available-history",
                            SynopsisRepositoryTest.CLAIM_HASH,
                            input,
                            SynopsisRepositoryTest.NOW));
                    assertTrue(
                        repository.complete(
                            "available-history",
                            SynopsisRepositoryTest.CLAIM_HASH,
                            expected,
                            SynopsisRepositoryTest.NOW));
                    repository.insertTask(
                        "processing-history",
                        SynopsisRepositoryTest.OWNER,
                        input.publication(),
                        SynopsisRepositoryTest.MODEL,
                        SynopsisRepositoryTest.POLICY,
                        SynopsisRepositoryTest.NOW);
                    assertTrue(
                        repository.markProcessing(
                            "processing-history",
                            SynopsisRepositoryTest.CLAIM_HASH,
                            input,
                            SynopsisRepositoryTest.NOW));
                    return repository.findTask("processing-history").orElseThrow();
                  });
    }
    restoreVersionThirteen(directory);
    for (int opening = 0; opening < 2; opening++) {
      try (var store = new SqliteAuthorityStore(directory)) {
        store.transaction(
            () -> {
              assertEquals(16, store.count("PRAGMA user_version"));
              var repository = new SynopsisRepository(store);
              assertEquals(expected, repository.findSynopsis("available-history").orElseThrow());
              assertEquals(processing, repository.findTask("processing-history").orElseThrow());
              assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
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
                      path.getFileName().toString().startsWith("java-library.v13-before-v14-")
                          && path.toString().endsWith(".db"))
              .toList();
    }
    assertEquals(1, backups.size());
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + backups.getFirst());
        var statement = connection.createStatement();
        var version = statement.executeQuery("PRAGMA user_version")) {
      assertTrue(version.next());
      assertEquals(13, version.getInt(1));
    }
    Path restored = Files.createDirectory(directory.resolve("restored"));
    Files.copy(backups.getFirst(), restored.resolve("java-library.db"));
    try (var store = new SqliteAuthorityStore(restored)) {
      store.transaction(
          () -> {
            assertEquals(
                expected,
                new SynopsisRepository(store).findSynopsis("available-history").orElseThrow());
            assertEquals(
                processing,
                new SynopsisRepository(store).findTask("processing-history").orElseThrow());
            return null;
          });
    }
  }

  /** Test-only schema reconstruction; refuses to discard a v14-sized input or change a row. */
  static void restoreVersionThirteen(Path directory) throws SQLException {
    VideoSubtitleMigrationTest.restoreVersionFourteen(directory);
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement()) {
      int current;
      try (var version = statement.executeQuery("PRAGMA user_version")) {
        assertTrue(version.next());
        current = version.getInt(1);
      }
      if (current == 13) {
        return;
      }
      assertEquals(14, current);
      statement.execute("PRAGMA foreign_keys=ON");
      connection.setAutoCommit(false);
      var tables =
          List.of(
              "synopsis_tasks",
              "synopsis_input_evidence",
              "synopsis_entries",
              "synopsis_references");
      var definitions = new LinkedHashMap<String, String>();
      var triggers = new LinkedHashMap<String, String>();
      var indexes = new ArrayList<String>();
      try (var rows =
          statement.executeQuery(
              "SELECT type,name,sql FROM sqlite_master WHERE tbl_name IN ('synopsis_tasks','synopsis_input_evidence','synopsis_entries','synopsis_references') AND sql IS NOT NULL ORDER BY name")) {
        while (rows.next()) {
          switch (rows.getString(1)) {
            case "table" -> definitions.put(rows.getString(2), rows.getString(3));
            case "trigger" -> triggers.put(rows.getString(2), rows.getString(3));
            case "index" -> indexes.add(rows.getString(3));
            default -> throw new IllegalStateException("Unexpected fixture object");
          }
        }
      }
      for (String table : tables) {
        String sql = definitions.get(table);
        if (table.equals("synopsis_tasks")) {
          assertTrue(sql.contains("input_count BETWEEN 0 AND 4096"));
          sql = sql.replace("input_count BETWEEN 0 AND 4096", "input_count BETWEEN 0 AND 64");
        } else if (table.equals("synopsis_input_evidence")) {
          assertTrue(sql.contains("ordinal BETWEEN 0 AND 4095"));
          sql = sql.replace("ordinal BETWEEN 0 AND 4095", "ordinal BETWEEN 0 AND 63");
        }
        for (String name : tables) {
          sql = sql.replace(name, "fixture_v13_" + name);
        }
        statement.execute(sql);
        statement.execute("INSERT INTO fixture_v13_" + table + " SELECT * FROM " + table);
      }
      for (String name : triggers.keySet()) {
        statement.execute("DROP TRIGGER " + name);
      }
      for (String table : tables.reversed()) {
        statement.execute("DROP TABLE " + table);
      }
      for (String table : tables) {
        statement.execute("ALTER TABLE fixture_v13_" + table + " RENAME TO " + table);
      }
      for (String sql : indexes) {
        statement.execute(sql);
      }
      for (String sql : triggers.values()) {
        statement.execute(sql);
      }
      try (var rows = statement.executeQuery("PRAGMA foreign_key_check")) {
        assertTrue(!rows.next());
      }
      statement.execute("UPDATE format_info SET version=13");
      statement.execute("PRAGMA user_version=13");
      connection.commit();
    }
  }
}
