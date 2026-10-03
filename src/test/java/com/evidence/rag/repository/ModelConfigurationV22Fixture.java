package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.evidence.rag.model.domain.ModelValues;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Test-only inverse of v23, refusing data that did not exist in the real v22 vocabulary. */
final class ModelConfigurationV22Fixture {
  private ModelConfigurationV22Fixture() {}

  static void restoreVersionTwentyTwo(Path directory) throws SQLException {
    ReindexVersion23Fixture.restoreVersionTwentyThree(directory);
    try (var connection =
        DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"))) {
      long version = count(connection, "PRAGMA user_version");
      if (version <= 22) {
        return;
      }
      assertEquals(23, version);
      assertEquals(
          0,
          count(
              connection,
              "SELECT COUNT(*) FROM ingestion_jobs WHERE error_code IN ('text_configuration_required','media_text_configuration_mismatch')"));
      String ddl =
          rows(
                  connection,
                  "SELECT sql FROM sqlite_master WHERE name='ingestion_jobs' AND type='table'")
              .getFirst()
              .getFirst();
      String restored =
          ddl.replace(",'text_configuration_required','media_text_configuration_mismatch'", "");
      if (restored.equals(ddl)) {
        throw new AssertionError("v23 ingestion vocabulary missing");
      }
      var objects =
          rows(
              connection,
              "SELECT sql FROM sqlite_master WHERE tbl_name='ingestion_jobs' AND type IN ('index','trigger') AND sql IS NOT NULL ORDER BY type,name");
      String guard =
          rows(
                  connection,
                  "SELECT sql FROM sqlite_master WHERE name='cleanup_schema_objects_no_update'")
              .getFirst()
              .getFirst();
      String columns =
          String.join(
              ",",
              rows(connection, "SELECT name FROM pragma_table_info('ingestion_jobs') ORDER BY cid")
                  .stream()
                  .map(List::getFirst)
                  .toList());
      execute(connection, "PRAGMA foreign_keys=OFF");
      execute(connection, "PRAGMA legacy_alter_table=ON");
      execute(connection, "BEGIN IMMEDIATE");
      try {
        execute(
            connection,
            restored.replaceFirst(
                "CREATE TABLE \"?ingestion_jobs\"?\\(",
                "CREATE TABLE ingestion_jobs_v22_fixture("));
        execute(
            connection,
            "INSERT INTO ingestion_jobs_v22_fixture("
                + columns
                + ") SELECT "
                + columns
                + " FROM ingestion_jobs");
        assertEquals(
            count(connection, "SELECT COUNT(*) FROM ingestion_jobs"),
            count(connection, "SELECT COUNT(*) FROM ingestion_jobs_v22_fixture"));
        assertEquals(
            0,
            count(
                connection,
                "SELECT COUNT(*) FROM (SELECT "
                    + columns
                    + " FROM ingestion_jobs EXCEPT SELECT "
                    + columns
                    + " FROM ingestion_jobs_v22_fixture)"));
        execute(connection, "DROP TABLE ingestion_jobs");
        execute(connection, "ALTER TABLE ingestion_jobs_v22_fixture RENAME TO ingestion_jobs");
        for (var object : objects) {
          execute(connection, object.getFirst());
        }
        String current =
            rows(
                    connection,
                    "SELECT sql FROM sqlite_master WHERE name='ingestion_jobs' AND type='table'")
                .getFirst()
                .getFirst();
        execute(connection, "DROP TRIGGER cleanup_schema_objects_no_update");
        try (var update =
            connection.prepareStatement(
                "UPDATE cleanup_schema_objects SET sql_sha256=? WHERE name='ingestion_jobs' AND object_type='table'")) {
          update.setString(1, ModelValues.sha256(current.getBytes(StandardCharsets.UTF_8)));
          assertEquals(1, update.executeUpdate());
        }
        execute(connection, guard);
        execute(connection, "UPDATE format_info SET version=22");
        execute(connection, "PRAGMA user_version=22");
        assertEquals(0, count(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
        execute(connection, "COMMIT");
      } catch (SQLException | RuntimeException | Error failure) {
        execute(connection, "ROLLBACK");
        throw failure;
      } finally {
        execute(connection, "PRAGMA legacy_alter_table=OFF");
        execute(connection, "PRAGMA foreign_keys=ON");
      }
    }
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
        throw new AssertionError("Missing synthetic fixture result");
      }
      return result.getLong(1);
    }
  }

  private static List<List<String>> rows(Connection connection, String sql) throws SQLException {
    var rows = new ArrayList<List<String>>();
    try (var statement = connection.createStatement();
        var result = statement.executeQuery(sql)) {
      while (result.next()) {
        var values = new ArrayList<String>();
        for (int column = 1; column <= result.getMetaData().getColumnCount(); column++) {
          values.add(result.getString(column));
        }
        rows.add(List.copyOf(values));
      }
    }
    return List.copyOf(rows);
  }
}
