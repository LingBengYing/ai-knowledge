package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.model.domain.Actor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class WikiPageLifecycleMigrationTest {
  @TempDir Path directory;

  @Test
  void realVersionThirtyTwoMigrationPreservesEveryOldRowAndSchemaThenRestarts() throws Exception {
    Path seed = directory.resolve("seed");
    IngestionMigrationTest.versionOne(seed);
    for (String sql :
        List.of(
            "CREATE INDEX documents_workspace ON documents(workspace_id,updated_at,id)",
            "CREATE INDEX documents_folder ON documents(folder_id,workspace_id)",
            "CREATE INDEX acl_principal ON document_acl(principal_id,document_id)",
            "CREATE INDEX audit_actor ON management_audit(workspace_id,actor_id,created_at)")) {
      ReindexVersion23Fixture.execute(seed.resolve("java-library.db"), sql);
    }
    try (var store = new SqliteAuthorityStore(seed)) {
      assertEquals(35L, store.transaction(() -> store.count("PRAGMA user_version")));
    }
    Path source;
    try (var paths = Files.list(seed)) {
      source =
          paths
              .filter(
                  path ->
                      path.getFileName().toString().startsWith("java-library.v32-before-v33-")
                          && path.toString().endsWith(".db"))
              .findFirst()
              .orElseThrow();
    }
    Path target = Files.createDirectory(directory.resolve("upgrade"));
    Path database = target.resolve("java-library.db");
    Files.copy(source, database);
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var insert =
            connection.prepareStatement(
                "INSERT INTO wiki_page_revisions VALUES('org','old-page',1,?,'extractive-v1','wiki-v1',1)")) {
      insert.setString(
          1,
          JsonMapper.builder()
              .build()
              .writeValueAsString(WikiWorkspaceRepositoryTest.content("旧知识页", "原文保持")));
      insert.executeUpdate();
    }
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
    var actor = new Actor("org", "member");
    try (var store = new SqliteAuthorityStore(target)) {
      store.transaction(
          () -> {
            assertEquals(35, store.count("PRAGMA user_version"));
            assertEquals(
                WikiPagePurgeMigrationTest.withoutReplacedGuards(schema),
                WikiPagePurgeMigrationTest.withoutReplacedGuards(
                    store.rows(
                        "SELECT type,name,sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'wiki_page_lifecycle%' AND name NOT LIKE 'wiki_page_purges%' AND name NOT IN ('wiki_page_revisions_purged','wiki_proposals_purged','import_index_requests') ORDER BY type,name")));
            for (var row : rows.entrySet())
              assertEquals(
                  row.getValue(), store.rows("SELECT * FROM " + row.getKey()), row.getKey());
            var repository = new WikiWorkspaceRepository(store);
            assertEquals("active", repository.lifecycle(actor, "old-page").state());
            assertEquals(0, repository.lifecycle(actor, "old-page").version());
            repository.changeLifecycle(actor, "old-page", 1, 0, "deleted", 2);
            assertEquals(0, repository.pageCount(actor, ""));
            assertEquals(
                "旧知识页", repository.page(actor, "old-page").orElseThrow().content().title());
            assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
            return null;
          });
      HistoricalSchemaV25Fixture.assertMigrationBackups(target, store.managedBackups().files(), 32);
    }
    try (var store = new SqliteAuthorityStore(target)) {
      store.transaction(
          () -> {
            var repository = new WikiWorkspaceRepository(store);
            assertEquals("deleted", repository.lifecycle(actor, "old-page").state());
            repository.changeLifecycle(actor, "old-page", 1, 1, "active", 3);
            assertEquals(1, repository.pageCount(actor, ""));
            assertEquals(2, repository.lifecycle(actor, "old-page").version());
            return null;
          });
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute("DELETE FROM wiki_page_lifecycle");
                    return null;
                  }));
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute("UPDATE wiki_page_lifecycle SET state='deleted'");
                    return null;
                  }));
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute("DELETE FROM wiki_page_revisions");
                    return null;
                  }));
    }
    ReindexVersion23Fixture.execute(database, "DROP TRIGGER wiki_page_lifecycle_no_delete");
    assertThrows(IllegalStateException.class, () -> new SqliteAuthorityStore(target));
  }
}
