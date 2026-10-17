package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentBatchMigrationTest {
  @TempDir Path directory;

  @Test
  void aLaterJdbcBatchFailureRollsBackTheWholeManifest() {
    try (var store = new SqliteAuthorityStore(directory.resolve("rollback"))) {
      var repository = new DocumentBatchRepository(store);
      var valid =
          new com.evidence.rag.model.domain.BatchImportFile.Manifest("one.txt", 1, "a".repeat(64));
      var invalid = new com.evidence.rag.model.domain.BatchImportFile.Manifest("two.txt", 1, null);
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    repository.insert(
                        new com.evidence.rag.model.domain.Actor("org-main", "owner"),
                        "batch",
                        "request",
                        "b".repeat(64),
                        10,
                        "2026-01-01T00:00:00Z",
                        List.of(valid, invalid));
                    return null;
                  }));
      store.transaction(
          () -> {
            assertEquals(0L, store.count("SELECT COUNT(*) FROM document_import_batches"));
            assertEquals(0L, store.count("SELECT COUNT(*) FROM document_import_files"));
            return null;
          });
    }
  }

  @Test
  void actualV37SnapshotUpgradesAndReopensWithoutChangingExistingBusinessRows() throws Exception {
    Path seed = directory.resolve("seed"), target = directory.resolve("upgraded");
    IngestionMigrationTest.versionOne(seed);
    try (var ignored = new SqliteAuthorityStore(seed)) {}
    Path original;
    try (var files = Files.list(seed)) {
      original =
          files
              .filter(p -> p.getFileName().toString().startsWith("java-library.v37-before-v38-"))
              .findFirst()
              .orElseThrow();
    }
    Files.createDirectory(target);
    Path database = target.resolve("java-library.db");
    Files.copy(original, database);
    var before = new LinkedHashMap<String, List<Map<String, Object>>>();
    for (var row :
        ReindexVersion23Fixture.rows(
            database,
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT IN ('format_info','cleanup_managed_backups','cleanup_schema_objects') ORDER BY name")) {
      String name = (String) row.get("name");
      before.put(name, ReindexVersion23Fixture.rows(database, "SELECT * FROM " + name));
    }
    for (int attempt = 0; attempt < 2; attempt++) {
      try (var store = new SqliteAuthorityStore(target)) {
        store.transaction(
            () -> {
              assertEquals(39L, store.count("PRAGMA user_version"));
              before.forEach(
                  (name, rows) -> assertEquals(rows, store.rows("SELECT * FROM " + name), name));
              assertEquals(0L, store.count("SELECT COUNT(*) FROM document_import_batches"));
              assertEquals(0L, store.count("SELECT COUNT(*) FROM document_import_files"));
              assertEquals(0L, store.count("SELECT COUNT(*) FROM batch_request_reservations"));
              assertEquals(0L, store.count("SELECT COUNT(*) FROM batch_task_requeues"));
              return null;
            });
      }
    }
    try (var files = Files.list(target)) {
      assertEquals(
          1,
          files
              .filter(p -> p.getFileName().toString().startsWith("java-library.v37-before-v38-"))
              .count());
    }
  }

  @Test
  void actualV38SnapshotUpgradesToV39WithOnlyTheRequeueTableAndOneBackup() throws Exception {
    Path seed = directory.resolve("seed39"), target = directory.resolve("upgraded39");
    IngestionMigrationTest.versionOne(seed);
    try (var ignored = new SqliteAuthorityStore(seed)) {}
    Path original;
    try (var files = Files.list(seed)) {
      original =
          files
              .filter(p -> p.getFileName().toString().startsWith("java-library.v38-before-v39-"))
              .findFirst()
              .orElseThrow();
    }
    Files.createDirectory(target);
    Path database = target.resolve("java-library.db");
    Files.copy(original, database);
    var objects =
        ReindexVersion23Fixture.rows(
            database,
            "SELECT type,name,sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY name");
    var before = new LinkedHashMap<String, List<Map<String, Object>>>();
    for (var row :
        ReindexVersion23Fixture.rows(
            database,
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT IN ('format_info','cleanup_managed_backups','cleanup_schema_objects') ORDER BY name")) {
      String name = (String) row.get("name");
      before.put(name, ReindexVersion23Fixture.rows(database, "SELECT * FROM " + name));
    }
    for (int attempt = 0; attempt < 2; attempt++) {
      try (var store = new SqliteAuthorityStore(target)) {
        store.transaction(
            () -> {
              assertEquals(39L, store.count("PRAGMA user_version"));
              assertEquals(39L, store.count("SELECT version FROM format_info"));
              before.forEach(
                  (name, rows) -> assertEquals(rows, store.rows("SELECT * FROM " + name), name));
              assertEquals(
                  objects,
                  store.rows(
                      "SELECT type,name,sql FROM sqlite_master WHERE sql IS NOT NULL AND name<>'batch_task_requeues' ORDER BY name"));
              assertEquals(0L, store.count("SELECT COUNT(*) FROM batch_task_requeues"));
              assertEquals(
                  1L,
                  store.count(
                      "SELECT COUNT(*) FROM cleanup_schema_objects WHERE name='batch_task_requeues'"));
              return null;
            });
      }
    }
    try (var files = Files.list(target)) {
      assertEquals(
          1,
          files
              .filter(p -> p.getFileName().toString().startsWith("java-library.v38-before-v39-"))
              .count());
    }
  }
}
