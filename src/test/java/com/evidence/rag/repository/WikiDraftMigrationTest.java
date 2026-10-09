package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.WikiDraft;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WikiDraftMigrationTest {
  @TempDir Path directory;

  @Test
  void versionThirtyOneUpgradePreservesEveryOldRowAndSchemaAndKeepsExactBackup() throws Exception {
    Path seed = directory.resolve("seed");
    IngestionMigrationTest.versionOne(seed);
    Path seedDatabase = seed.resolve("java-library.db");
    for (String sql :
        List.of(
            "CREATE INDEX documents_workspace ON documents(workspace_id,updated_at,id)",
            "CREATE INDEX documents_folder ON documents(folder_id,workspace_id)",
            "CREATE INDEX acl_principal ON document_acl(principal_id,document_id)",
            "CREATE INDEX audit_actor ON management_audit(workspace_id,actor_id,created_at)")) {
      ReindexVersion23Fixture.execute(seedDatabase, sql);
    }
    try (var store = new SqliteAuthorityStore(seed)) {
      assertEquals(32L, store.transaction(() -> store.count("PRAGMA user_version")));
    }
    Path source;
    try (var paths = Files.list(seed)) {
      source =
          paths
              .filter(
                  path ->
                      path.getFileName().toString().startsWith("java-library.v31-before-v32-")
                          && path.toString().endsWith(".db"))
              .findFirst()
              .orElseThrow();
    }
    Path target = Files.createDirectory(directory.resolve("upgrade"));
    Path database = target.resolve("java-library.db");
    Files.copy(source, database);
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
    try (var store = new SqliteAuthorityStore(target)) {
      store.transaction(
          () -> {
            assertEquals(32, store.count("PRAGMA user_version"));
            assertEquals(
                schema,
                store.rows(
                    "SELECT type,name,sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT IN ('wiki_drafts','wiki_drafts_updated','wiki_drafts_version') ORDER BY type,name"));
            for (var row : rows.entrySet()) {
              assertEquals(
                  row.getValue(), store.rows("SELECT * FROM " + row.getKey()), row.getKey());
            }
            assertEquals(0, store.count("SELECT COUNT(*) FROM wiki_drafts"));
            assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
            new WikiDraftRepository(store)
                .insert(new Actor("org", "member"), new WikiDraft("draft", "标题", "正文", 1, 1, 1));
            return null;
          });
      HistoricalSchemaV25Fixture.assertMigrationBackups(target, store.managedBackups().files(), 31);
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute("UPDATE wiki_drafts SET version=4 WHERE id='draft'");
                    return null;
                  }));
    }
    try (var store = new SqliteAuthorityStore(target)) {
      assertEquals(
          "正文",
          store.transaction(
              () ->
                  new WikiDraftRepository(store)
                      .find(new Actor("org", "second"), "draft")
                      .orElseThrow()
                      .body()));
    }
    ReindexVersion23Fixture.execute(database, "DROP TRIGGER wiki_drafts_version");
    assertThrows(IllegalStateException.class, () -> new SqliteAuthorityStore(target));
  }
}
