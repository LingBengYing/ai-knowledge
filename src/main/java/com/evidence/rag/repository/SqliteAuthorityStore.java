package com.evidence.rag.repository;

import com.evidence.rag.exception.ApplicationException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Single connection, single monitor, same-thread authority transactions. Raw SQL is package-private
 * and is rejected unless the caller is inside this store's current transaction.
 */
public final class SqliteAuthorityStore implements AutoCloseable {
  private Connection connection;
  private FileChannel lockChannel;
  private FileLock writerLock;
  private Thread transactionOwner;

  public SqliteAuthorityStore(Path directory) {
    try {
      Files.createDirectories(directory);
      if (Files.isSymbolicLink(directory)
          || Files.exists(directory.resolve("rag.db"))
          || Files.exists(directory.resolve("authority.db"))) {
        throw new IllegalStateException("An isolated Java data directory is required");
      }
      Path canonicalDirectory = directory.toRealPath();
      lockChannel =
          FileChannel.open(
              canonicalDirectory.resolve(".java-library.lock"),
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
              LinkOption.NOFOLLOW_LINKS);
      writerLock = lockChannel.tryLock();
      if (writerLock == null) {
        throw new IllegalStateException("Java library already has a writer");
      }
      Path database = canonicalDirectory.resolve("java-library.db");
      boolean exists = Files.exists(database, LinkOption.NOFOLLOW_LINKS);
      if (exists && !Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalStateException("Unsafe Java database path");
      }
      connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
      var schema = new AuthoritySchema(this);
      if (exists) {
        transaction(
            () -> {
              schema.verifyFormat();
              return null;
            });
      }
      rawExecute("PRAGMA foreign_keys=ON");
      rawExecute("PRAGMA busy_timeout=5000");
      if (!exists) {
        schema.initialize();
      }
      if (transaction(() -> count("PRAGMA user_version")) == 1) {
        if (exists) {
          schema.backupVersionOne(canonicalDirectory);
        }
        schema.migrateVersionTwo();
      }
      if (transaction(() -> count("PRAGMA user_version")) == 2) {
        if (exists) {
          schema.backupVersion(canonicalDirectory, 2, 3);
        }
        schema.migrateVersionThree();
      }
      if (transaction(() -> count("PRAGMA user_version")) == 3) {
        if (exists) {
          schema.backupVersion(canonicalDirectory, 3, 4);
        }
        schema.migrateVersionFour();
      }
      if (transaction(() -> count("PRAGMA user_version")) == 4) {
        if (exists) {
          schema.backupVersion(canonicalDirectory, 4, 5);
        }
        schema.migrateVersionFive();
      }
      if (transaction(() -> count("PRAGMA user_version")) == 5) {
        if (exists) {
          schema.backupVersion(canonicalDirectory, 5, 6);
        }
        schema.migrateVersionSix();
      }
      if (transaction(() -> count("PRAGMA user_version")) == 6) {
        if (exists) {
          schema.backupVersion(canonicalDirectory, 6, 7);
        }
        schema.migrateVersionSeven();
      }
      if (transaction(() -> count("PRAGMA user_version")) == 7) {
        if (exists) {
          schema.backupVersion(canonicalDirectory, 7, 8);
        }
        schema.migrateVersionEight();
      }
      if (transaction(() -> count("PRAGMA user_version")) == 8) {
        if (exists) {
          schema.backupVersion(canonicalDirectory, 8, 9);
        }
        schema.migrateVersionNine();
      }
      if (transaction(() -> count("PRAGMA user_version")) == 9) {
        if (exists) {
          schema.backupVersion(canonicalDirectory, 9, 10);
        }
        schema.migrateVersionTen();
      }
      if (transaction(() -> count("PRAGMA user_version")) == 10) {
        if (exists) {
          schema.backupVersion(canonicalDirectory, 10, 11);
        }
        schema.migrateVersionEleven();
      }
      if (transaction(() -> count("PRAGMA user_version")) == 11) {
        if (exists) {
          schema.backupVersion(canonicalDirectory, 11, 12);
        }
        schema.migrateVersionTwelve();
      }
      if (transaction(() -> count("PRAGMA user_version")) == 12) {
        if (exists) {
          schema.backupVersion(canonicalDirectory, 12, 13);
        }
        schema.migrateVersionThirteen();
      }
      if (transaction(() -> count("PRAGMA user_version")) == 13) {
        if (exists) {
          schema.backupVersion(canonicalDirectory, 13, 14);
        }
        schema.migrateVersionFourteen();
      }
      if (transaction(() -> count("PRAGMA user_version")) == 14) {
        if (exists) {
          schema.backupVersion(canonicalDirectory, 14, 15);
        }
        schema.migrateVersionFifteen();
      }
      if (transaction(() -> count("PRAGMA user_version")) == 15) {
        if (exists) {
          schema.backupVersion(canonicalDirectory, 15, 16);
        }
        schema.migrateVersionSixteen();
      }
    } catch (IOException | SQLException | RuntimeException error) {
      close();
      throw new IllegalStateException("Cannot open isolated Java library");
    }
  }

  public synchronized <T> T transaction(Supplier<T> work) {
    if (connection == null) {
      throw unavailable();
    }
    if (transactionOwner != null) {
      throw new IllegalStateException("Nested authority transactions are not supported");
    }
    try {
      rawExecute("BEGIN IMMEDIATE");
      transactionOwner = Thread.currentThread();
      T result = work.get();
      rawExecute("COMMIT");
      return result;
    } catch (SQLException | RuntimeException failure) {
      try {
        rawExecute("ROLLBACK");
      } catch (SQLException ignored) {
        /* Original failure wins. */
      }
      if (failure instanceof ApplicationException problem) {
        throw problem;
      }
      throw unavailable();
    } finally {
      transactionOwner = null;
    }
  }

  private void requireTransaction() {
    if (transactionOwner != Thread.currentThread() || !Thread.holdsLock(this)) {
      throw new IllegalStateException(
          "Repository access requires the active authority transaction");
    }
  }

  void execute(String sql, Object... args) {
    requireTransaction();
    try {
      rawExecute(sql, args);
    } catch (SQLException failure) {
      throw unavailable();
    }
  }

  long count(String sql, Object... args) {
    requireTransaction();
    try (var statement = prepare(sql, args);
        var result = statement.executeQuery()) {
      result.next();
      return result.getLong(1);
    } catch (SQLException failure) {
      throw unavailable();
    }
  }

  List<Map<String, Object>> rows(String sql, Object... args) {
    requireTransaction();
    try (var statement = prepare(sql, args);
        var result = statement.executeQuery()) {
      var output = new ArrayList<Map<String, Object>>();
      while (result.next()) {
        var row = new LinkedHashMap<String, Object>();
        for (int index = 1; index <= result.getMetaData().getColumnCount(); index++) {
          row.put(result.getMetaData().getColumnLabel(index), result.getObject(index));
        }
        output.add(row);
      }
      return output;
    } catch (SQLException failure) {
      throw unavailable();
    }
  }

  synchronized void backupSnapshot(Path destination) throws SQLException {
    if (transactionOwner != null || connection == null) {
      throw new IllegalStateException("Backup requires an idle authority store");
    }
    rawExecute("VACUUM INTO ?", destination.toString());
  }

  private PreparedStatement prepare(String sql, Object... args) throws SQLException {
    var statement = connection.prepareStatement(sql);
    try {
      for (int index = 0; index < args.length; index++) {
        statement.setObject(index + 1, args[index]);
      }
      return statement;
    } catch (SQLException failure) {
      statement.close();
      throw failure;
    }
  }

  private void rawExecute(String sql, Object... args) throws SQLException {
    try (var statement = prepare(sql, args)) {
      statement.execute();
    }
  }

  private static ApplicationException unavailable() {
    return new ApplicationException(
        com.evidence.rag.exception.FailureKind.UNAVAILABLE, "management_unavailable", "资料管理暂不可用。");
  }

  @Override
  public synchronized void close() {
    try {
      if (connection != null) {
        connection.close();
      }
    } catch (SQLException ignored) {
      /* Close remaining resources. */
    }
    connection = null;
    try {
      if (writerLock != null) {
        writerLock.release();
      }
    } catch (IOException ignored) {
      /* Channel close releases lock. */
    }
    try {
      if (lockChannel != null) {
        lockChannel.close();
      }
    } catch (IOException ignored) {
      /* Shutdown best effort. */
    }
  }
}
