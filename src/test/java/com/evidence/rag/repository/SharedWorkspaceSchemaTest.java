package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.support.AuthorityTestContext;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Shared-workspace trace contracts on the current schema, with zero remote calls. */
class SharedWorkspaceSchemaTest {
  private static final String SHA = "a".repeat(64);
  private static final String NOW = "2026-10-08T00:00:00Z";
  private static final List<String> TABLES =
      List.of(
          "knowledge_answer_traces",
          "knowledge_answer_documents",
          "knowledge_answer_citations",
          "query_traces",
          "query_trace_documents",
          "sound_traces",
          "sound_trace_documents",
          "video_av_traces",
          "video_av_trace_documents");
  @TempDir Path directory;

  @Test
  void freshSchemaKeepsIdentityAndSealingButNotBusinessCaps() {
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(LibrarySchema.VERSION, store.count("PRAGMA user_version"));
            assertEquals(LibrarySchema.VERSION, store.count("SELECT version FROM format_info"));
            for (String table : TABLES) {
              String sql = sql(store, "table", table);
              assertFalse(sql.contains("scope_count BETWEEN 0 AND 128"), table);
              assertFalse(sql.contains("ordinal BETWEEN 0 AND 127"), table);
              if (table.startsWith("knowledge_answer_")) {
                assertFalse(sql.contains("citation_count BETWEEN 0 AND 32"));
                assertFalse(sql.contains("ordinal BETWEEN 1 AND 32"));
                assertFalse(sql.contains("end_offset-start_offset<=1200"));
              }
              assertEquals(
                  2,
                  store.count(
                      "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN (?,?)",
                      table + "_no_update",
                      table + "_no_delete"));
            }
            for (String name : List.of("sound_traces_complete", "video_av_traces_complete")) {
              String guard = sql(store, "trigger", name);
              assertFalse(guard.contains("document_acl"));
              assertFalse(guard.contains("a.principal_id"));
              assertTrue(guard.contains("d.workspace_id=NEW.workspace_id"));
              assertTrue(guard.contains("d.active_revision_id=p.source_revision_id"));
              assertTrue(guard.contains("d.source_sha256=p.source_sha256"));
              assertTrue(guard.contains("document_tombstones"));
            }
            assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
            return null;
          });
    }
  }

  @Test
  void sealsRealFullLibraryAbove128DocumentsAndMoreThan32KnowledgeCitations() throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      var publications = new java.util.ArrayList<ReindexSqlFixture.Published>();
      for (int index = 0; index < 129; index++) {
        publications.add(ReindexSqlFixture.publish(authority));
      }
      var store = authority.store();
      store.transaction(
          () -> {
            for (int ordinal = 0; ordinal < publications.size(); ordinal++) {
              String publication = publications.get(ordinal).publicationId();
              store.execute(
                  "INSERT INTO knowledge_answer_documents VALUES(?,?,?)",
                  "large-knowledge",
                  ordinal,
                  publication);
              store.execute(
                  "INSERT INTO query_trace_documents VALUES(?,?,?)",
                  "large-query",
                  ordinal,
                  publication);
              if (ordinal < 33) {
                store.execute(
                    """
                INSERT INTO knowledge_answer_citations
                SELECT ?,?,e.publication_id,'DOCUMENT_TEXT',e.physical_segment_id,s.start_offset,s.end_offset,
                  pg.text_sha256,s.text_sha256,s.page_number,NULL,NULL
                FROM index_publication_entries e JOIN corpus_segments s ON s.id=e.source_segment_id
                  JOIN corpus_pages pg ON pg.revision_id=s.revision_id AND pg.page_number=s.page_number
                WHERE e.publication_id=? ORDER BY s.ordinal LIMIT 1
                """,
                    "large-knowledge",
                    ordinal + 1,
                    publication);
              }
            }
            store.execute(
                "INSERT INTO knowledge_answer_traces VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "large-knowledge",
                ReindexSqlFixture.OWNER.workspaceId(),
                "member-2",
                1,
                129,
                33,
                SHA,
                SHA,
                "answered",
                null,
                "model-v1",
                "prompt-v1",
                "policy-v1",
                NOW);
            store.execute(
                "INSERT INTO query_traces VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "large-query",
                ReindexSqlFixture.OWNER.workspaceId(),
                "member-2",
                1,
                129,
                0,
                SHA,
                null,
                "abstained",
                "no_evidence",
                "model-v1",
                "prompt-v1",
                "policy-v1",
                NOW);
            assertEquals(
                129,
                store.count(
                    "SELECT COUNT(*) FROM knowledge_answer_documents WHERE trace_id='large-knowledge'"));
            assertEquals(
                33,
                store.count(
                    "SELECT COUNT(*) FROM knowledge_answer_citations WHERE trace_id='large-knowledge'"));
            assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
            return null;
          });
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute(
                        "DELETE FROM knowledge_answer_citations WHERE trace_id='large-knowledge'");
                    return null;
                  }));
    }
    try (var reopened = new SqliteAuthorityStore(directory)) {
      assertEquals(
          33,
          reopened.transaction(
              () ->
                  reopened.count(
                      "SELECT citation_count FROM knowledge_answer_traces WHERE id='large-knowledge'")));
    }
  }

  private static String sql(SqliteAuthorityStore store, String type, String name) {
    return (String)
        store
            .rows("SELECT sql FROM sqlite_master WHERE type=? AND name=?", type, name)
            .getFirst()
            .get("sql");
  }
}
