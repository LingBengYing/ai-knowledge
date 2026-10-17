package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexRestartMigrationTest {
  /** Objects v37 rewrites or adds; every other object keeps its exact SQL. */
  static final Set<String> CHANGED =
      Set.of(
          "indexing_jobs",
          "indexing_identity",
          "indexing_rebuild_identity",
          "indexing_restart_identity",
          "active_corpus_publication_initial");

  @TempDir Path directory;

  /** Business rows without the v37 column, so earlier copies compare row for row. */
  static List<Map<String, Object>> rows(SqliteAuthorityStore store, String table) {
    return store.rows("SELECT * FROM " + table).stream()
        .map(
            row -> {
              if (!"indexing_jobs".equals(table) || row.get("restart_of") != null) {
                return row;
              }
              var copy = new LinkedHashMap<>(row);
              copy.remove("restart_of");
              return (Map<String, Object>) copy;
            })
        .toList();
  }

  @Test
  void actualThirtySixCopyKeepsEveryRowAndOnlyRewritesIndexRestartGuards() throws Exception {
    Path seed = directory.resolve("seed");
    IngestionMigrationTest.versionOne(seed);
    try (var store = new SqliteAuthorityStore(seed)) {
      assertEquals(
          (long) HistoricalSchemaV25Fixture.CURRENT_VERSION,
          store.transaction(() -> store.count("PRAGMA user_version")));
    }
    Path backup;
    try (var paths = Files.list(seed)) {
      backup =
          paths
              .filter(
                  path ->
                      path.getFileName().toString().startsWith("java-library.v36-before-v37-")
                          && path.toString().endsWith(".db"))
              .findFirst()
              .orElseThrow();
    }
    Path target = Files.createDirectory(directory.resolve("upgrade"));
    Path database = target.resolve("java-library.db");
    Files.copy(backup, database);
    var oldObjects =
        ReindexVersion23Fixture.rows(
            database,
            "SELECT type,name,sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY type,name");
    var oldRows = new LinkedHashMap<String, List<Map<String, Object>>>();
    for (var row :
        ReindexVersion23Fixture.rows(
            database,
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT IN ('format_info','cleanup_schema_objects','cleanup_managed_backups') ORDER BY name")) {
      String name = (String) row.get("name");
      oldRows.put(name, ReindexVersion23Fixture.rows(database, "SELECT * FROM " + name));
    }
    for (int reopen = 0; reopen < 2; reopen++) {
      try (var store = new SqliteAuthorityStore(target)) {
        store.transaction(
            () -> {
              assertEquals(
                  (long) HistoricalSchemaV25Fixture.CURRENT_VERSION,
                  store.count("PRAGMA user_version"));
              assertEquals(
                  1L,
                  store.count(
                      "SELECT COUNT(*) FROM pragma_table_info('indexing_jobs') WHERE name='restart_of'"));
              var changed = new HashSet<String>();
              for (var object : oldObjects) {
                if (!store
                    .rows(
                        "SELECT type,name,sql FROM sqlite_master WHERE type=? AND name=?",
                        object.get("type"),
                        object.get("name"))
                    .equals(List.of(object))) {
                  changed.add((String) object.get("name"));
                }
              }
              assertEquals(
                  Set.of(
                      "indexing_jobs",
                      "indexing_identity",
                      "indexing_rebuild_identity",
                      "active_corpus_publication_initial"),
                  changed);
              assertEquals(
                  oldObjects.size() + 1,
                  store.count("SELECT COUNT(*) FROM sqlite_master WHERE sql IS NOT NULL"));
              for (var row : oldRows.entrySet()) {
                assertEquals(row.getValue(), rows(store, row.getKey()), row.getKey());
              }
              assertEquals(0L, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
              return null;
            });
        HistoricalSchemaV25Fixture.assertMigrationBackups(
            target, store.managedBackups().files(), 36);
      }
    }
    ReindexVersion23Fixture.execute(database, "DROP TRIGGER indexing_restart_identity");
    assertThrows(IllegalStateException.class, () -> new SqliteAuthorityStore(target));
  }
}
