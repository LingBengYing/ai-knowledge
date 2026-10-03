package com.evidence.rag.repository;

import com.evidence.rag.model.domain.ModelValues;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** v22-only migration. Earlier migration definitions and their historical snapshots stay intact. */
final class DocumentCleanupSchema {
  private final SqliteAuthorityStore store;

  DocumentCleanupSchema(SqliteAuthorityStore store) {
    this.store = store;
  }

  void migrate(boolean backupInventoryKnown) {
    store.transaction(
        () -> {
          createLedger(backupInventoryKnown);
          for (var table : CleanupPayloadTables.TABLES) {
            rebuild(table);
          }
          completionGuard();
          store.execute("UPDATE format_info SET version=22");
          store.execute("PRAGMA user_version=22");
          if (store.count("SELECT COUNT(*) FROM pragma_foreign_key_check") != 0) {
            throw new IllegalStateException("Cleanup migration changed authority references");
          }
          for (var row : objects()) {
            store.execute(
                "INSERT INTO cleanup_schema_objects(name,object_type,sql_sha256) VALUES(?,?,?)",
                row.get("name"),
                row.get("type"),
                hash((String) row.get("sql")));
          }
          return null;
        });
  }

  private void createLedger(boolean backupsKnown) {
    store.execute(
        """
        CREATE TABLE cleanup_library(id INTEGER PRIMARY KEY CHECK(id=1),library_id TEXT NOT NULL UNIQUE,
          backups_known INTEGER NOT NULL CHECK(backups_known IN (0,1)))
        """);
    store.execute(
        "INSERT INTO cleanup_library VALUES(1,?,?)",
        UUID.randomUUID().toString(),
        backupsKnown ? 1 : 0);
    store.execute(
        """
        CREATE TABLE document_cleanups(id TEXT PRIMARY KEY,document_id TEXT NOT NULL UNIQUE REFERENCES documents(id) ON DELETE RESTRICT,
          workspace_id TEXT NOT NULL,source_sha256 TEXT NOT NULL,requested_by TEXT NOT NULL,
          requested_at TEXT NOT NULL,updated_at TEXT NOT NULL,completed_at TEXT,
          state TEXT NOT NULL CHECK(state IN ('pending','running','blocked','failed','completed')),
          claim_sha256 TEXT,error_code TEXT,
          CHECK((state='completed')=(completed_at IS NOT NULL)),CHECK(state!='running' OR claim_sha256 IS NOT NULL))
        """);
    store.execute(
        """
        CREATE TABLE cleanup_resources(cleanup_id TEXT NOT NULL REFERENCES document_cleanups(id) ON DELETE RESTRICT,
          kind TEXT NOT NULL CHECK(kind IN ('database_payload','database_file','managed_backups','managed_temporaries','remote_inventory','remote_logical_rows','remote_write_terminal','remote_physical_storage','restore_barrier')),
          status TEXT NOT NULL CHECK(status IN ('pending','running','completed','not_applicable','blocked','failed')),
          PRIMARY KEY(cleanup_id,kind))
        """);
    store.execute(
        """
        CREATE TABLE cleanup_plans(cleanup_id TEXT PRIMARY KEY REFERENCES document_cleanups(id) ON DELETE RESTRICT,
          document_id TEXT NOT NULL,workspace_id TEXT NOT NULL,source_sha256 TEXT NOT NULL,
          manifest_sha256 TEXT NOT NULL,payload_count INTEGER NOT NULL CHECK(payload_count>=0))
        """);
    store.execute(
        """
        CREATE TABLE cleanup_payloads(cleanup_id TEXT NOT NULL REFERENCES cleanup_plans(cleanup_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
          table_name TEXT NOT NULL,row_key TEXT NOT NULL,body_column TEXT NOT NULL,
          size_bytes INTEGER NOT NULL CHECK(size_bytes>=0),body_sha256 TEXT NOT NULL CHECK(length(body_sha256)=64),
          PRIMARY KEY(cleanup_id,table_name,row_key,body_column))
        """);
    store.execute(
        """
        CREATE TABLE cleanup_document_provenance(document_id TEXT PRIMARY KEY REFERENCES documents(id) ON DELETE RESTRICT,
          inventory_known INTEGER NOT NULL CHECK(inventory_known IN (0,1)))
        """);
    store.execute("INSERT INTO cleanup_document_provenance SELECT id,0 FROM documents");
    store.execute(
        """
        CREATE TRIGGER cleanup_new_document AFTER INSERT ON documents
        BEGIN INSERT INTO cleanup_document_provenance(document_id,inventory_known) VALUES(NEW.id,1); END
        """);
    store.execute(
        """
        CREATE TABLE cleanup_projection_attempts(document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE RESTRICT,
          workspace_id TEXT NOT NULL,source_revision_id TEXT NOT NULL,source_sha256 TEXT NOT NULL,
          generation_id TEXT NOT NULL,route TEXT NOT NULL,endpoint TEXT NOT NULL,database_name TEXT NOT NULL,
          collection_name TEXT NOT NULL,embedding_identity TEXT NOT NULL,dimensions INTEGER NOT NULL,
          projection_identity TEXT NOT NULL,write_issued INTEGER NOT NULL CHECK(write_issued IN (0,1)),
          PRIMARY KEY(generation_id,route))
        """);
    store.execute(
        """
        CREATE TABLE cleanup_managed_backups(relative_path TEXT PRIMARY KEY,sha256 TEXT NOT NULL,
          size_bytes INTEGER NOT NULL CHECK(size_bytes>=0))
        """);
    store.execute(
        "CREATE TABLE cleanup_schema_objects(name TEXT PRIMARY KEY,object_type TEXT NOT NULL,sql_sha256 TEXT NOT NULL)");
    store.execute(
        """
        CREATE TRIGGER cleanup_request_identity BEFORE INSERT ON document_cleanups
        WHEN NOT EXISTS(SELECT 1 FROM documents d JOIN document_tombstones t ON t.document_id=d.id
          WHERE d.id=NEW.document_id AND d.workspace_id=NEW.workspace_id AND d.source_sha256=NEW.source_sha256)
        BEGIN SELECT RAISE(ABORT,'cleanup requires removed source'); END
        """);
    store.execute(
        """
        CREATE TRIGGER cleanup_request_update BEFORE UPDATE ON document_cleanups
        WHEN NEW.id IS NOT OLD.id OR NEW.document_id IS NOT OLD.document_id OR NEW.workspace_id IS NOT OLD.workspace_id
          OR NEW.source_sha256 IS NOT OLD.source_sha256 OR NEW.requested_by IS NOT OLD.requested_by OR NEW.requested_at IS NOT OLD.requested_at
          OR OLD.state='completed' OR (NEW.state='completed' AND ((SELECT COUNT(*) FROM cleanup_resources WHERE cleanup_id=OLD.id)!=9
          OR EXISTS(SELECT 1 FROM cleanup_resources WHERE cleanup_id=OLD.id AND status NOT IN ('completed','not_applicable'))))
        BEGIN SELECT RAISE(ABORT,'immutable cleanup identity or incomplete result'); END
        """);
    store.execute(
        """
        CREATE TRIGGER cleanup_plan_identity BEFORE INSERT ON cleanup_plans
        WHEN NOT EXISTS(SELECT 1 FROM document_cleanups c WHERE c.id=NEW.cleanup_id AND c.document_id=NEW.document_id
          AND c.workspace_id=NEW.workspace_id AND c.source_sha256=NEW.source_sha256 AND c.state='running')
          OR NEW.payload_count!=(SELECT COUNT(*) FROM cleanup_payloads WHERE cleanup_id=NEW.cleanup_id)
        BEGIN SELECT RAISE(ABORT,'incomplete cleanup plan'); END
        """);
    store.execute(
        """
        CREATE TRIGGER cleanup_payload_sealed BEFORE INSERT ON cleanup_payloads
        WHEN EXISTS(SELECT 1 FROM cleanup_plans WHERE cleanup_id=NEW.cleanup_id)
        BEGIN SELECT RAISE(ABORT,'sealed cleanup body inventory'); END
        """);
    store.execute(
        """
        CREATE TRIGGER cleanup_projection_identity BEFORE INSERT ON cleanup_projection_attempts
        WHEN NOT EXISTS(SELECT 1 FROM documents d WHERE d.id=NEW.document_id AND d.workspace_id=NEW.workspace_id
          AND d.active_revision_id=NEW.source_revision_id AND d.source_sha256=NEW.source_sha256)
          OR EXISTS(SELECT 1 FROM document_tombstones WHERE document_id=NEW.document_id)
        BEGIN SELECT RAISE(ABORT,'invalid projection resource identity'); END
        """);
    store.execute(
        """
        CREATE TRIGGER cleanup_projection_update BEFORE UPDATE ON cleanup_projection_attempts
        WHEN NEW.document_id IS NOT OLD.document_id OR NEW.workspace_id IS NOT OLD.workspace_id
          OR NEW.source_revision_id IS NOT OLD.source_revision_id OR NEW.source_sha256 IS NOT OLD.source_sha256
          OR NEW.generation_id IS NOT OLD.generation_id OR NEW.route IS NOT OLD.route
          OR NEW.endpoint IS NOT OLD.endpoint OR NEW.database_name IS NOT OLD.database_name
          OR NEW.collection_name IS NOT OLD.collection_name OR NEW.embedding_identity IS NOT OLD.embedding_identity
          OR NEW.dimensions IS NOT OLD.dimensions OR NEW.projection_identity IS NOT OLD.projection_identity
          OR NEW.write_issued<OLD.write_issued
        BEGIN SELECT RAISE(ABORT,'immutable projection attempt'); END
        """);
    store.execute(
        """
        CREATE TRIGGER cleanup_resource_update BEFORE UPDATE ON cleanup_resources
        WHEN NEW.cleanup_id IS NOT OLD.cleanup_id OR NEW.kind IS NOT OLD.kind
          OR EXISTS(SELECT 1 FROM document_cleanups WHERE id=OLD.cleanup_id AND state='completed')
        BEGIN SELECT RAISE(ABORT,'immutable cleanup resource'); END
        """);
    store.execute(
        """
        CREATE TRIGGER cleanup_backup_update BEFORE UPDATE ON cleanup_managed_backups
        WHEN NEW.relative_path IS NOT OLD.relative_path
        BEGIN SELECT RAISE(ABORT,'immutable managed backup identity'); END
        """);
    var immutable =
        List.of(
            "cleanup_library",
            "cleanup_plans",
            "cleanup_payloads",
            "cleanup_document_provenance",
            "cleanup_schema_objects");
    for (String table : immutable) {
      for (String op : List.of("UPDATE", "DELETE")) {
        store.execute(
            "CREATE TRIGGER "
                + table
                + "_no_"
                + op.toLowerCase(java.util.Locale.ROOT)
                + " BEFORE "
                + op
                + " ON "
                + table
                + " BEGIN SELECT RAISE(ABORT,'immutable cleanup audit'); END");
      }
    }
    for (String table :
        List.of(
            "document_cleanups",
            "cleanup_resources",
            "cleanup_projection_attempts",
            "cleanup_managed_backups")) {
      store.execute(
          "CREATE TRIGGER "
              + table
              + "_no_delete BEFORE DELETE ON "
              + table
              + " BEGIN SELECT RAISE(ABORT,'immutable cleanup inventory'); END");
    }
    // Reject REPLACE independently of SQLite recursive-trigger settings.
    for (var entry :
        Map.of(
                "document_cleanups",
                "id=NEW.id OR document_id=NEW.document_id",
                "cleanup_plans",
                "cleanup_id=NEW.cleanup_id",
                "cleanup_payloads",
                "cleanup_id=NEW.cleanup_id AND table_name=NEW.table_name AND row_key=NEW.row_key AND body_column=NEW.body_column",
                "cleanup_document_provenance",
                "document_id=NEW.document_id",
                "cleanup_library",
                "id=NEW.id",
                "cleanup_schema_objects",
                "name=NEW.name",
                "cleanup_projection_attempts",
                "generation_id=NEW.generation_id AND route=NEW.route",
                "cleanup_resources",
                "cleanup_id=NEW.cleanup_id AND kind=NEW.kind",
                "cleanup_managed_backups",
                "relative_path=NEW.relative_path")
            .entrySet()) {
      store.execute(
          "CREATE TRIGGER "
              + entry.getKey()
              + "_no_replace BEFORE INSERT ON "
              + entry.getKey()
              + " WHEN EXISTS(SELECT 1 FROM "
              + entry.getKey()
              + " WHERE "
              + entry.getValue()
              + ") BEGIN SELECT RAISE(ABORT,'immutable cleanup identity'); END");
    }
  }

  private void completionGuard() {
    String remaining =
        CleanupPayloadTables.TABLES.stream()
            .map(
                table ->
                    "EXISTS(SELECT 1 FROM "
                        + table.name()
                        + " p WHERE "
                        + table.document("p")
                        + "=NEW.document_id AND (payload_purged!=1 OR NOT ("
                        + table.empty("p")
                        + ")))")
            .collect(java.util.stream.Collectors.joining(" OR "));
    store.execute(
        "CREATE TRIGGER cleanup_completed_payloads BEFORE UPDATE ON document_cleanups WHEN NEW.state='completed' AND ("
            + "NOT EXISTS(SELECT 1 FROM cleanup_plans p WHERE p.cleanup_id=NEW.id AND p.document_id=NEW.document_id AND p.source_sha256=NEW.source_sha256) OR "
            + remaining
            + ") BEGIN SELECT RAISE(ABORT,'cleanup payloads remain'); END");
  }

  private void rebuild(CleanupPayloadTables.Table table) {
    String name = table.name();
    String definition =
        (String)
            store
                .rows("SELECT sql FROM sqlite_master WHERE type='table' AND name=?", name)
                .getFirst()
                .get("sql");
    var oldColumns =
        store.rows("SELECT name FROM pragma_table_info(?) ORDER BY cid", name).stream()
            .map(r -> (String) r.get("name"))
            .toList();
    if (oldColumns.contains("payload_purged")) {
      throw new IllegalStateException("Unexpected cleanup marker");
    }
    var objects =
        store.rows(
            "SELECT type,name,sql FROM sqlite_master WHERE tbl_name=? AND type IN ('index','trigger') AND sql IS NOT NULL ORDER BY name",
            name);
    String relaxed = relaxChecks(definition, table);
    int opening = relaxed.indexOf('(') + 1;
    relaxed =
        relaxed.substring(0, opening)
            + "payload_purged INTEGER NOT NULL DEFAULT 0 CHECK(payload_purged IN (0,1)),"
            + relaxed.substring(opening);
    int end = relaxed.lastIndexOf(')');
    String marker =
        ",CHECK(payload_purged=0 OR (" + table.empty(name).replace(name + ".", "") + "))";
    relaxed = relaxed.substring(0, end) + marker + relaxed.substring(end);
    relaxed =
        relaxed.replaceFirst(
            "(?i)CREATE TABLE\\s+\"?" + Pattern.quote(name) + "\"?",
            "CREATE TABLE " + name + "_v22");
    store.execute(relaxed);
    String columns = String.join(",", oldColumns);
    store.execute(
        "INSERT INTO " + name + "_v22(" + columns + ") SELECT " + columns + " FROM " + name);
    if (store.count("SELECT COUNT(*) FROM " + name)
            != store.count("SELECT COUNT(*) FROM " + name + "_v22")
        || store.count(
                "SELECT COUNT(*) FROM (SELECT "
                    + columns
                    + " FROM "
                    + name
                    + " EXCEPT SELECT "
                    + columns
                    + " FROM "
                    + name
                    + "_v22)")
            != 0
        || store.count(
                "SELECT COUNT(*) FROM (SELECT "
                    + columns
                    + " FROM "
                    + name
                    + "_v22 EXCEPT SELECT "
                    + columns
                    + " FROM "
                    + name
                    + ")")
            != 0) {
      throw new IllegalStateException("Cleanup migration changed historical data");
    }
    store.execute("DROP TABLE " + name);
    store.execute("ALTER TABLE " + name + "_v22 RENAME TO " + name);
    String condition = authorized(table, oldColumns);
    for (var object : objects) {
      String sql = (String) object.get("sql");
      if ("trigger".equals(object.get("type"))
          && Pattern.compile("(?i)\\bBEFORE\\s+UPDATE\\b").matcher(sql).find()) {
        int begin = sql.toUpperCase(java.util.Locale.ROOT).indexOf("BEGIN");
        int when = sql.toUpperCase(java.util.Locale.ROOT).indexOf(" WHEN ");
        // Existing guards may put WHEN at the next line.
        var matcher = Pattern.compile("(?i)\\bWHEN\\b").matcher(sql.substring(0, begin));
        when = matcher.find() ? matcher.start() : -1;
        String oldWhen = when < 0 ? "1" : sql.substring(when + 4, begin).strip();
        sql =
            sql.substring(0, when < 0 ? begin : when)
                + " WHEN NOT ("
                + condition
                + ") AND ("
                + oldWhen
                + ") "
                + sql.substring(begin);
      }
      store.execute(sql);
    }
    store.execute(
        "CREATE TRIGGER cleanup_"
            + name
            + "_insert BEFORE INSERT ON "
            + name
            + " WHEN NEW.payload_purged!=0 BEGIN SELECT RAISE(ABORT,'new evidence cannot be purged'); END");
    var collisions = new ArrayList<String>();
    collisions.add(table.rowKey("prior") + "=" + table.rowKey("NEW"));
    for (var index : store.rows("SELECT name FROM pragma_index_list(?) WHERE \"unique\"=1", name)) {
      var keys =
          store
              .rows("SELECT name FROM pragma_index_info(?) ORDER BY seqno", index.get("name"))
              .stream()
              // SQLite UNIQUE permits multiple NULLs (silent spans and subtitle clear cues).
              // Equality must retain that rule; IS would incorrectly collapse absent ordinals.
              .map(row -> "prior." + row.get("name") + "=NEW." + row.get("name"))
              .toList();
      if (!keys.isEmpty()) {
        collisions.add("(" + String.join(" AND ", keys) + ")");
      }
    }
    store.execute(
        "CREATE TRIGGER cleanup_"
            + name
            + "_replace BEFORE INSERT ON "
            + name
            + " WHEN EXISTS(SELECT 1 FROM "
            + name
            + " prior WHERE "
            + String.join(" OR ", collisions)
            + ") BEGIN SELECT RAISE(ABORT,'immutable cleanup row identity'); END");
    // Includes the historical tombstone-only sound/video raw erasure exception: body changes
    // now require the same sealed-plan capability even when the marker is left at zero.
    String bodyChanged =
        table.bodies().stream()
            .map(body -> "NEW." + body.name() + " IS NOT OLD." + body.name())
            .collect(java.util.stream.Collectors.joining(" OR "));
    store.execute(
        "CREATE TRIGGER cleanup_"
            + name
            + "_purge BEFORE UPDATE ON "
            + name
            + " WHEN ("
            + bodyChanged
            + " OR NEW.payload_purged IS NOT OLD.payload_purged OR OLD.payload_purged=1) AND NOT ("
            + condition
            + ") BEGIN SELECT RAISE(ABORT,'unauthorized cleanup'); END");
  }

  private String authorized(CleanupPayloadTables.Table table, List<String> columns) {
    var bodyNames = table.bodies().stream().map(CleanupPayloadTables.Body::name).toList();
    var same = new ArrayList<String>();
    for (String column : columns) {
      if (!bodyNames.contains(column)) {
        same.add("NEW." + column + " IS OLD." + column);
      }
    }
    return "OLD.payload_purged=0 AND NEW.payload_purged=1 AND "
        + table.empty("NEW")
        + " AND "
        + String.join(" AND ", same)
        + " AND java_cleanup_authorized("
        + table.document("OLD")
        + ",'"
        + table.name()
        + "',"
        + table.rowKey("OLD")
        + ")=1";
  }

  /** Wrap only CHECK expressions mentioning actual body columns, not hashes or locators. */
  private static String relaxChecks(String sql, CleanupPayloadTables.Table table) {
    var result = new StringBuilder();
    int cursor = 0;
    var matcher = Pattern.compile("(?i)CHECK\\s*\\(").matcher(sql);
    while (matcher.find(cursor)) {
      int opening = sql.indexOf('(', matcher.start());
      int depth = 1, end = opening + 1;
      boolean quoted = false;
      while (depth > 0 && end < sql.length()) {
        char c = sql.charAt(end++);
        if (c == '\'') {
          if (quoted && end < sql.length() && sql.charAt(end) == '\'') {
            end++;
          } else {
            quoted = !quoted;
          }
        } else if (!quoted) {
          depth += c == '(' ? 1 : c == ')' ? -1 : 0;
        }
      }
      if (depth != 0) {
        throw new IllegalStateException("Malformed authority CHECK");
      }
      String expression = sql.substring(opening + 1, end - 1);
      boolean body =
          table.bodies().stream()
              .anyMatch(b -> Pattern.compile("\\b" + b.name() + "\\b").matcher(expression).find());
      result.append(sql, cursor, opening + 1);
      if (body) {
        result.append("payload_purged=1 OR (").append(expression).append(')');
      } else {
        result.append(expression);
      }
      result.append(')');
      cursor = end;
    }
    return result.append(sql.substring(cursor)).toString();
  }

  void verify() {
    if (store.count("PRAGMA user_version") != 22
        || store.count(
                "SELECT COUNT(*) FROM format_info WHERE version=22 AND format='evidence-rag-java-management-v1'")
            != 1
        || store.count("PRAGMA application_id") != 1163280711
        || store.count("SELECT COUNT(*) FROM pragma_foreign_key_check") != 0) {
      throw new IllegalStateException("Unsupported cleanup authority format");
    }
    var expected = new LinkedHashMap<String, String>();
    var actual = new LinkedHashMap<String, String>();
    for (var row : objects()) {
      actual.put(row.get("type") + ":" + row.get("name"), hash((String) row.get("sql")));
    }
    for (var row : store.rows("SELECT name,object_type,sql_sha256 FROM cleanup_schema_objects")) {
      expected.put(row.get("object_type") + ":" + row.get("name"), (String) row.get("sql_sha256"));
    }
    if (!actual.equals(expected)) {
      throw new IllegalStateException("Changed cleanup authority guards");
    }
    new DocumentCleanupRepository(store).verifyPurgedRows();
  }

  private List<Map<String, Object>> objects() {
    return store.rows(
        "SELECT type,name,sql FROM sqlite_master WHERE type IN ('table','index','trigger') AND sql IS NOT NULL ORDER BY type,name");
  }

  private static String hash(String text) {
    return ModelValues.sha256(text.getBytes(StandardCharsets.UTF_8));
  }
}
