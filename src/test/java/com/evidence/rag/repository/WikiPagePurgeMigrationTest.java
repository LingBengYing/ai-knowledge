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
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class WikiPagePurgeMigrationTest {
  static final Set<String> REPLACED_GUARDS =
      Set.of(
          "wiki_page_revisions_no_delete",
          "wiki_proposals_no_delete",
          "wiki_page_lifecycle_no_delete");
  @TempDir Path directory;

  @Test
  void upgradePreservesEveryBusinessRowAndOnlyReplacesThreeScopedDeletionGuards() throws Exception {
    Path seed = directory.resolve("seed");
    IngestionMigrationTest.versionOne(seed);
    try (var store = new SqliteAuthorityStore(seed)) {
      assertEquals(35L, store.transaction(() -> store.count("PRAGMA user_version")));
    }
    Path source;
    try (var paths = Files.list(seed)) {
      source =
          paths
              .filter(
                  path ->
                      path.getFileName().toString().startsWith("java-library.v34-before-v35-")
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
              .writeValueAsString(WikiWorkspaceRepositoryTest.content("旧知识页", "既有正文")));
      insert.executeUpdate();
    }
    var oldSchema =
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
              for (var row : rows.entrySet()) {
                assertEquals(
                    row.getValue(), store.rows("SELECT * FROM " + row.getKey()), row.getKey());
              }
              var changed = new java.util.HashSet<String>();
              for (var object : oldSchema) {
                var actual =
                    store.rows(
                        "SELECT type,name,sql FROM sqlite_master WHERE type=? AND name=?",
                        object.get("type"),
                        object.get("name"));
                if (!actual.equals(List.of(object))) {
                  changed.add((String) object.get("name"));
                }
              }
              assertEquals(REPLACED_GUARDS, changed);
              assertEquals(0, store.count("SELECT COUNT(*) FROM wiki_page_purges"));
              assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
              assertEquals(
                  "既有正文",
                  new WikiWorkspaceRepository(store)
                      .page(new Actor("org", "member"), "old-page")
                      .orElseThrow()
                      .content()
                      .sections()
                      .getFirst()
                      .body());
              return null;
            });
        HistoricalSchemaV25Fixture.assertMigrationBackups(
            target, store.managedBackups().files(), 34);
        assertThrows(
            RuntimeException.class,
            () ->
                store.transaction(
                    () -> {
                      store.execute("DELETE FROM wiki_page_revisions");
                      return null;
                    }));
      }
    }
    ReindexVersion23Fixture.execute(database, "DROP TRIGGER wiki_page_purges_authorized");
    assertThrows(IllegalStateException.class, () -> new SqliteAuthorityStore(target));
  }

  static List<Map<String, Object>> withoutReplacedGuards(List<Map<String, Object>> rows) {
    return rows.stream().filter(row -> !REPLACED_GUARDS.contains(row.get("name"))).toList();
  }
}
