package com.evidence.rag.repository;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;

/** Raw JDBC access to a closed library file, for tests that tamper with or seed stored rows. */
final class LibraryFileFixture {
  private LibraryFileFixture() {}

  static void execute(Path database, String sql) throws SQLException {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  /**
   * Runs {@code sql} with the triggers of {@code table} lifted and then restored with their exact
   * original SQL, so the schema-object inventory still verifies. This seeds rows the current code
   * no longer writes but that libraries upgraded by earlier releases can hold.
   */
  static void executeUnguarded(Path database, String table, String sql) throws SQLException {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
      connection.setAutoCommit(false);
      var triggers = new ArrayList<String[]>();
      try (var query =
          connection.prepareStatement(
              "SELECT name,sql FROM sqlite_master WHERE type='trigger' AND tbl_name=? ORDER BY rowid")) {
        query.setString(1, table);
        try (var rows = query.executeQuery()) {
          while (rows.next()) {
            triggers.add(new String[] {rows.getString(1), rows.getString(2)});
          }
        }
      }
      try (var statement = connection.createStatement()) {
        for (var trigger : triggers) {
          statement.execute("DROP TRIGGER \"" + trigger[0] + "\"");
        }
        statement.execute(sql);
        for (var trigger : triggers) {
          statement.execute(trigger[1]);
        }
      }
      connection.commit();
    }
  }
}
