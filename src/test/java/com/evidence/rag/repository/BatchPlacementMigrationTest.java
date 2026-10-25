package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BatchPlacementMigrationTest {
  @TempDir Path directory;

  @Test
  void actualV42SnapshotKeepsEveryExistingRowAndObjectAcrossTwoReopens() throws Exception {
    Path seed = directory.resolve("seed"), target = directory.resolve("upgraded");
    IngestionMigrationTest.versionOne(seed);
    try (var ignored = new SqliteAuthorityStore(seed)) {}
    Path original;
    try (var files = Files.list(seed)) {
      original =
          files
              .filter(p -> p.getFileName().toString().startsWith("java-library.v42-before-v43-"))
              .findFirst()
              .orElseThrow();
    }
    Files.createDirectory(target);
    Path database = target.resolve("java-library.db");
    Files.copy(original, database);
    ReindexVersion23Fixture.execute(
        database,
        "INSERT INTO document_import_batches(id,workspace_id,actor_id,request_id,manifest_sha256,created_at,request_limit) VALUES('before','org-main','owner','before','"
            + "a".repeat(64)
            + "','2026-01-01T00:00:00Z',10)");
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
              assertEquals(43L, store.count("PRAGMA user_version"));
              assertEquals(43L, store.count("SELECT version FROM format_info"));
              before.forEach(
                  (name, rows) -> assertEquals(rows, store.rows("SELECT * FROM " + name), name));
              assertEquals(
                  objects,
                  store.rows(
                      "SELECT type,name,sql FROM sqlite_master WHERE sql IS NOT NULL AND name<>'document_import_batch_placement' ORDER BY name"));
              assertEquals(0L, store.count("SELECT COUNT(*) FROM document_import_batch_placement"));
              assertEquals(
                  1L,
                  store.count(
                      "SELECT COUNT(*) FROM cleanup_schema_objects WHERE name='document_import_batch_placement'"));
              return null;
            });
      }
    }
    try (var files = Files.list(target)) {
      assertEquals(
          1L,
          files
              .filter(p -> p.getFileName().toString().startsWith("java-library.v42-before-v43-"))
              .count());
    }
  }
}
