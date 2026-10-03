package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Test-only inverse of v22 on unclaimed synthetic data; retains every v21 row and constraint. */
final class CleanupV21Fixture {
  private CleanupV21Fixture() {}

  static void restoreVersionTwentyOne(Path directory) throws SQLException {
    ModelConfigurationV22Fixture.restoreVersionTwentyTwo(directory);
    try (var connection =
        DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"))) {
      if (count(connection, "PRAGMA user_version") == 21) {
        return;
      }
      assertEquals(22, count(connection, "PRAGMA user_version"));
      for (String table :
          List.of(
              "document_cleanups",
              "cleanup_resources",
              "cleanup_plans",
              "cleanup_payloads",
              "cleanup_projection_attempts",
              "cleanup_managed_backups")) {
        assertEquals(0, count(connection, "SELECT COUNT(*) FROM " + table), table);
      }
      for (String table : CleanupPayloadTables.names()) {
        assertEquals(
            0,
            count(connection, "SELECT COUNT(*) FROM " + table + " WHERE payload_purged!=0"),
            table);
      }
      execute(connection, "PRAGMA foreign_keys=OFF");
      execute(connection, "PRAGMA legacy_alter_table=ON");
      execute(connection, "BEGIN IMMEDIATE");
      try {
        for (var row :
            rows(
                connection,
                "SELECT name FROM sqlite_master WHERE type='trigger' AND (name LIKE 'cleanup_%' OR name LIKE 'document_cleanups_%')")) {
          execute(connection, "DROP TRIGGER " + row.getFirst());
        }
        for (var table : CleanupPayloadTables.TABLES) {
          String name = table.name();
          String ddl =
              rows(
                      connection,
                      "SELECT sql FROM sqlite_master WHERE type='table' AND name='" + name + "'")
                  .getFirst()
                  .getFirst();
          ddl =
              ddl.replace(
                  "payload_purged INTEGER NOT NULL DEFAULT 0 CHECK(payload_purged IN (0,1)),", "");
          String marker =
              ",CHECK(payload_purged=0 OR (" + table.empty(name).replace(name + ".", "") + "))";
          ddl = ddl.replace(marker, "");
          ddl = unwrapChecks(ddl);
          if (ddl.contains("payload_purged")) {
            throw new AssertionError("Unexpected cleanup marker in fixture DDL");
          }
          var columns =
              rows(
                      connection,
                      "SELECT name FROM pragma_table_info('"
                          + name
                          + "') WHERE name!='payload_purged' ORDER BY cid")
                  .stream()
                  .map(List::getFirst)
                  .toList();
          var objects =
              rows(
                  connection,
                  "SELECT type,sql FROM sqlite_master WHERE tbl_name='"
                      + name
                      + "' AND type IN ('trigger','index') AND sql IS NOT NULL ORDER BY name");
          ddl =
              ddl.replaceFirst(
                  "(?i)CREATE TABLE\\s+\"?" + Pattern.quote(name) + "\"?",
                  "CREATE TABLE " + name + "_v21_fixture");
          execute(connection, ddl);
          String joined = String.join(",", columns);
          execute(
              connection,
              "INSERT INTO "
                  + name
                  + "_v21_fixture("
                  + joined
                  + ") SELECT "
                  + joined
                  + " FROM "
                  + name);
          assertEquals(
              0,
              count(
                  connection,
                  "SELECT COUNT(*) FROM (SELECT "
                      + joined
                      + " FROM "
                      + name
                      + " EXCEPT SELECT "
                      + joined
                      + " FROM "
                      + name
                      + "_v21_fixture)"));
          assertEquals(
              0,
              count(
                  connection,
                  "SELECT COUNT(*) FROM (SELECT "
                      + joined
                      + " FROM "
                      + name
                      + "_v21_fixture EXCEPT SELECT "
                      + joined
                      + " FROM "
                      + name
                      + ")"));
          execute(connection, "DROP TABLE " + name);
          execute(connection, "ALTER TABLE " + name + "_v21_fixture RENAME TO " + name);
          for (var object : objects) {
            String sql = object.get(1);
            int when = sql.indexOf(" WHEN NOT (");
            if (object.getFirst().equals("trigger")
                && when >= 0
                && sql.contains("java_cleanup_authorized")) {
              int end = closing(sql, when + " WHEN NOT ".length());
              int original = end + " AND ".length() + 1;
              if (sql.charAt(original) != '(') {
                throw new AssertionError("Unexpected cleanup guard wrapper");
              }
              int originalEnd = closing(sql, original);
              String oldWhen = sql.substring(original + 1, originalEnd);
              sql =
                  sql.substring(0, when)
                      + (oldWhen.equals("1") ? " " : " WHEN " + oldWhen + " ")
                      + sql.substring(originalEnd + 1).stripLeading();
            }
            execute(connection, sql);
          }
        }
        for (String table :
            List.of(
                "cleanup_payloads",
                "cleanup_plans",
                "cleanup_resources",
                "cleanup_projection_attempts",
                "cleanup_document_provenance",
                "cleanup_managed_backups",
                "document_cleanups",
                "cleanup_schema_objects",
                "cleanup_library")) {
          execute(connection, "DROP TABLE " + table);
        }
        execute(connection, "UPDATE format_info SET version=21");
        execute(connection, "PRAGMA user_version=21");
        assertEquals(0, count(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
        execute(connection, "COMMIT");
      } catch (SQLException | RuntimeException | Error failure) {
        execute(connection, "ROLLBACK");
        throw failure;
      } finally {
        execute(connection, "PRAGMA legacy_alter_table=OFF");
        execute(connection, "PRAGMA foreign_keys=ON");
      }
      assertEquals(1, count(connection, "PRAGMA foreign_keys"));
    }
  }

  private static String unwrapChecks(String ddl) {
    String ddlMarker = "payload_purged=1 OR (";
    for (int at = ddl.indexOf(ddlMarker); at >= 0; at = ddl.indexOf(ddlMarker)) {
      int opening = at + ddlMarker.length() - 1;
      int end = closing(ddl, opening);
      ddl = ddl.substring(0, at) + ddl.substring(opening + 1, end) + ddl.substring(end + 1);
    }
    return ddl;
  }

  private static int closing(String value, int opening) {
    int depth = 0;
    boolean quoted = false;
    for (int i = opening; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '\'') {
        if (quoted && i + 1 < value.length() && value.charAt(i + 1) == '\'') {
          i++;
        } else {
          quoted = !quoted;
        }
      } else if (!quoted && c == '(') {
        depth++;
      } else if (!quoted && c == ')' && --depth == 0) {
        return i;
      }
    }
    throw new AssertionError("Unbalanced fixture DDL");
  }

  private static void execute(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static long count(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement();
        var result = statement.executeQuery(sql)) {
      if (!result.next()) {
        throw new AssertionError("Missing fixture scalar");
      }
      return result.getLong(1);
    }
  }

  private static List<List<String>> rows(Connection connection, String sql) throws SQLException {
    var output = new ArrayList<List<String>>();
    try (var statement = connection.createStatement();
        var result = statement.executeQuery(sql)) {
      while (result.next()) {
        var row = new ArrayList<String>();
        for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) {
          row.add(result.getString(i));
        }
        output.add(List.copyOf(row));
      }
    }
    return List.copyOf(output);
  }
}
