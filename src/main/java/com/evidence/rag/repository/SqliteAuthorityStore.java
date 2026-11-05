package com.evidence.rag.repository;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.CleanupClaim;
import com.evidence.rag.model.domain.CleanupPlan;
import com.evidence.rag.model.domain.LibraryOperationGate;
import com.evidence.rag.model.domain.ManagedBackupFile;
import com.evidence.rag.model.domain.ManagedBackupInventory;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.TaskWakeup;
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
import java.util.function.Consumer;
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
  private static final Set<String> QUEUE_TABLES =
      Set.of("ingestion_jobs", "indexing_jobs", "import_index_requests");
  private boolean queueChanged;
  private volatile long workspaceOriginalBytes = 10L * 1024 * 1024 * 1024;
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
  private String wikiPurgeWorkspace;
  private String wikiPurgePage;
  private static volatile Consumer<Path> freshLibrarySeeder;

  /**
   * Test suites install a seeder that copies an already migrated current-version library to the new
   * database path, so every fresh test library does not rerun all migrations. Production never
   * installs one; a seeded library is opened through the normal existing-library verification.
   */
  public static void seedFreshLibrariesWith(Consumer<Path> seeder) {
    freshLibrarySeeder = seeder;
  }

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
      Consumer<Path> seeder = freshLibrarySeeder;
      if (seeder != null && !Files.exists(database, LinkOption.NOFOLLOW_LINKS)) {
        seeder.accept(database);
      }
      boolean exists = Files.exists(database, LinkOption.NOFOLLOW_LINKS);
      if (exists && !Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalStateException("Unsafe Java database path");
      }
      connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
      // Row changes to the task queues (including trigger writes) wake idle consumers on commit.
      connection
          .unwrap(org.sqlite.SQLiteConnection.class)
          .addUpdateListener(
              (type, databaseName, table, rowId) -> {
                if (QUEUE_TABLES.contains(table)) {
                  queueChanged = true;
                }
              });
      Function.create(
          connection,
          "java_wiki_page_purge_authorized",
          new TransactionGrant(2) {
            @Override
            boolean granted() throws SQLException {
              return wikiPurgeWorkspace != null
                  && wikiPurgeWorkspace.equals(value_text(0))
                  && wikiPurgePage.equals(value_text(1));
            }
          });
      Function.create(
          connection,
          "java_cleanup_authorized",
          new TransactionGrant(3) {
            @Override
            boolean granted() throws SQLException {
              return purgeDocument != null
                  && purgeDocument.equals(value_text(0))
                  && purgeRows.contains(value_text(1) + "\n" + value_text(2));
            }
          });
      Function.create(
          connection,
          "java_replacement_authorized",
          new TransactionGrant(1) {
            @Override
            boolean granted() throws SQLException {
              return replacementDocument != null && replacementDocument.equals(value_text(0));
            }
          });
      Function.create(
          connection,
          "java_model_rebuild_authorized",
          new TransactionGrant(1) {
            @Override
            boolean granted() throws SQLException {
              return modelRebuildId != null && modelRebuildDocuments.contains(value_text(0));
            }
          });
      Function.create(
          connection,
          "java_model_rebuild_batch_authorized",
          new TransactionGrant(1) {
            @Override
            boolean granted() throws SQLException {
              return modelRebuildId != null && modelRebuildId.equals(value_text(0));
            }
          });
      var schema = new AuthoritySchema(this);
      if (exists) {
        transaction(
            () -> {
              verifyStoredFormat(schema, count("PRAGMA user_version"));
              return null;
            });
      }
      rawExecute("PRAGMA foreign_keys=ON");
      rawExecute("PRAGMA busy_timeout=5000");
      if (!exists) {
        schema.initialize();
      }
      // Each step re-reads the version, snapshots an existing library, then migrates one version.
      upgrade(schema, exists, 1, false, schema::migrateVersionTwo);
      upgrade(schema, exists, 2, false, schema::migrateVersionThree);
      upgrade(schema, exists, 3, false, schema::migrateVersionFour);
      upgrade(schema, exists, 4, false, schema::migrateVersionFive);
      upgrade(schema, exists, 5, false, schema::migrateVersionSix);
      upgrade(schema, exists, 6, false, schema::migrateVersionSeven);
      upgrade(schema, exists, 7, false, schema::migrateVersionEight);
      upgrade(schema, exists, 8, false, schema::migrateVersionNine);
      upgrade(schema, exists, 9, false, schema::migrateVersionTen);
      upgrade(schema, exists, 10, false, schema::migrateVersionEleven);
      upgrade(schema, exists, 11, false, schema::migrateVersionTwelve);
      upgrade(schema, exists, 12, false, schema::migrateVersionThirteen);
      upgrade(schema, exists, 13, false, schema::migrateVersionFourteen);
      upgrade(schema, exists, 14, false, schema::migrateVersionFifteen);
      upgrade(schema, exists, 15, false, schema::migrateVersionSixteen);
      upgrade(schema, exists, 16, false, schema::migrateVersionSeventeen);
      upgrade(schema, exists, 17, false, schema::migrateVersionEighteen);
      upgrade(schema, exists, 18, false, schema::migrateVersionNineteen);
      upgrade(schema, exists, 19, false, schema::migrateVersionTwenty);
      upgrade(schema, exists, 20, false, schema::migrateVersionTwentyOne);
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
        withTableRebuildPragmas(() -> new DocumentCleanupSchema(this).migrate(backupsKnown));
      }
      upgrade(schema, exists, 22, true, schema::migrateVersionTwentyThree);
      upgrade(schema, exists, 23, true, schema::migrateVersionTwentyFour);
      upgrade(schema, exists, 24, false, schema::migrateVersionTwentyFive);
      upgrade(schema, exists, 25, true, () -> new DocumentReplacementSchema(this).migrate());
      upgrade(schema, exists, 26, true, () -> new ModelRebuildSchema(this).migrate());
      upgrade(schema, exists, 27, true, () -> new VideoTextEvidenceSchema(this).migrate());
      upgrade(schema, exists, 28, false, () -> new KnowledgeAnswerSchema(this).migrate());
      upgrade(schema, exists, 29, true, () -> new SharedWorkspaceSchema(this).migrate());
      upgrade(schema, exists, 30, false, () -> new WikiWorkspaceSchema(this).migrate());
      upgrade(schema, exists, 31, false, () -> new WikiDraftSchema(this).migrate());
      upgrade(schema, exists, 32, false, () -> new WikiPageLifecycleSchema(this).migrate());
      upgrade(schema, exists, 33, false, () -> new ImportIndexSchema(this).migrate());
      upgrade(schema, exists, 34, false, () -> new WikiPagePurgeSchema(this).migrate());
      upgrade(schema, exists, 35, false, () -> new ConversationSchema(this).migrate());
      upgrade(schema, exists, 36, true, () -> new IndexRestartSchema(this).migrate());
      upgrade(schema, exists, 37, false, () -> new DocumentBatchSchema(this).migrate());
      upgrade(schema, exists, 38, false, () -> new BatchRequeueSchema(this).migrate());
      upgrade(schema, exists, 39, false, () -> new BatchThroughputSchema(this).migrate());
      upgrade(schema, exists, 40, false, () -> new BatchHistorySchema(this).migrate());
      upgrade(schema, exists, 41, false, () -> new BatchStreamSchema(this).migrate());
      upgrade(schema, exists, 42, false, () -> new BatchPlacementSchema(this).migrate());
      transaction(
          () -> {
            new BatchPlacementSchema(this).verify();
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
      queueChanged = false;
      transactionOwner = Thread.currentThread();
      T result = work.get();
      rawExecute("COMMIT");
      if (queueChanged) {
        TaskWakeup.global().signal();
      }
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
      queueChanged = false;
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

  <T> T wikiPagePurgeScope(String workspaceId, String pageId, Supplier<T> work) {
    requireTransaction();
    if (wikiPurgePage != null) {
      throw ModelValues.invalid();
    }
    wikiPurgeWorkspace = workspaceId;
    wikiPurgePage = pageId;
    try {
      return work.get();
    } finally {
      wikiPurgeWorkspace = null;
      wikiPurgePage = null;
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

  /** Reuse one prepared statement, flushing bounded chunks within the caller's transaction. */
  void executeBatch(String sql, List<Object[]> rows) {
    requireTransaction();
    try (var statement = connection.prepareStatement(sql)) {
      int pending = 0;
      for (Object[] row : rows) {
        statement.clearParameters();
        for (int index = 0; index < row.length; index++) statement.setObject(index + 1, row[index]);
        statement.addBatch();
        if (++pending == 256) {
          statement.executeBatch();
          statement.clearBatch();
          pending = 0;
        }
      }
      if (pending > 0) statement.executeBatch();
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

  /**
   * Total original bytes one workspace may hold across documents, revisions, sounds and videos
   * (rag.batch.workspace-original-bytes, default 10 GiB).
   */
  public void configureWorkspaceOriginalBytes(long value) {
    if (value < 1024L * 1024 || value > 1024L * 1024 * 1024 * 1024)
      throw new IllegalArgumentException("Invalid workspace original bytes");
    workspaceOriginalBytes = value;
  }

  public long workspaceOriginalBytes() {
    return workspaceOriginalBytes;
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
            new BatchPlacementSchema(this).verify();
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

  /** Verifies an existing library at its stored version before any upgrade step runs. */
  private void verifyStoredFormat(AuthoritySchema schema, long version) {
    switch ((int) version) {
      case 43 -> new BatchPlacementSchema(this).verify();
      case 42 -> new BatchStreamSchema(this).verify();
      case 41 -> new BatchHistorySchema(this).verify();
      case 40 -> new BatchThroughputSchema(this).verify();
      case 39 -> new BatchRequeueSchema(this).verify();
      case 38 -> new DocumentBatchSchema(this).verify();
      case 37 -> new IndexRestartSchema(this).verify();
      case 36 -> new ConversationSchema(this).verify();
      case 35 -> new WikiPagePurgeSchema(this).verify();
      case 34 -> new ImportIndexSchema(this).verify();
      case 33 -> new WikiPageLifecycleSchema(this).verify();
      case 32 -> new WikiDraftSchema(this).verify();
      case 31 -> new WikiWorkspaceSchema(this).verify();
      case 30 -> new SharedWorkspaceSchema(this).verify();
      case 29 -> new KnowledgeAnswerSchema(this).verify();
      case 28 -> new VideoTextEvidenceSchema(this).verify();
      case 27 -> new ModelRebuildSchema(this).verify();
      case 26 -> new DocumentReplacementSchema(this).verify();
      case 25 -> schema.verifyVersionTwentyFive();
      case 24 -> schema.verifyVersionTwentyFour();
      case 23 -> schema.verifyVersionTwentyThree();
      case 22 -> new DocumentCleanupSchema(this).verify();
      default -> schema.verifyFormat();
    }
  }

  /**
   * Upgrades one version when the library is at {@code from}: an existing library is snapshotted
   * first, and steps that rebuild tables run with the table-rebuild pragmas.
   */
  private void upgrade(
      AuthoritySchema schema, boolean exists, int from, boolean rebuildsTables, Runnable migrate)
      throws IOException, SQLException {
    if (transaction(() -> count("PRAGMA user_version")) != from) {
      return;
    }
    if (exists) {
      schema.backupVersion(libraryDirectory, from, from + 1);
    }
    if (rebuildsTables) {
      withTableRebuildPragmas(migrate);
    } else {
      migrate.run();
    }
  }

  private void withTableRebuildPragmas(Runnable migrate) throws SQLException {
    rawExecute("PRAGMA foreign_keys=OFF");
    rawExecute("PRAGMA legacy_alter_table=ON");
    try {
      migrate.run();
    } finally {
      rawExecute("PRAGMA legacy_alter_table=OFF");
      rawExecute("PRAGMA foreign_keys=ON");
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

  /**
   * SQL predicate that is true only on the thread inside this store's current transaction, with
   * exactly {@code arity} arguments, and when {@link #granted()} accepts them.
   */
  private abstract class TransactionGrant extends Function {
    private final int arity;

    TransactionGrant(int arity) {
      this.arity = arity;
    }

    abstract boolean granted() throws SQLException;

    @Override
    protected final void xFunc() {
      try {
        result(args() == arity && transactionOwner == Thread.currentThread() && granted() ? 1 : 0);
      } catch (SQLException failure) {
        throw unavailable();
      }
    }
  }
}
