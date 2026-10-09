package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.ManagedBackupFile;
import com.evidence.rag.model.domain.ModelValues;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Test-only physical v25 restoration; newer authority history is never discarded. */
final class HistoricalSchemaV25Fixture {
  static final int CURRENT_VERSION = 35;
  private static Template template;

  private HistoricalSchemaV25Fixture() {}

  /** Every upgrade step must retain exactly one correctly labelled and hash-bound snapshot. */
  static void assertMigrationBackups(
      Path directory, List<ManagedBackupFile> backups, int fromVersion) throws Exception {
    assertEquals(CURRENT_VERSION - fromVersion, backups.size());
    for (int version = fromVersion; version < CURRENT_VERSION; version++) {
      String prefix = "java-library.v" + version + "-before-v" + (version + 1) + "-";
      var matching =
          backups.stream().filter(file -> file.relativePath().startsWith(prefix)).toList();
      assertEquals(1, matching.size(), prefix);
      var backup = matching.getFirst();
      Path file = directory.resolve(backup.relativePath());
      assertEquals(Files.size(file), backup.sizeBytes());
      assertEquals(ModelValues.sha256(Files.readAllBytes(file)), backup.sha256());
      try (var connection = connect(file)) {
        assertEquals(version, count(connection, "PRAGMA user_version"));
      }
    }
  }

  static List<Map<String, Object>> versionTwentyFiveObject(
      Path directory, Object type, Object name) {
    try (var paths = Files.list(directory)) {
      Path backup =
          paths
              .filter(
                  path -> path.getFileName().toString().startsWith("java-library.v25-before-v26-"))
              .findFirst()
              .orElseThrow();
      try (var connection = connect(backup);
          var query =
              connection.prepareStatement(
                  "SELECT type,name,sql FROM sqlite_master WHERE type=? AND name=?")) {
        assertEquals(25, count(connection, "PRAGMA user_version"));
        query.setObject(1, type);
        query.setObject(2, name);
        try (var result = query.executeQuery()) {
          assertTrue(result.next());
          return List.of(
              Map.of(
                  "type",
                  result.getString(1),
                  "name",
                  result.getString(2),
                  "sql",
                  result.getString(3)));
        }
      }
    } catch (Exception failure) {
      throw new AssertionError("Cannot verify the original v25 migration schema", failure);
    }
  }

  static void restoreVersionTwentyFive(Path directory) throws SQLException {
    try (var connection = connect(directory.resolve("java-library.db"))) {
      long version = count(connection, "PRAGMA user_version");
      if (version <= 25) {
        return;
      }
      assertTrue(version >= 26 && version <= CURRENT_VERSION);
      var historical = template();
      for (String name : tables(connection)) {
        if (!historical.columns().containsKey(name)) {
          if (name.equals("text_runtime_selection")) {
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM text_runtime_selection"));
            assertEquals(
                1,
                count(
                    connection,
                    "SELECT COUNT(*) FROM text_runtime_selection WHERE id=1 AND initialized=0 AND active_version IS NULL AND configuration_sha256 IS NULL AND anchor_sha256 IS NULL AND batch_id IS NULL AND updated_at='1970-01-01T00:00:00Z'"));
          } else {
            assertEquals(
                0,
                count(connection, "SELECT COUNT(*) FROM " + name),
                "Cannot discard newer authority: " + name);
          }
        }
      }
      for (String table : historical.columns().keySet()) {
        for (String column : columns(connection, table)) {
          if (!historical.columns().get(table).contains(column)) {
            assertEquals(
                0,
                count(
                    connection,
                    "SELECT COUNT(*) FROM " + table + " WHERE " + column + " IS NOT NULL"),
                "Cannot discard newer identity: " + table + "." + column);
          }
        }
      }
      assertEquals(
          0,
          count(
              connection,
              "SELECT COUNT(*) FROM video_compilations WHERE compiler_revision LIKE 'java-video-compiler-v4:%'"));
      assertEquals(
          0, count(connection, "SELECT COUNT(*) FROM indexing_jobs WHERE state='prepared'"));
      var snapshots = new LinkedHashMap<String, List<List<Object>>>();
      for (var entry : historical.columns().entrySet()) {
        if (!entry.getKey().equals("cleanup_schema_objects")) {
          snapshots.put(
              entry.getKey(),
              rows(
                  connection,
                  "SELECT " + String.join(",", entry.getValue()) + " FROM " + entry.getKey()));
        }
      }
      var currentObjects = schema(connection);
      execute(connection, "PRAGMA foreign_keys=OFF");
      execute(connection, "BEGIN IMMEDIATE");
      try {
        for (var object : currentObjects) {
          if (object.type().equals("trigger")) {
            execute(connection, "DROP TRIGGER " + object.name());
          }
        }
        for (String name : tables(connection)) {
          execute(connection, "DROP TABLE " + name);
        }
        for (var object : historical.objects()) {
          if (object.type().equals("table")) {
            execute(connection, object.sql());
          }
        }
        for (var entry : snapshots.entrySet()) {
          String table = entry.getKey();
          var columns = historical.columns().get(table);
          String placeholders =
              String.join(",", java.util.Collections.nCopies(columns.size(), "?"));
          try (var insert =
              connection.prepareStatement(
                  "INSERT INTO "
                      + table
                      + "("
                      + String.join(",", columns)
                      + ") VALUES("
                      + placeholders
                      + ")")) {
            for (var row : entry.getValue()) {
              for (int i = 0; i < row.size(); i++) {
                insert.setObject(i + 1, row.get(i));
              }
              assertEquals(1, insert.executeUpdate());
            }
          }
          assertEquals(
              canonical(entry.getValue()),
              canonical(rows(connection, "SELECT " + String.join(",", columns) + " FROM " + table)),
              "Historical rows changed: " + table);
        }
        execute(connection, "UPDATE format_info SET version=25");
        execute(connection, "PRAGMA user_version=25");
        for (var object : historical.objects()) {
          try (var insert =
              connection.prepareStatement(
                  "INSERT INTO cleanup_schema_objects(name,object_type,sql_sha256) VALUES(?,?,?)")) {
            insert.setString(1, object.name());
            insert.setString(2, object.type());
            insert.setString(3, ModelValues.sha256(object.sql().getBytes(StandardCharsets.UTF_8)));
            assertEquals(1, insert.executeUpdate());
          }
        }
        for (var object : historical.objects()) {
          if (!object.type().equals("table")) {
            execute(connection, object.sql());
          }
        }
        assertEquals(
            historical.objects(),
            schema(connection),
            "Physical v25 schema differs from the production-created historical snapshot");
        assertEquals(0, count(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
        execute(connection, "COMMIT");
      } catch (SQLException | RuntimeException | Error failure) {
        execute(connection, "ROLLBACK");
        throw failure;
      } finally {
        execute(connection, "PRAGMA foreign_keys=ON");
      }
    }
  }

  /** Actual v1 -> latest migration provides the physical v25 snapshot, not inferred DDL. */
  private static synchronized Template template() {
    if (template != null) {
      return template;
    }
    Path directory = null;
    try {
      directory = Files.createTempDirectory("rag-historical-v25-template-");
      IngestionMigrationTest.versionOne(directory);
      try (var connection = connect(directory.resolve("java-library.db"))) {
        execute(
            connection,
            "CREATE INDEX documents_workspace ON documents(workspace_id,updated_at,id)");
        execute(connection, "CREATE INDEX documents_folder ON documents(folder_id,workspace_id)");
        execute(connection, "CREATE INDEX acl_principal ON document_acl(principal_id,document_id)");
        execute(
            connection,
            "CREATE INDEX audit_actor ON management_audit(workspace_id,actor_id,created_at)");
      }
      try (var store = new SqliteAuthorityStore(directory)) {
        assertEquals(CURRENT_VERSION, store.transaction(() -> store.count("PRAGMA user_version")));
      }
      Path backup;
      try (var paths = Files.list(directory)) {
        backup =
            paths
                .filter(
                    path ->
                        path.getFileName().toString().startsWith("java-library.v25-before-v26-"))
                .findFirst()
                .orElseThrow();
      }
      try (var connection = connect(backup)) {
        assertEquals(25, count(connection, "PRAGMA user_version"));
        var columns = new LinkedHashMap<String, List<String>>();
        for (String table : tables(connection)) {
          columns.put(table, columns(connection, table));
        }
        template = new Template(schema(connection), columns);
        return template;
      }
    } catch (Exception failure) {
      throw new AssertionError("Unable to construct actual v25 fixture schema", failure);
    } finally {
      if (directory != null) {
        try (var paths = Files.walk(directory)) {
          for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
            Files.delete(path);
          }
        } catch (Exception failure) {
          throw new AssertionError(
              "Unable to clean the owned historical fixture template", failure);
        }
      }
    }
  }

  private static List<List<Object>> canonical(List<List<Object>> rows) {
    return rows.stream()
        .map(
            row ->
                row.stream()
                    .map(
                        value ->
                            value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : value)
                    .toList())
        .toList();
  }

  private static Connection connect(Path database) throws SQLException {
    return DriverManager.getConnection("jdbc:sqlite:" + database);
  }

  private static List<String> tables(Connection connection) throws SQLException {
    return rows(
            connection,
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
        .stream()
        .map(row -> (String) row.getFirst())
        .toList();
  }

  private static List<String> columns(Connection connection, String table) throws SQLException {
    return rows(connection, "SELECT name FROM pragma_table_info('" + table + "') ORDER BY cid")
        .stream()
        .map(row -> (String) row.getFirst())
        .toList();
  }

  private static List<SchemaObject> schema(Connection connection) throws SQLException {
    return rows(
            connection,
            "SELECT type,name,sql FROM sqlite_master WHERE type IN ('table','index','trigger') AND sql IS NOT NULL ORDER BY type,name")
        .stream()
        .map(row -> new SchemaObject((String) row.get(0), (String) row.get(1), (String) row.get(2)))
        .toList();
  }

  private static List<List<Object>> rows(Connection connection, String sql) throws SQLException {
    var values = new ArrayList<List<Object>>();
    try (var statement = connection.createStatement();
        var result = statement.executeQuery(sql)) {
      while (result.next()) {
        var row = new ArrayList<Object>();
        for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) {
          row.add(result.getObject(i));
        }
        values.add(row);
      }
    }
    return values;
  }

  private static long count(Connection connection, String sql) throws SQLException {
    return ((Number) rows(connection, sql).getFirst().getFirst()).longValue();
  }

  private static void execute(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private record SchemaObject(String type, String name, String sql) {}

  private record Template(List<SchemaObject> objects, Map<String, List<String>> columns) {}
}
