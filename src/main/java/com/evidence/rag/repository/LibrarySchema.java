package com.evidence.rag.repository;

import com.evidence.rag.model.domain.ModelValues;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Creates a fresh library directly at the current format from {@code db/schema-v43.sql}. That file
 * holds every table, index and trigger exactly as the retired v1-to-v43 migration chain left them,
 * so a fresh library is indistinguishable from a migrated one. Older libraries are refused, not
 * upgraded: they must first be opened once by a release that still carried the chain.
 */
final class LibrarySchema {
  static final int VERSION = BatchPlacementSchema.VERSION;
  static final int APPLICATION_ID = 1163280711;
  static final String FORMAT = "evidence-rag-java-management-v1";

  /** The last commit whose store still upgraded libraries older than v43 step by step. */
  static final String LAST_UPGRADING_RELEASE = "ce6912a";

  private static final String RESOURCE = "/db/schema-v43.sql";
  private static volatile List<String> statements;

  private final SqliteAuthorityStore store;

  LibrarySchema(SqliteAuthorityStore store) {
    this.store = store;
  }

  /**
   * Creates every schema object, the seed rows and the schema-object inventory in one transaction,
   * then stamps the version. {@code backupsKnown} is false when the directory already holds
   * snapshot files this library cannot claim as its own.
   */
  void create(boolean backupsKnown) {
    List<String> ddl = statements();
    store.transaction(
        () -> {
          store.execute("PRAGMA application_id=" + APPLICATION_ID);
          for (String sql : ddl) {
            store.execute(sql);
          }
          store.execute("INSERT INTO format_info VALUES(?,?)", FORMAT, VERSION);
          store.execute(
              "INSERT INTO text_runtime_selection(id,initialized,updated_at) VALUES(1,0,'1970-01-01T00:00:00Z')");
          store.execute(
              "INSERT INTO cleanup_library VALUES(1,?,?)",
              UUID.randomUUID().toString(),
              backupsKnown ? 1 : 0);
          for (var row :
              store.rows(
                  "SELECT type,name,sql FROM sqlite_master WHERE type IN ('table','index','trigger') AND sql IS NOT NULL ORDER BY rowid")) {
            store.execute(
                "INSERT INTO cleanup_schema_objects(name,object_type,sql_sha256) VALUES(?,?,?)",
                row.get("name"),
                row.get("type"),
                ModelValues.sha256(
                    AuthorityRows.text(row, "sql").getBytes(StandardCharsets.UTF_8)));
          }
          store.execute("PRAGMA user_version=" + VERSION);
          return null;
        });
  }

  /** The schema statements in creation order, each exactly as sqlite_master stores it. */
  static List<String> statements() {
    var cached = statements;
    if (cached == null) {
      try (InputStream input = LibrarySchema.class.getResourceAsStream(RESOURCE)) {
        if (input == null) {
          throw new IllegalStateException("Missing library schema");
        }
        cached = parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
      } catch (IOException error) {
        throw new UncheckedIOException(error);
      }
      statements = cached;
    }
    return cached;
  }

  /** Statements end with a line holding only {@code ;}; leading {@code --} lines are comments. */
  private static List<String> parse(String text) {
    var result = new ArrayList<String>();
    for (String chunk : text.replace("\r\n", "\n").split("\n;\n", -1)) {
      var lines = new ArrayList<>(List.of(chunk.strip().split("\n", -1)));
      while (!lines.isEmpty() && lines.getFirst().startsWith("--")) {
        lines.removeFirst();
      }
      String sql = String.join("\n", lines).strip();
      if (!sql.isEmpty()) {
        result.add(sql);
      }
    }
    return List.copyOf(result);
  }
}
