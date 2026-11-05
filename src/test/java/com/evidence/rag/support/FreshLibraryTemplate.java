package com.evidence.rag.support;

import com.evidence.rag.repository.SqliteAuthorityStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Auto-registered for every test class (see {@code junit-platform.properties}). Creating a fresh
 * current-version library runs every schema rebuild and verification and costs over a second of
 * CPU; nearly every test needs one. Each test JVM therefore builds one library through the real
 * constructor and every later fresh library starts as a copy of it with its own library id. The
 * copy is then opened through the normal existing-library verification. Migration tests are
 * unaffected because they write their old-version database file before opening the store.
 */
public final class FreshLibraryTemplate implements BeforeAllCallback {
  private static final String DATABASE = "java-library.db";
  private static Path template;
  private static boolean building;

  static {
    SqliteAuthorityStore.seedFreshLibrariesWith(FreshLibraryTemplate::seed);
  }

  @Override
  public void beforeAll(ExtensionContext context) {
    // Loading this class installs the seeder before any test constructs a store.
  }

  private static synchronized void seed(Path database) {
    if (building) {
      return;
    }
    try {
      if (template == null) {
        building = true;
        try {
          Path directory = Files.createTempDirectory("rag-library-template");
          new SqliteAuthorityStore(directory).close();
          template = directory.resolve(DATABASE);
          Runtime.getRuntime()
              .addShutdownHook(
                  new Thread(
                      () -> {
                        try {
                          Files.deleteIfExists(template);
                          Files.deleteIfExists(directory);
                        } catch (IOException ignored) {
                          // Best effort: the JVM is exiting.
                        }
                      }));
        } finally {
          building = false;
        }
      }
      Files.copy(template, database);
      assignNewLibraryId(database);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    } catch (SQLException error) {
      throw new IllegalStateException("Cannot seed test library", error);
    }
  }

  /** The library id is immutable by trigger, so lift the triggers for this one write only. */
  private static void assignNewLibraryId(Path database) throws SQLException {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
      connection.setAutoCommit(false);
      var triggers = new ArrayList<String[]>();
      try (var statement =
          connection.prepareStatement(
              "SELECT name, sql FROM sqlite_master WHERE type='trigger' AND tbl_name=?")) {
        statement.setString(1, "cleanup_library");
        try (var rows = statement.executeQuery()) {
          while (rows.next()) {
            triggers.add(new String[] {rows.getString(1), rows.getString(2)});
          }
        }
      }
      try (var statement = connection.createStatement()) {
        for (var trigger : triggers) {
          statement.execute("DROP TRIGGER \"" + trigger[0].replace("\"", "\"\"") + "\"");
        }
        try (var update =
            connection.prepareStatement("UPDATE cleanup_library SET library_id=? WHERE id=1")) {
          update.setString(1, UUID.randomUUID().toString());
          if (update.executeUpdate() != 1) {
            throw new SQLException("Template library has no cleanup_library row");
          }
        }
        for (var trigger : triggers) {
          statement.execute(trigger[1]);
        }
      }
      connection.commit();
    }
  }
}
