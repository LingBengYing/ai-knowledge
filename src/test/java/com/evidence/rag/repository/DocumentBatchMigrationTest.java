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
              assertEquals(40L, store.count("PRAGMA user_version"));
              before.forEach(
                  (name, rows) -> assertEquals(rows, store.rows("SELECT * FROM " + name), name));
              assertEquals(0L, store.count("SELECT COUNT(*) FROM document_import_batches"));
              assertEquals(0L, store.count("SELECT COUNT(*) FROM document_import_files"));
              assertEquals(0L, store.count("SELECT COUNT(*) FROM batch_request_reservations"));
              assertEquals(0L, store.count("SELECT COUNT(*) FROM batch_task_requeues"));
              assertEquals(0L, store.count("SELECT COUNT(*) FROM document_import_file_states"));
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
  void actualV38SnapshotUpgradesThroughV39WithOnlyTheRequeueTableAndOneBackup() throws Exception {
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
              assertEquals(40L, store.count("PRAGMA user_version"));
              assertEquals(40L, store.count("SELECT version FROM format_info"));
              before.forEach(
                  (name, rows) -> assertEquals(rows, store.rows("SELECT * FROM " + name), name));
              assertEquals(
                  objects,
                  store.rows(
                      "SELECT type,name,sql FROM sqlite_master WHERE sql IS NOT NULL AND name<>'batch_task_requeues' AND name NOT IN "
                          + V40_OBJECTS
                          + " ORDER BY name"));
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

  static final String V40_OBJECTS =
      "('document_import_file_states','document_import_file_states_state','document_import_files_ingestion_task','document_import_files_revision','import_index_requests_task','batch_request_reservations_batch','documents_workspace_source','batch_file_state_insert','batch_file_state_received','batch_file_state_parse','batch_file_state_request_insert','batch_file_state_request_update','batch_file_state_index','batch_file_state_tombstone')";

  @Test
  void actualV39SnapshotWithABatchUpgradesToV40AddingOnlyStateRowsIndexesAndTriggers()
      throws Exception {
    Path seed = directory.resolve("seed40"), target = directory.resolve("upgraded40");
    IngestionMigrationTest.versionOne(seed);
    try (var ignored = new SqliteAuthorityStore(seed)) {}
    Path original;
    try (var files = Files.list(seed)) {
      original =
          files
              .filter(p -> p.getFileName().toString().startsWith("java-library.v39-before-v40-"))
              .findFirst()
              .orElseThrow();
    }
    Files.createDirectory(target);
    Path database = target.resolve("java-library.db");
    Files.copy(original, database);
    assertEquals(
        39L,
        ((Number)
                ReindexVersion23Fixture.rows(database, "PRAGMA user_version")
                    .getFirst()
                    .values()
                    .iterator()
                    .next())
            .longValue());
    // An in-flight v39 batch: two files never uploaded, registered before v40.
    ReindexVersion23Fixture.execute(
        database,
        "INSERT INTO document_import_batches(id,workspace_id,actor_id,request_id,manifest_sha256,created_at,request_limit) VALUES('old-batch','org-main','owner','old','"
            + "c".repeat(64)
            + "','2026-01-01T00:00:00Z',10)");
    for (int ordinal = 0; ordinal < 2; ordinal++)
      ReindexVersion23Fixture.execute(
          database,
          "INSERT INTO document_import_files(batch_id,ordinal,filename,size_bytes,source_sha256) VALUES('old-batch',"
              + ordinal
              + ",'old-"
              + ordinal
              + ".txt',1,'"
              + "d".repeat(64)
              + "')");
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
              assertEquals(40L, store.count("PRAGMA user_version"));
              assertEquals(40L, store.count("SELECT version FROM format_info"));
              before.forEach(
                  (name, rows) -> assertEquals(rows, store.rows("SELECT * FROM " + name), name));
              assertEquals(
                  objects,
                  store.rows(
                      "SELECT type,name,sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT IN "
                          + V40_OBJECTS
                          + " ORDER BY name"));
              // Existing files get their derived state; nothing is marked duplicate after the fact.
              assertEquals(
                  List.of(
                      Map.of("ordinal", 0, "state", "awaiting_upload"),
                      Map.of("ordinal", 1, "state", "awaiting_upload")),
                  store.rows(
                      "SELECT ordinal,state FROM document_import_file_states WHERE batch_id='old-batch' ORDER BY ordinal"));
              assertEquals(
                  14L,
                  store.count(
                      "SELECT COUNT(*) FROM cleanup_schema_objects WHERE name IN " + V40_OBJECTS));
              return null;
            });
      }
    }
    try (var files = Files.list(target)) {
      assertEquals(
          1,
          files
              .filter(p -> p.getFileName().toString().startsWith("java-library.v39-before-v40-"))
              .count());
    }
  }
}
