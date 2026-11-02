package com.evidence.rag.repository;

import com.evidence.rag.model.domain.ModelValues;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * sqlite_master helpers shared by the versioned migrations. Callers hold the Store transaction;
 * none of these methods changes stored SQL beyond what the caller asks for.
 */
final class SchemaObjects {
  private static final String INVENTORY_GUARD = "cleanup_schema_objects_no_update";
  private static final String ALL_OBJECTS =
      "SELECT type,name,sql FROM sqlite_master WHERE type IN ('table','index','trigger') AND sql IS NOT NULL ORDER BY type,name";

  private final SqliteAuthorityStore store;

  SchemaObjects(SqliteAuthorityStore store) {
    this.store = store;
  }

  /** The stored definition of one schema object; the object must exist. */
  String sql(String type, String name) {
    return AuthorityRows.text(
        store.rows("SELECT sql FROM sqlite_master WHERE type=? AND name=?", type, name).getFirst(),
        "sql");
  }

  /** Recreates trigger {@code name} with {@code condition} AND-ed in front of its WHEN clause. */
  void wrapTrigger(String name, String condition) {
    String original = sql("trigger", name);
    store.execute("DROP TRIGGER " + name);
    store.execute(withCondition(original, condition));
  }

  /**
   * Runs {@code work} with the inventory guard dropped and restores its exact original SQL. The
   * inventory is immutable during normal operation; a failure leaves the rollback to the caller.
   */
  void withoutInventoryGuard(Runnable work) {
    String guard = sql("trigger", INVENTORY_GUARD);
    store.execute("DROP TRIGGER " + INVENTORY_GUARD);
    work.run();
    store.execute(guard);
  }

  /** Inserts or refreshes the inventory hash of every stored table, index and trigger. */
  void refreshInventory() {
    refreshInventory(ALL_OBJECTS);
  }

  /**
   * Inserts or refreshes the inventory hash of the {@code type,name,sql} rows {@code query}
   * selects.
   */
  void refreshInventory(String query) {
    withoutInventoryGuard(
        () -> {
          for (var row : store.rows(query)) {
            String name = AuthorityRows.text(row, "name");
            String hash =
                ModelValues.sha256(AuthorityRows.text(row, "sql").getBytes(StandardCharsets.UTF_8));
            if (store.count("SELECT COUNT(*) FROM cleanup_schema_objects WHERE name=?", name)
                == 0) {
              store.execute(
                  "INSERT INTO cleanup_schema_objects(name,object_type,sql_sha256) VALUES(?,?,?)",
                  name,
                  row.get("type"),
                  hash);
            } else {
              store.execute(
                  "UPDATE cleanup_schema_objects SET object_type=?,sql_sha256=? WHERE name=?",
                  row.get("type"),
                  hash,
                  name);
            }
          }
        });
  }

  /** Trigger SQL with {@code condition} AND-ed in front of its WHEN clause (or {@code 1}). */
  static String withCondition(String original, String condition) {
    int begin = original.toUpperCase(Locale.ROOT).indexOf("BEGIN");
    var matcher = Pattern.compile("(?i)\\bWHEN\\b").matcher(original.substring(0, begin));
    int when = matcher.find() ? matcher.start() : -1;
    String old = when < 0 ? "1" : original.substring(when + 4, begin).strip();
    return original.substring(0, when < 0 ? begin : when)
        + " WHEN ("
        + condition
        + ") AND ("
        + old
        + ") "
        + original.substring(begin);
  }

  /** Table SQL with {@code column} inserted as the first column definition. */
  static String insertColumn(String sql, String column) {
    int opening = sql.indexOf('(') + 1;
    return sql.substring(0, opening) + column + "," + sql.substring(opening);
  }
}
