package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WikiWorkspaceMigrationTest {
  @TempDir Path directory;

  @Test
  void versionThirtyUpgradeAddsOnlyWikiAndKeepsOriginalRowsSchemaAndRestorableBackup()
      throws Exception {
    Path target = versionThirty();
    Path database = target.resolve("java-library.db");
    var originalSchema =
        ReindexVersion23Fixture.rows(
            database,
            "SELECT type,name,sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY type,name");
    var originalRows = new LinkedHashMap<String, List<Map<String, Object>>>();
    for (var row :
        ReindexVersion23Fixture.rows(
            database,
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT IN ('format_info','cleanup_schema_objects','cleanup_managed_backups') ORDER BY name")) {
      String name = (String) row.get("name");
      originalRows.put(name, ReindexVersion23Fixture.rows(database, "SELECT * FROM " + name));
    }
    for (int opening = 0; opening < 2; opening++) {
      try (var store = new SqliteAuthorityStore(target)) {
        store.transaction(
            () -> {
              assertEquals(
                  HistoricalSchemaV25Fixture.CURRENT_VERSION, store.count("PRAGMA user_version"));
              assertEquals(
                  originalSchema,
                  store.rows(
                      "SELECT type,name,sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'wiki_%' AND name!='import_index_requests' ORDER BY type,name"));
              for (var entry : originalRows.entrySet()) {
                assertEquals(
                    entry.getValue(),
                    store.rows("SELECT * FROM " + entry.getKey()),
                    entry.getKey());
              }
              assertEquals(
                  23,
                  store.count(
                      "SELECT COUNT(*) FROM cleanup_schema_objects WHERE name LIKE 'wiki_%'"));
              assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
              assertEquals(1, store.count("PRAGMA foreign_keys"));
              assertEquals(0, store.count("SELECT COUNT(*) FROM wiki_page_revisions"));
              assertEquals(0, store.count("SELECT COUNT(*) FROM wiki_proposals"));
              assertEquals(0, store.count("SELECT COUNT(*) FROM import_index_requests"));
              return null;
            });
        HistoricalSchemaV25Fixture.assertMigrationBackups(
            target, store.managedBackups().files(), 30);
      }
    }
    Path backup;
    try (var paths = Files.list(target)) {
      var backups =
          paths
              .filter(
                  path ->
                      path.getFileName().toString().startsWith("java-library.v30-before-v31-")
                          && path.toString().endsWith(".db"))
              .toList();
      assertEquals(1, backups.size());
      backup = backups.getFirst();
    }
    assertEquals(
        originalRows.get("documents"),
        ReindexVersion23Fixture.rows(backup, "SELECT * FROM documents"));
    assertEquals(
        originalSchema,
        ReindexVersion23Fixture.rows(
            backup,
            "SELECT type,name,sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY type,name"));
    Path restored = Files.createDirectory(directory.resolve("restored"));
    Files.copy(backup, restored.resolve("java-library.db"));
    try (var store = new SqliteAuthorityStore(restored)) {
      assertEquals(
          (long) HistoricalSchemaV25Fixture.CURRENT_VERSION,
          store.transaction(() -> store.count("PRAGMA user_version")));
    }
  }

  @Test
  void modifiedWikiGuardFailsClosedOnReopenWithoutChangingPersistedVersion() throws Exception {
    try (var store = new SqliteAuthorityStore(directory)) {
      assertEquals(
          (long) HistoricalSchemaV25Fixture.CURRENT_VERSION,
          store.transaction(() -> store.count("PRAGMA user_version")));
    }
    Path database = directory.resolve("java-library.db");
    ReindexVersion23Fixture.execute(database, "DROP TRIGGER wiki_page_revisions_no_update");
    assertThrows(IllegalStateException.class, () -> new SqliteAuthorityStore(directory));
    assertEquals(
        HistoricalSchemaV25Fixture.CURRENT_VERSION,
        ((Number)
                ReindexVersion23Fixture.rows(database, "PRAGMA user_version")
                    .getFirst()
                    .get("user_version"))
            .intValue());
  }

  private Path versionThirty() throws Exception {
    Path seed = directory.resolve("seed");
    IngestionMigrationTest.versionOne(seed);
    Path database = seed.resolve("java-library.db");
    for (String sql :
        List.of(
            "CREATE INDEX documents_workspace ON documents(workspace_id,updated_at,id)",
            "CREATE INDEX documents_folder ON documents(folder_id,workspace_id)",
            "CREATE INDEX acl_principal ON document_acl(principal_id,document_id)",
            "CREATE INDEX audit_actor ON management_audit(workspace_id,actor_id,created_at)")) {
      ReindexVersion23Fixture.execute(database, sql);
    }
    try (var store = new SqliteAuthorityStore(seed)) {
      assertTrue(store.transaction(() -> store.count("SELECT COUNT(*) FROM documents")) > 0);
    }
    Path backup;
    try (var paths = Files.list(seed)) {
      backup =
          paths
              .filter(
                  path ->
                      path.getFileName().toString().startsWith("java-library.v30-before-v31-")
                          && path.toString().endsWith(".db"))
              .findFirst()
              .orElseThrow();
    }
    Path target = Files.createDirectory(directory.resolve("upgrade"));
    Files.copy(backup, target.resolve("java-library.db"));
    return target;
  }
}
