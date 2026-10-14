package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConversationMigrationTest {
  @TempDir Path directory;

  @Test
  void actualThirtyFiveCopyPreservesEveryOriginalObjectAndBusinessRowAndReopens() throws Exception {
    Path seed = directory.resolve("seed");
    IngestionMigrationTest.versionOne(seed);
    try (var store = new SqliteAuthorityStore(seed)) {
      assertEquals(37, store.transaction(() -> store.count("PRAGMA user_version")));
    }
    Path backup;
    try (var paths = Files.list(seed)) {
      backup =
          paths
              .filter(
                  path ->
                      path.getFileName().toString().startsWith("java-library.v35-before-v36-")
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
              assertEquals(37, store.count("PRAGMA user_version"));
              assertEquals(
                  WikiPagePurgeMigrationTest.withoutReplacedGuards(oldObjects),
                  WikiPagePurgeMigrationTest.withoutReplacedGuards(
                      store.rows(
                          "SELECT type,name,sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'knowledge_conversation%' ORDER BY type,name")));
              for (var row : rows.entrySet()) {
                assertEquals(
                    row.getValue(),
                    IndexRestartMigrationTest.rows(store, row.getKey()),
                    row.getKey());
              }
              assertEquals(0, store.count("SELECT COUNT(*) FROM knowledge_conversations"));
              assertEquals(0, store.count("SELECT COUNT(*) FROM knowledge_conversation_turns"));
              assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
              return null;
            });
        HistoricalSchemaV25Fixture.assertMigrationBackups(
            target, store.managedBackups().files(), 35);
      }
    }
    ReindexVersion23Fixture.execute(
        database, "ALTER TABLE knowledge_conversation_turns DROP COLUMN result_json");
    assertThrows(IllegalStateException.class, () -> new SqliteAuthorityStore(target));
  }
}
