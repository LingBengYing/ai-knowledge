package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportIndexMigrationTest {
  @TempDir Path directory;

  @Test
  void realThirtyThreeCopyKeepsEveryBusinessRowAndCreatesNoHistoricalIntent() throws Exception {
    Path seed = directory.resolve("seed");
    IngestionMigrationTest.versionOne(seed);
    try (var store = new SqliteAuthorityStore(seed)) {
      assertEquals(35L, store.transaction(() -> store.count("PRAGMA user_version")));
    }
    Path original;
    try (var paths = Files.list(seed)) {
      original =
          paths
              .filter(
                  path ->
                      path.getFileName().toString().startsWith("java-library.v33-before-v34-")
                          && path.toString().endsWith(".db"))
              .findFirst()
              .orElseThrow();
    }
    Path target = Files.createDirectory(directory.resolve("copy"));
    Path database = target.resolve("java-library.db");
    Files.copy(original, database);
    var schema =
        ReindexVersion23Fixture.rows(
            database,
            "SELECT type,name,sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY type,name");
    var rows = new LinkedHashMap<String, List<Map<String, Object>>>();
    for (var row :
        ReindexVersion23Fixture.rows(
            database,
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT IN ('format_info','cleanup_schema_objects','cleanup_managed_backups') ORDER BY name")) {
      String name = (String) row.get("name");
      rows.put(name, ReindexVersion23Fixture.rows(database, "SELECT * FROM " + name));
    }
    for (int reopen = 0; reopen < 2; reopen++) {
      try (var store = new SqliteAuthorityStore(target)) {
        store.transaction(
            () -> {
              assertEquals(35L, store.count("PRAGMA user_version"));
              assertEquals(0L, store.count("SELECT COUNT(*) FROM import_index_requests"));
              assertEquals(
                  WikiPagePurgeMigrationTest.withoutReplacedGuards(schema),
                  WikiPagePurgeMigrationTest.withoutReplacedGuards(
                      store.rows(
                          "SELECT type,name,sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'wiki_page_purges%' AND name NOT IN ('wiki_page_revisions_purged','wiki_proposals_purged','import_index_requests') ORDER BY type,name")));
              for (var row : rows.entrySet()) {
                assertEquals(
                    row.getValue(), store.rows("SELECT * FROM " + row.getKey()), row.getKey());
              }
              assertEquals(0L, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
              return null;
            });
        HistoricalSchemaV25Fixture.assertMigrationBackups(
            target, store.managedBackups().files(), 33);
      }
    }
  }
}
