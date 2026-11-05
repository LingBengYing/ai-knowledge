package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QueryTraceSchemaTest {
  private static final List<String> TABLES =
      List.of(
          "query_trace_attachment_images", "query_trace_attachments", "query_trace_preparations");
  @TempDir Path directory;

  @Test
  void newStoreHasHashOnlyQueryTraceSidecars() {
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(LibrarySchema.VERSION, store.count("PRAGMA user_version"));
            assertEquals(LibrarySchema.VERSION, store.count("SELECT version FROM format_info"));
            for (String table : TABLES) {
              assertEquals(
                  1,
                  store.count(
                      "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", table));
              assertEquals(
                  4,
                  store.count(
                      "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN (?,?,?,?)",
                      table + "_sealed",
                      table + "_no_replace",
                      table + "_no_update",
                      table + "_no_delete"));
              assertEquals(
                  0,
                  store.count(
                      "SELECT COUNT(*) FROM pragma_table_info(?) WHERE name IN ('question','text','filename','content','transcript','answer','bytes')",
                      table));
            }
            assertEquals(
                1,
                store.count(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name='query_traces_query_preparation_complete'"));
            assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
            return null;
          });
    }
  }
}
