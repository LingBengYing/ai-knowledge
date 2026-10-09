package com.evidence.rag.repository;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.CleanupClaim;
import com.evidence.rag.model.domain.CleanupPlan;
import com.evidence.rag.model.domain.LibraryOperationGate;
import com.evidence.rag.model.domain.ManagedBackupFile;
import com.evidence.rag.model.domain.ManagedBackupInventory;
import com.evidence.rag.model.domain.ModelValues;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
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
import java.util.Set;
import java.util.function.Supplier;
import org.sqlite.Function;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Single connection, single monitor, same-thread authority transactions. Raw SQL is package-private
 * and is rejected unless the caller is inside this store's current transaction.
 */
public final class SqliteAuthorityStore implements AutoCloseable {
  private Connection connection;
  private FileChannel lockChannel;
  private FileLock writerLock;
  private Thread transactionOwner;
  private final LibraryOperationGate operationGate;
  private Path libraryDirectory;
  private Path libraryPath;
  private String libraryIdentity;
  private final List<Path> generatedBackups = new ArrayList<>();
  private String modelRebuildId;
  private Set<String> modelRebuildDocuments = Set.of();
  private String replacementDocument;
  private String replacementId;
  private String purgeDocument;
  private Set<String> purgeRows = Set.of();

  public SqliteAuthorityStore(Path directory) {
    try {
      Files.createDirectories(directory);
      if (Files.isSymbolicLink(directory)
          || Files.exists(directory.resolve("rag.db"))
          || Files.exists(directory.resolve("authority.db"))) {
        throw new IllegalStateException("An isolated Java data directory is required");
      }
      Path canonicalDirectory = directory.toRealPath();
      libraryDirectory = canonicalDirectory;
      operationGate = new LibraryOperationGate(canonicalDirectory.resolve("managed-work"));
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
      libraryPath = database;
      boolean exists = Files.exists(database, LinkOption.NOFOLLOW_LINKS);
      if (exists && !Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalStateException("Unsafe Java database path");
      }
      connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
      Function.create(
          connection,
          "java_cleanup_authorized",
          new Function() {
            @Override
            protected void xFunc() {
              try {
                result(
                    args() == 3
                            && transactionOwner == Thread.currentThread()
                            && purgeDocument != null
                            && purgeDocument.equals(value_text(0))
                            && purgeRows.contains(value_text(1) + "\n" + value_text(2))
                        ? 1
                        : 0);
              } catch (SQLException failure) {
                throw unavailable();
              }
            }
          });
      Function.create(
          connection,
          "java_replacement_authorized",
          new Function() {
            @Override
            protected void xFunc() {
              try {
                result(
                    args() == 1
                            && transactionOwner == Thread.currentThread()
                            && replacementDocument != null
                            && replacementDocument.equals(value_text(0))
                        ? 1
                        : 0);
              } catch (SQLException failure) {
                throw unavailable();
              }
            }
          });
      Function.create(
          connection,
          "java_model_rebuild_authorized",
          new Function() {
            @Override
            protected void xFunc() {
              try {
                result(
                    args() == 1
                            && transactionOwner == Thread.currentThread()
                            && modelRebuildId != null
                            && modelRebuildDocuments.contains(value_text(0))
                        ? 1
                        : 0);
              } catch (SQLException failure) {
                throw unavailable();
              }
            }
          });
      Function.create(
          connection,
          "java_model_rebuild_batch_authorized",
          new Function() {
            @Override
            protected void xFunc() {
              try {
                result(
                    args() == 1
                            && transactionOwner == Thread.currentThread()
                            && modelRebuildId != null
                            && modelRebuildId.equals(value_text(0))
                        ? 1
                        : 0);
              } catch (SQLException failure) {
                throw unavailable();
              }
            }
          });
      var schema = new AuthoritySchema(this);
      if (exists) {
        transaction(
            () -> {
              if (count("PRAGMA user_version") == 32) {
                schema.verifyVersionThirtyTwo();
              } else if (count("PRAGMA user_version") == 31) {
                schema.verifyVersionThirtyOne();
              } else if (count("PRAGMA user_version") == 30) {
                schema.verifyVersionThirty();
              } else if (count("PRAGMA user_version") == 29) {
                schema.verifyVersionTwentyNine();
              } else if (count("PRAGMA user_version") == 28) {
                schema.verifyVersionTwentyEight();
              } else if (count("PRAGMA user_version") == 27) {
                schema.verifyVersionTwentySeven();
              } else if (count("PRAGMA user_version") == 26) {
                schema.verifyVersionTwentySix();
              } else if (count("PRAGMA user_version") == 25) {
                schema.verifyVersionTwentyFive();
              } else if (count("PRAGMA user_version") == 24) {
                schema.verifyVersionTwentyFour();
              } else if (count("PRAGMA user_version") == 23) {
                schema.verifyVersionTwentyThree();
              } else if (count("PRAGMA user_version") == 22) {
                new DocumentCleanupSchema(this).verify();
              } else {
                schema.verifyFormat();
              }
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
      if (transaction(() -> count("PRAGMA user_version")) == 16) {
        if (exists) {
          schema.backupVersion(canonicalDirectory, 16, 17);
        }
        schema.migrateVersionSeventeen();
      }
      if (transaction(() -> count("PRAGMA user_version")) == 17) {
        if (exists) {
          schema.backupVersion(canonicalDirectory, 17, 18);
        }
        schema.migrateVersionEighteen();
      }
      if (transaction(() -> count("PRAGMA user_version")) == 18) {
        if (exists) {
          schema.backupVersion(canonicalDirectory, 18, 19);
        }
        schema.migrateVersionNineteen();
      }
      if (transaction(() -> count("PRAGMA user_version")) == 19) {
        if (exists) {
          schema.backupVersion(canonicalDirectory, 19, 20);
        }
        schema.migrateVersionTwenty();
      }
      if (transaction(() -> count("PRAGMA user_version")) == 20) {
        if (exists) {
          schema.backupVersion(canonicalDirectory, 20, 21);
        }
        schema.migrateVersionTwentyOne();
      }
      if (transaction(() -> count("PRAGMA user_version")) == 21) {
        boolean backupsKnown;
        try (var files = Files.list(canonicalDirectory)) {
          backupsKnown =
              files.noneMatch(
                  path -> {
                    String name = path.getFileName().toString();
                    return name.startsWith("java-library.v")
                        && (name.endsWith(".db") || name.endsWith(".partial"))
                        && generatedBackups.stream()
                            .noneMatch(
                                partial ->
                                    completedBackup(partial).equals(path) || partial.equals(path));
                  });
        }
        if (exists) {
          schema.backupVersion(canonicalDirectory, 21, 22);
        }
        rawExecute("PRAGMA foreign_keys=OFF");
        rawExecute("PRAGMA legacy_alter_table=ON");
        try {
          new DocumentCleanupSchema(this).migrate(backupsKnown);
        } finally {
          rawExecute("PRAGMA legacy_alter_table=OFF");
          rawExecute("PRAGMA foreign_keys=ON");
        }
      }
      if (transaction(() -> count("PRAGMA user_version")) == 22) {
        if (exists) {
          schema.backupVersion(canonicalDirectory, 22, 23);
        }
        rawExecute("PRAGMA foreign_keys=OFF");
        rawExecute("PRAGMA legacy_alter_table=ON");
        try {
          schema.migrateVersionTwentyThree();
        } finally {
          rawExecute("PRAGMA legacy_alter_table=OFF");
          rawExecute("PRAGMA foreign_keys=ON");
        }
      }
      if (transaction(() -> count("PRAGMA user_version")) == 23) {
        if (exists) {
          schema.backupVersion(canonicalDirectory, 23, 24);
        }
        rawExecute("PRAGMA foreign_keys=OFF");
        rawExecute("PRAGMA legacy_alter_table=ON");
        try {
          schema.migrateVersionTwentyFour();
        } finally {
          rawExecute("PRAGMA legacy_alter_table=OFF");
          rawExecute("PRAGMA foreign_keys=ON");
        }
      }
      if (transaction(() -> count("PRAGMA user_version")) == 24) {
        if (exists) {
          schema.backupVersion(canonicalDirectory, 24, 25);
        }
        schema.migrateVersionTwentyFive();
      }
      if (transaction(() -> count("PRAGMA user_version")) == 25) {
        if (exists) {
          schema.backupVersion(canonicalDirectory, 25, 26);
        }
        rawExecute("PRAGMA foreign_keys=OFF");
        rawExecute("PRAGMA legacy_alter_table=ON");
        try {
          schema.migrateVersionTwentySix();
        } finally {
          rawExecute("PRAGMA legacy_alter_table=OFF");
          rawExecute("PRAGMA foreign_keys=ON");
        }
      }
      if (transaction(() -> count("PRAGMA user_version")) == 26) {
        if (exists) {
          schema.backupVersion(canonicalDirectory, 26, 27);
        }
        rawExecute("PRAGMA foreign_keys=OFF");
        rawExecute("PRAGMA legacy_alter_table=ON");
        try {
          schema.migrateVersionTwentySeven();
        } finally {
          rawExecute("PRAGMA legacy_alter_table=OFF");
          rawExecute("PRAGMA foreign_keys=ON");
        }
      }
      if (transaction(() -> count("PRAGMA user_version")) == 27) {
        if (exists) {
          schema.backupVersion(canonicalDirectory, 27, 28);
        }
        rawExecute("PRAGMA foreign_keys=OFF");
        rawExecute("PRAGMA legacy_alter_table=ON");
        try {
          schema.migrateVersionTwentyEight();
        } finally {
          rawExecute("PRAGMA legacy_alter_table=OFF");
          rawExecute("PRAGMA foreign_keys=ON");
        }
      }
      if (transaction(() -> count("PRAGMA user_version")) == 28) {
        if (exists) {
          schema.backupVersion(canonicalDirectory, 28, 29);
        }
        schema.migrateVersionTwentyNine();
      }
      if (transaction(() -> count("PRAGMA user_version")) == 29) {
        if (exists) {
          schema.backupVersion(canonicalDirectory, 29, 30);
        }
        rawExecute("PRAGMA foreign_keys=OFF");
        rawExecute("PRAGMA legacy_alter_table=ON");
        try {
          schema.migrateVersionThirty();
        } finally {
          rawExecute("PRAGMA legacy_alter_table=OFF");
          rawExecute("PRAGMA foreign_keys=ON");
        }
      }
      if (transaction(() -> count("PRAGMA user_version")) == 30) {
        if (exists) {
          schema.backupVersion(canonicalDirectory, 30, 31);
        }
        schema.migrateVersionThirtyOne();
      }
      if (transaction(() -> count("PRAGMA user_version")) == 31) {
        if (exists) {
          schema.backupVersion(canonicalDirectory, 31, 32);
        }
        schema.migrateVersionThirtyTwo();
      }
      transaction(
          () -> {
            schema.verifyVersionThirtyTwo();
            libraryIdentity =
                (String)
                    rows("SELECT library_id FROM cleanup_library WHERE id=1")
                        .getFirst()
                        .get("library_id");
            for (Path partial : generatedBackups) {
              Path backup = completedBackup(partial);
              try {
                if (Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)) {
                  execute(
                      "INSERT INTO cleanup_managed_backups(relative_path,sha256,size_bytes) VALUES(?,?,?)",
                      backup.getFileName().toString(),
                      fileHash(backup),
                      Files.size(backup));
                }
              } catch (IOException error) {
                throw new IllegalStateException("Cannot record managed snapshot");
              }
            }
            return null;
          });
      verifyRestoreJournal();
      transaction(
          () -> {
            execute(
                "UPDATE document_cleanups SET state='pending',claim_sha256=NULL WHERE state='running'");
            return null;
          });
      if (transaction(() -> count("PRAGMA foreign_keys")) != 1) {
        throw new IllegalStateException("Authority foreign keys are disabled");
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

  <T> T modelRebuildScope(String id, List<String> documents, Supplier<T> work) {
    requireTransaction();
    if (modelRebuildId != null) {
      throw ModelValues.invalid();
    }
    modelRebuildId = id;
    modelRebuildDocuments = Set.copyOf(documents);
    try {
      return work.get();
    } finally {
      modelRebuildId = null;
      modelRebuildDocuments = Set.of();
    }
  }

  boolean modelRebuildAuthorized(String id) {
    requireTransaction();
    return id.equals(modelRebuildId);
  }

  <T> T replacementScope(String id, String documentId, Supplier<T> work) {
    requireTransaction();
    if (replacementDocument != null) {
      if (!documentId.equals(replacementDocument) || !id.equals(replacementId)) {
        throw ModelValues.invalid();
      }
      return work.get();
    }
    if (!new DocumentUpdateRepository(this).sourceCurrent(id)) {
      throw ModelValues.invalid();
    }
    replacementDocument = documentId;
    replacementId = id;
    try {
      return work.get();
    } finally {
      replacementDocument = null;
      replacementId = null;
    }
  }

  boolean replacementAuthorized(String documentId) {
    requireTransaction();
    return documentId.equals(replacementDocument);
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
    generatedBackups.add(destination);
  }

  public LibraryOperationGate operationGate() {
    return operationGate;
  }

  public Path libraryPath() {
    return libraryPath;
  }

  public String libraryIdentity() {
    return libraryIdentity;
  }

  public void purge(
      CleanupClaim claim, CleanupPlan plan, LibraryOperationGate.MaintenanceLease lease) {
    maintenance(lease);
    verifyRestoreJournal();
    transaction(
        () -> {
          var repository = new DocumentCleanupRepository(this);
          var authorized = repository.validatePurge(claim, plan);
          requireJournalIntent(plan);
          execute("PRAGMA secure_delete=ON");
          purgeDocument = claim.documentId();
          purgeRows = authorized;
          try {
            repository.purgeBodies(claim);
          } finally {
            purgeRows = Set.of();
            purgeDocument = null;
          }
          return null;
        });
    recordHighWater(plan);
  }

  public synchronized void compact(
      CleanupClaim claim, LibraryOperationGate.MaintenanceLease lease) {
    maintenance(lease);
    if (transactionOwner != null) {
      throw new IllegalStateException("Compaction requires idle authority transaction");
    }
    verifyRestoreJournal();
    transaction(
        () -> {
          var repository = new DocumentCleanupRepository(this);
          if (!repository.current(claim)) {
            throw ModelValues.invalid();
          }
          var plan = repository.plan(claim.cleanupId()).orElseThrow(ModelValues::invalid);
          repository.validatePurge(claim, plan);
          repository.verifyPurged(claim.documentId(), claim.cleanupId());
          repository.verifyPurgedRows();
          return null;
        });
    try {
      rawExecute("PRAGMA secure_delete=ON");
      rawExecute("PRAGMA wal_checkpoint(TRUNCATE)");
      rawExecute("VACUUM");
      force(libraryPath);
      force(libraryDirectory);
      transaction(
          () -> {
            new AuthoritySchema(this).verifyVersionTwentyNine();
            return null;
          });
    } catch (IOException | SQLException failure) {
      throw unavailable();
    }
  }

  public ManagedBackupInventory managedBackups() {
    var saved =
        transaction(
            () ->
                new ManagedBackupInventory(
                    count("SELECT backups_known FROM cleanup_library WHERE id=1") == 1,
                    rows(
                            "SELECT relative_path,sha256,size_bytes FROM cleanup_managed_backups ORDER BY relative_path")
                        .stream()
                        .map(
                            r ->
                                new ManagedBackupFile(
                                    (String) r.get("relative_path"),
                                    (String) r.get("sha256"),
                                    ((Number) r.get("size_bytes")).longValue()))
                        .toList()));
    try (var files = Files.list(libraryDirectory)) {
      boolean unknown =
          files.anyMatch(
              path -> {
                String name = path.getFileName().toString();
                return (name.startsWith("java-library.v") || name.startsWith(".cleanup-backup-"))
                    && saved.files().stream()
                        .noneMatch(registered -> registered.relativePath().equals(name));
              });
      return new ManagedBackupInventory(saved.known() && !unknown, saved.files());
    } catch (IOException failure) {
      throw unavailable();
    }
  }

  public void recordManagedBackup(
      String relativePath,
      String sha256,
      long sizeBytes,
      LibraryOperationGate.MaintenanceLease lease) {
    maintenance(lease);
    var value = new ManagedBackupFile(relativePath, sha256, sizeBytes);
    Path path = libraryDirectory.resolve(value.relativePath());
    try {
      if (!value.relativePath().matches("java-library\\.v[0-9]+-before-v[0-9]+-[A-Za-z0-9-]+\\.db")
          || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
          || Files.size(path) != sizeBytes
          || !fileHash(path).equals(sha256)) {
        throw ModelValues.invalid();
      }
    } catch (IOException bad) {
      throw unavailable();
    }
    transaction(
        () -> {
          if (count(
                  "SELECT COUNT(*) FROM cleanup_managed_backups WHERE relative_path=?",
                  relativePath)
              != 1) {
            throw ModelValues.invalid();
          }
          execute(
              "UPDATE cleanup_managed_backups SET sha256=?,size_bytes=? WHERE relative_path=?",
              sha256,
              sizeBytes,
              relativePath);
          return null;
        });
  }

  public synchronized void writeManagedSnapshot(
      Path destination, CleanupClaim claim, LibraryOperationGate.MaintenanceLease lease) {
    maintenance(lease);
    verifyRestoreJournal();
    Path path = destination.toAbsolutePath().normalize();
    if (!path.getParent().equals(libraryDirectory)
        || !path.getFileName().toString().matches("\\.cleanup-backup-[A-Za-z0-9-]+\\.(partial|db)")
        || Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        || transactionOwner != null) {
      throw ModelValues.invalid();
    }
    transaction(
        () -> {
          var repository = new DocumentCleanupRepository(this);
          if (!repository.current(claim)) {
            throw ModelValues.invalid();
          }
          repository.validatePurge(
              claim, repository.plan(claim.cleanupId()).orElseThrow(ModelValues::invalid));
          repository.verifyPurged(claim.documentId(), claim.cleanupId());
          repository.verifyPurgedRows();
          return null;
        });
    try {
      rawExecute("VACUUM INTO ?", path.toString());
      force(path);
      force(libraryDirectory);
    } catch (IOException | SQLException error) {
      throw unavailable();
    }
  }

  private void maintenance(LibraryOperationGate.MaintenanceLease lease) {
    if (lease == null
        || !lease.belongsTo(operationGate)
        || !lease.isHeld()
        || !lease.isOwnerThread()) {
      throw ModelValues.invalid();
    }
  }

  private void verifyRestoreJournal() {
    var intents = journalIntents();
    var highWater = journalEntries(".cleanup-restore-high-water.jsonl");
    transaction(
        () -> {
          for (var entry : intents.values()) {
            if (!libraryIdentity.equals(entry.get("library_id"))
                || count(
                        """
            SELECT COUNT(*) FROM cleanup_plans WHERE cleanup_id=? AND document_id=? AND manifest_sha256=? AND source_sha256=?
            """,
                        entry.get("cleanup_id"),
                        entry.get("document_id"),
                        entry.get("plan_sha256"),
                        entry.get("source_sha256"))
                    != 1) {
              throw new IllegalStateException("Cleanup restore barrier mismatch");
            }
          }
          for (var entry : highWater.entrySet()) {
            if (!entry.getValue().equals(intents.get(entry.getKey()))) {
              throw new IllegalStateException("Cleanup high water differs from intent");
            }
            new DocumentCleanupRepository(this)
                .verifyPurged(entry.getValue().get("document_id"), entry.getKey());
          }
          for (var table : CleanupPayloadTables.current(this)) {
            for (var row :
                rows(
                    "SELECT DISTINCT "
                        + table.document("p")
                        + " AS document_id FROM "
                        + table.name()
                        + " p WHERE payload_purged=1")) {
              var plans =
                  rows(
                      "SELECT cleanup_id FROM cleanup_plans WHERE document_id=?",
                      row.get("document_id"));
              if (plans.size() != 1
                  || !intents.containsKey(plans.getFirst().get("cleanup_id"))
                  || !highWater.containsKey(plans.getFirst().get("cleanup_id"))) {
                throw new IllegalStateException("Missing cleanup restore barrier");
              }
            }
          }
          for (var row : rows("SELECT id FROM document_cleanups WHERE state='completed'")) {
            if (!intents.containsKey(row.get("id")) || !highWater.containsKey(row.get("id"))) {
              throw new IllegalStateException("Missing completed cleanup barrier");
            }
          }
          return null;
        });
  }

  private void requireJournalIntent(CleanupPlan plan) {
    var entry = journalIntents().get(plan.cleanupId());
    if (entry == null
        || !plan.documentId().equals(entry.get("document_id"))
        || !plan.manifestSha256().equals(entry.get("plan_sha256"))
        || !plan.sourceSha256().equals(entry.get("source_sha256"))
        || !libraryIdentity.equals(entry.get("library_id"))) {
      throw ModelValues.invalid();
    }
  }

  private Map<String, Map<String, String>> journalIntents() {
    return journalEntries(".cleanup-restore-journal.jsonl");
  }

  private void recordHighWater(CleanupPlan plan) {
    var entry = journalIntents().get(plan.cleanupId());
    if (entry == null) {
      throw ModelValues.invalid();
    }
    var existing = journalEntries(".cleanup-restore-high-water.jsonl");
    if (existing.containsKey(plan.cleanupId())) {
      if (!existing.get(plan.cleanupId()).equals(entry)) {
        throw ModelValues.invalid();
      }
      return;
    }
    Path path = libraryDirectory.resolve(".cleanup-restore-high-water.jsonl");
    try {
      byte[] bytes =
          (JsonMapper.builder().build().writeValueAsString(entry) + "\n")
              .getBytes(StandardCharsets.UTF_8);
      if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
          && Files.size(path) + bytes.length > 16 * 1024 * 1024) {
        throw new IOException();
      }
      try (var channel =
          FileChannel.open(
              path,
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
              StandardOpenOption.APPEND,
              LinkOption.NOFOLLOW_LINKS)) {
        var buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
        channel.force(true);
      }
      force(libraryDirectory);
    } catch (IOException | RuntimeException failure) {
      throw unavailable();
    }
  }

  private Map<String, Map<String, String>> journalEntries(String filename) {
    Path path = libraryDirectory.resolve(filename);
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      return Map.of();
    }
    try {
      if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
          || Files.size(path) > 16 * 1024 * 1024) {
        throw new IOException();
      }
      var mapper =
          JsonMapper.builder()
              .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
              .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .build();
      var result = new LinkedHashMap<String, Map<String, String>>();
      for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
        if (line.isBlank() || line.length() > 4096) {
          throw new IOException();
        }
        var node = mapper.readTree(line);
        var fields = new LinkedHashMap<String, String>();
        for (String key :
            List.of("library_id", "cleanup_id", "document_id", "plan_sha256", "source_sha256")) {
          if (!node.path(key).isTextual()) {
            throw new IOException();
          }
          fields.put(key, node.path(key).asText());
        }
        if (!node.isObject() || node.size() != 5) {
          throw new IOException();
        }
        var prior = result.putIfAbsent(fields.get("cleanup_id"), Map.copyOf(fields));
        if (prior != null && !prior.equals(fields)) {
          throw new IOException();
        }
      }
      return Map.copyOf(result);
    } catch (IOException | RuntimeException error) {
      throw new IllegalStateException("Invalid cleanup restore barrier");
    }
  }

  private static Path completedBackup(Path partial) {
    return partial.resolveSibling(partial.getFileName().toString().replace(".partial", ".db"));
  }

  private static String fileHash(Path path) throws IOException {
    try (var input = Files.newInputStream(path)) {
      java.security.MessageDigest digest;
      try {
        digest = java.security.MessageDigest.getInstance("SHA-256");
      } catch (java.security.NoSuchAlgorithmException impossible) {
        throw new IllegalStateException(impossible);
      }
      byte[] bytes = new byte[65536];
      int count;
      while ((count = input.read(bytes)) != -1) {
        digest.update(bytes, 0, count);
      }
      return java.util.HexFormat.of().formatHex(digest.digest());
    }
  }

  private static void force(Path path) throws IOException {
    try (var channel = FileChannel.open(path, StandardOpenOption.READ)) {
      channel.force(true);
    }
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
