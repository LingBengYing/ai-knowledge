package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.ModelValues;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A fresh library is created directly at v43 and must equal what the retired v1-to-v43 migration
 * chain produced ({@code db/schema-v43-expected.txt} was captured from that chain). Existing
 * libraries are only opened at exactly v43 and after full verification; anything else is refused
 * without touching the file.
 */
class LibrarySchemaTest {
  private static final String UUID_PATTERN =
      "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
  private static final Set<String> SEEDED =
      Set.of("cleanup_library", "cleanup_schema_objects", "format_info", "text_runtime_selection");

  @TempDir Path directory;

  @Test
  void freshLibraryEqualsTheSchemaAndSeedRowsOfTheMigrationChain() throws Exception {
    try (var ignored = new SqliteAuthorityStore(directory)) {}
    Path database = database();
    List<String> expected = expectation();
    List<String> actual = new ArrayList<>();
    actual.add("pragma user_version " + text(database, "PRAGMA user_version"));
    actual.add("pragma application_id " + text(database, "PRAGMA application_id"));
    var objects =
        rows(database, "SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name");
    for (var row : objects) {
      actual.add(
          "object "
              + row.get(0)
              + " "
              + row.get(1)
              + " "
              + row.get(2)
              + " "
              + (row.get(3) == null ? "-" : sha256(row.get(3))));
    }
    for (String table : List.of("cleanup_library", "format_info", "text_runtime_selection")) {
      for (var row : rows(database, "SELECT * FROM " + table)) {
        if (table.equals("cleanup_library")) {
          assertTrue(row.get(1).matches(UUID_PATTERN), row.get(1));
          row.set(1, "<uuid>");
        }
        actual.add(
            "row "
                + table
                + " "
                + row.stream().map(v -> v == null ? "NULL" : v).collect(Collectors.joining("|")));
      }
    }
    assertEquals(expected, actual);

    var inventory = new TreeSet<String>();
    for (var row :
        rows(database, "SELECT object_type,name,sql_sha256 FROM cleanup_schema_objects")) {
      inventory.add(String.join(" ", row));
    }
    var stored = new TreeSet<String>();
    for (var row : objects) {
      if (row.get(3) != null) stored.add(row.get(0) + " " + row.get(1) + " " + sha256(row.get(3)));
    }
    assertEquals(stored, inventory);
    for (var row :
        rows(database, "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")) {
      if (!SEEDED.contains(row.getFirst())) {
        assertEquals("0", text(database, "SELECT COUNT(*) FROM \"" + row.getFirst() + "\""));
      }
    }
  }

  @Test
  void everyFreshLibraryGetsItsOwnIdentityAndReopensThroughVerification() throws Exception {
    String first;
    try (var store = new SqliteAuthorityStore(directory.resolve("first"))) {
      first = store.libraryIdentity();
    }
    try (var store = new SqliteAuthorityStore(directory.resolve("second"))) {
      assertNotEquals(first, store.libraryIdentity());
    }
    try (var store = new SqliteAuthorityStore(directory.resolve("first"))) {
      assertEquals(first, store.libraryIdentity());
      assertTrue(store.managedBackups().known());
    }
  }

  @Test
  void schemaResourceHoldsEveryObjectOnceInCreationOrder() {
    var statements = LibrarySchema.statements();
    assertEquals(
        expectation().stream()
            .filter(line -> line.startsWith("object ") && !line.endsWith(" -"))
            .count(),
        statements.size());
    boolean pastTables = false;
    for (String sql : statements) {
      assertFalse(sql.endsWith(";"), sql);
      boolean table = sql.startsWith("CREATE TABLE ");
      assertFalse(pastTables && table, "tables precede indexes and triggers: " + sql);
      pastTables |= !table;
    }
    assertEquals(statements.size(), Set.copyOf(statements).size());
    assertTrue(statements.getFirst().startsWith("CREATE TABLE format_info("));
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 21, 22, 42})
  void olderLibraryIsRefusedWithAnUpgradeHintAndLeftUntouched(int version) throws Exception {
    try (var ignored = new SqliteAuthorityStore(directory)) {}
    LibraryFileFixture.execute(database(), "PRAGMA user_version=" + version);
    byte[] before = Files.readAllBytes(database());
    var listing = listing();

    var failure =
        assertThrows(
            SqliteAuthorityStore.OutdatedLibraryException.class,
            () -> new SqliteAuthorityStore(directory));

    assertEquals(
        "Java library schema v"
            + version
            + " is older than v43; upgrade it first by opening it once with release ce6912a"
            + " (the last with the step-by-step upgrade chain)",
        failure.getMessage());
    assertArrayEquals(before, Files.readAllBytes(database()));
    assertEquals(listing, listing());
  }

  @Test
  void minimalHistoricalVersionOneFileIsRefusedUntouched() throws Exception {
    Files.createDirectories(directory);
    LibraryFileFixture.execute(
        database(), "CREATE TABLE format_info(format TEXT PRIMARY KEY,version INTEGER NOT NULL)");
    LibraryFileFixture.execute(database(), "PRAGMA application_id=1163280711");
    LibraryFileFixture.execute(database(), "PRAGMA user_version=1");
    byte[] before = Files.readAllBytes(database());

    var failure =
        assertThrows(IllegalStateException.class, () -> new SqliteAuthorityStore(directory));

    assertInstanceOf(SqliteAuthorityStore.OutdatedLibraryException.class, failure);
    assertTrue(failure.getMessage().startsWith("Java library schema v1 is older than v43"));
    assertArrayEquals(before, Files.readAllBytes(database()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"newer", "foreign", "unversioned"})
  void newerOrForeignDatabaseKeepsTheGenericRefusal(String kind) throws Exception {
    Files.createDirectories(directory);
    switch (kind) {
      case "newer" -> {
        try (var ignored = new SqliteAuthorityStore(directory)) {}
        LibraryFileFixture.execute(database(), "PRAGMA user_version=44");
      }
      case "foreign" -> {
        LibraryFileFixture.execute(database(), "CREATE TABLE other(id INTEGER)");
        LibraryFileFixture.execute(database(), "PRAGMA user_version=12");
      }
      case "unversioned" -> LibraryFileFixture.execute(database(), "CREATE TABLE other(id)");
      default -> throw new AssertionError(kind);
    }
    byte[] before = Files.readAllBytes(database());

    var failure =
        assertThrows(IllegalStateException.class, () -> new SqliteAuthorityStore(directory));

    assertFalse(failure instanceof SqliteAuthorityStore.OutdatedLibraryException);
    assertEquals("Cannot open isolated Java library", failure.getMessage());
    assertArrayEquals(before, Files.readAllBytes(database()));
  }

  /** Guards whose loss each former migration test checked; any change fails verification. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "DROP TRIGGER ingestion_transition",
        "DROP TRIGGER indexing_rebuild_identity",
        "DROP TRIGGER image_vector_publications_no_update",
        "DROP TRIGGER audio_vector_entries_sealed",
        "DROP TRIGGER query_trace_preparations_no_update",
        "DROP TRIGGER sound_spans_sealed",
        "DROP TRIGGER video_av_query_parent_complete",
        "DROP TRIGGER knowledge_answer_citations_sealed",
        "DROP TRIGGER wiki_page_revisions_no_update",
        "DROP INDEX documents_workspace",
        "CREATE TABLE unexpected(id INTEGER)",
        "ALTER TABLE document_import_batch_placement ADD COLUMN extra TEXT",
        "PRAGMA application_id=1"
      })
  void tamperedCurrentLibraryIsRefusedByVerificationAndLeftUntouched(String tampering)
      throws Exception {
    try (var ignored = new SqliteAuthorityStore(directory)) {}
    LibraryFileFixture.execute(database(), tampering);
    byte[] before = Files.readAllBytes(database());

    var failure =
        assertThrows(IllegalStateException.class, () -> new SqliteAuthorityStore(directory));

    assertFalse(failure instanceof SqliteAuthorityStore.OutdatedLibraryException);
    assertArrayEquals(before, Files.readAllBytes(database()));
    assertEquals("43", text(database(), "PRAGMA user_version"));
  }

  @Test
  void rewrittenGuardWithTheSameNameIsRefused() throws Exception {
    try (var ignored = new SqliteAuthorityStore(directory)) {}
    LibraryFileFixture.execute(database(), "DROP TRIGGER cleanup_library_no_update");
    LibraryFileFixture.execute(
        database(),
        "CREATE TRIGGER cleanup_library_no_update BEFORE UPDATE ON cleanup_library WHEN 0 BEGIN SELECT RAISE(ABORT,'immutable cleanup audit'); END");

    assertThrows(IllegalStateException.class, () -> new SqliteAuthorityStore(directory));
  }

  @Test
  void snapshotFilesItCannotClaimLeaveTheManagedBackupInventoryUnknown() throws Exception {
    Path preexisting = directory.resolve("preexisting");
    Files.createDirectories(preexisting);
    Files.writeString(preexisting.resolve("java-library.v21-before-v22-stray.db"), "stray");
    try (var store = new SqliteAuthorityStore(preexisting)) {
      assertEquals(
          0L, store.transaction(() -> store.count("SELECT backups_known FROM cleanup_library")));
      assertFalse(store.managedBackups().known());
    }

    Path later = directory.resolve("later");
    try (var store = new SqliteAuthorityStore(later)) {
      assertEquals(
          1L, store.transaction(() -> store.count("SELECT backups_known FROM cleanup_library")));
      assertTrue(store.managedBackups().known());
      assertTrue(store.managedBackups().files().isEmpty());
      Files.writeString(later.resolve("java-library.v42-before-v43-stray.db"), "stray");
      assertFalse(store.managedBackups().known());
    }
  }

  private Path database() {
    return directory.resolve("java-library.db");
  }

  private List<String> listing() throws IOException {
    try (var files = Files.list(directory)) {
      return files.map(path -> path.getFileName().toString()).sorted().toList();
    }
  }

  private static List<String> expectation() {
    try (var input = LibrarySchemaTest.class.getResourceAsStream("/db/schema-v43-expected.txt")) {
      return new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8)
          .lines()
          .filter(line -> !line.isBlank() && !line.startsWith("#"))
          .toList();
    } catch (IOException error) {
      throw new IllegalStateException(error);
    }
  }

  private static String sha256(String sql) {
    return ModelValues.sha256(sql.getBytes(StandardCharsets.UTF_8));
  }

  private static String text(Path database, String sql) throws SQLException {
    return rows(database, sql).getFirst().getFirst();
  }

  private static List<List<String>> rows(Path database, String sql) throws SQLException {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var statement = connection.createStatement();
        var result = statement.executeQuery(sql)) {
      var rows = new ArrayList<List<String>>();
      int columns = result.getMetaData().getColumnCount();
      while (result.next()) {
        var row = new ArrayList<String>();
        for (int index = 1; index <= columns; index++) {
          row.add(result.getString(index));
        }
        rows.add(row);
      }
      return rows;
    }
  }
}
