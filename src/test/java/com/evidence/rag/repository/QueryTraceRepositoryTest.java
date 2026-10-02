package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.QueryAttachment;
import com.evidence.rag.model.domain.QueryTrace;
import com.evidence.rag.model.domain.QueryTraceAttachment;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QueryTraceRepositoryTest {
  @TempDir Path directory;

  @Test
  void preparedAndFailedHashOnlyOutcomesSurviveReopeningWithNoModelsOrAttachmentBytes() {
    try (var store = new SqliteAuthorityStore(directory)) {
      QueryTraceMigrationTest.insert(store, "prepared", QueryTraceMigrationTest.prepared());
      QueryTraceMigrationTest.insert(store, "failed", failed());
      QueryTraceMigrationTest.insert(store, "plain", null);
    }
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(3, store.count("SELECT COUNT(*) FROM query_traces"));
            assertEquals(2, store.count("SELECT COUNT(*) FROM query_trace_preparations"));
            assertEquals(3, store.count("SELECT COUNT(*) FROM query_trace_attachments"));
            assertEquals(2, store.count("SELECT COUNT(*) FROM query_trace_attachment_images"));
            var prepared =
                store
                    .rows("SELECT * FROM query_trace_preparations WHERE trace_id='prepared'")
                    .getFirst();
            assertEquals(QueryTraceMigrationTest.SHA, prepared.get("question_sha256"));
            assertEquals("prepared", prepared.get("status"));
            assertEquals("rank-v1", prepared.get("ranking_revision"));
            assertEquals(QueryTraceMigrationTest.OTHER, prepared.get("manifest_sha256"));
            var asset =
                store
                    .rows("SELECT * FROM query_trace_attachments WHERE trace_id='prepared'")
                    .getFirst();
            assertEquals("VIDEO", asset.get("media_kind"));
            assertEquals("video-compiler-v1", asset.get("compiler_revision"));
            assertEquals(4, ((Number) asset.get("visual_count")).intValue());
            assertEquals(1, ((Number) asset.get("visual_sampled")).intValue());
            assertEquals(
                List.of(QueryTraceMigrationTest.SHA, QueryTraceMigrationTest.OTHER),
                store
                    .rows(
                        "SELECT image_sha256 FROM query_trace_attachment_images WHERE trace_id='prepared' ORDER BY ordinal")
                    .stream()
                    .map(row -> row.get("image_sha256"))
                    .toList());
            var failure =
                store
                    .rows("SELECT * FROM query_trace_preparations WHERE trace_id='failed'")
                    .getFirst();
            assertEquals("failed", failure.get("status"));
            assertEquals("query_attachment_failed", failure.get("reason_code"));
            assertNull(failure.get("manifest_sha256"));
            assertEquals(
                2,
                store.count(
                    "SELECT COUNT(*) FROM query_trace_attachments WHERE trace_id='failed' AND compiler_revision IS NULL AND content_sha256 IS NULL AND text_code_points IS NULL AND visual_count IS NULL AND visual_sampled IS NULL"));
            assertEquals(
                0,
                store.count(
                    "SELECT COUNT(*) FROM query_trace_preparations WHERE trace_id='plain'"));
            assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
            return null;
          });
    }
  }

  @Test
  void sealedPreparationIsImmutableAndRejectsLateChildrenOrReplacement() {
    try (var store = new SqliteAuthorityStore(directory)) {
      QueryTraceMigrationTest.insert(store, "prepared", QueryTraceMigrationTest.prepared());
      for (String query :
          List.of(
              "UPDATE query_trace_preparations SET status='failed' WHERE trace_id='prepared'",
              "DELETE FROM query_trace_preparations WHERE trace_id='prepared'",
              "UPDATE query_trace_attachments SET source_sha256='"
                  + QueryTraceMigrationTest.OTHER
                  + "'",
              "DELETE FROM query_trace_attachment_images",
              "INSERT OR REPLACE INTO query_trace_preparations SELECT * FROM query_trace_preparations",
              "INSERT OR REPLACE INTO query_trace_attachments SELECT * FROM query_trace_attachments",
              "INSERT OR REPLACE INTO query_trace_attachment_images SELECT * FROM query_trace_attachment_images",
              "INSERT INTO query_trace_attachment_images VALUES('prepared',0,2,'"
                  + "c".repeat(64)
                  + "')")) {
        assertThrows(
            ApplicationException.class,
            () ->
                store.transaction(
                    () -> {
                      store.execute(query);
                      return null;
                    }));
      }
      store.transaction(
          () -> {
            assertEquals(1, store.count("SELECT COUNT(*) FROM query_traces"));
            assertEquals(2, store.count("SELECT COUNT(*) FROM query_trace_attachment_images"));
            return null;
          });
    }
  }

  @Test
  void terminalHeaderFailureRollsBackItsNewAttachmentSidecarsAtomically() {
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            store.execute(
                "CREATE TRIGGER reject_test_trace BEFORE INSERT ON query_traces BEGIN SELECT RAISE(ABORT,'test-only rejection'); END");
            return null;
          });
      assertThrows(
          ApplicationException.class,
          () ->
              QueryTraceMigrationTest.insert(store, "reject", QueryTraceMigrationTest.prepared()));
      store.transaction(
          () -> {
            assertEquals(0, store.count("SELECT COUNT(*) FROM query_traces"));
            for (String table : QueryTraceMigrationTest.TABLES) {
              assertEquals(0, store.count("SELECT COUNT(*) FROM " + table));
            }
            return null;
          });
    }
  }

  @Test
  void terminalHeaderRejectsMissingAttachmentsImagesStatusOrQuestionBinding() {
    try (var store = new SqliteAuthorityStore(directory)) {
      for (String defect :
          List.of("attachment_count", "image_count", "ordinal_gap", "question", "status")) {
        assertThrows(
            ApplicationException.class,
            () ->
                store.transaction(
                    () -> {
                      String status = defect.equals("status") ? "failed" : "prepared";
                      store.execute(
                          "INSERT INTO query_trace_preparations VALUES('incomplete',?,?,?,?,?,?,?)",
                          defect.equals("question")
                              ? QueryTraceMigrationTest.OTHER
                              : QueryTraceMigrationTest.SHA,
                          "p",
                          "r",
                          status,
                          status.equals("failed") ? "failed" : null,
                          status.equals("failed") ? null : QueryTraceMigrationTest.OTHER,
                          defect.equals("attachment_count") ? 2 : 1);
                      int attachmentOrdinal = defect.equals("ordinal_gap") ? 1 : 0;
                      store.execute(
                          "INSERT INTO query_trace_attachments VALUES('incomplete',?,?,'VIDEO','compiler',?,3,4,2,1)",
                          attachmentOrdinal,
                          QueryTraceMigrationTest.SHA,
                          QueryTraceMigrationTest.OTHER);
                      store.execute(
                          "INSERT INTO query_trace_attachment_images VALUES('incomplete',?,0,?)",
                          attachmentOrdinal,
                          QueryTraceMigrationTest.SHA);
                      if (!defect.equals("image_count")) {
                        store.execute(
                            "INSERT INTO query_trace_attachment_images VALUES('incomplete',?,1,?)",
                            attachmentOrdinal,
                            QueryTraceMigrationTest.OTHER);
                      }
                      store.execute(
                          "INSERT INTO query_traces VALUES('incomplete','org','owner',1,0,0,?,NULL,'abstained','insufficient_evidence','m','p','g','2026-09-20T09:00:00Z')",
                          QueryTraceMigrationTest.SHA);
                      return null;
                    }));
      }
      store.transaction(
          () -> {
            assertEquals(0, store.count("SELECT COUNT(*) FROM query_traces"));
            for (String table : QueryTraceMigrationTest.TABLES) {
              assertEquals(0, store.count("SELECT COUNT(*) FROM " + table));
            }
            return null;
          });
    }
  }

  @Test
  void deferredParentIdentityPreventsOrphanPreparationFromCommitting() {
    try (var store = new SqliteAuthorityStore(directory)) {
      assertThrows(
          ApplicationException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute(
                        "INSERT INTO query_trace_preparations VALUES('orphan',?,'p',NULL,'failed','failed',NULL,1)",
                        QueryTraceMigrationTest.SHA);
                    store.execute(
                        "INSERT INTO query_trace_attachments VALUES('orphan',0,?,'AUDIO',NULL,NULL,NULL,NULL,0,NULL)",
                        QueryTraceMigrationTest.SHA);
                    return null;
                  }));
      store.transaction(
          () -> {
            assertEquals(0, store.count("SELECT COUNT(*) FROM query_trace_preparations"));
            assertEquals(0, store.count("SELECT COUNT(*) FROM query_trace_attachments"));
            return null;
          });
    }
  }

  private static QueryTrace failed() {
    return new QueryTrace(
        QueryTraceMigrationTest.SHA,
        "prepare-v1",
        null,
        "failed",
        "query_attachment_failed",
        null,
        List.of(
            new QueryTraceAttachment(
                0, QueryTraceMigrationTest.SHA, QueryAttachment.Kind.IMAGE, null),
            new QueryTraceAttachment(
                1, QueryTraceMigrationTest.OTHER, QueryAttachment.Kind.AUDIO, null)));
  }
}
