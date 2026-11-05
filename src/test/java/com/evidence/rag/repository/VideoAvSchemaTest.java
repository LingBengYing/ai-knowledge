package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.SyntheticDocument;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VideoAvSchemaTest {
  private static final List<String> TABLES =
      List.of(
          "video_av_trace_evidence",
          "video_av_trace_documents",
          "video_av_traces",
          "video_av_windows",
          "video_av_publications",
          "video_av_originals");
  private static final Actor OWNER = new Actor("org", "owner");
  @TempDir Path directory;

  @Test
  void freshLibraryHasExactSixVideoAvTablesAndDeferredChildren() {
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(LibrarySchema.VERSION, store.count("PRAGMA user_version"));
            assertEquals(LibrarySchema.VERSION, store.count("SELECT version FROM format_info"));
            for (String table : TABLES) {
              assertEquals(0, store.count("SELECT COUNT(*) FROM " + table));
            }
            assertEquals(
                24,
                store.count(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name LIKE 'video_av_%' AND name NOT LIKE 'video_av_query_%'"));
            assertEquals(
                33, store.count("SELECT COUNT(*) FROM pragma_table_info('video_av_publications')"));
            assertEquals(
                19, store.count("SELECT COUNT(*) FROM pragma_table_info('video_av_windows')"));
            assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
            return null;
          });
    }
  }

  @Test
  void metadataIsImmutableAndOnlyTombstoneAllowsRawBlobErasure() throws Exception {
    try (var store = new SqliteAuthorityStore(directory)) {
      var original = register(store);
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute("UPDATE video_av_originals SET original_blob=x''");
                    return null;
                  }));
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute("UPDATE video_av_originals SET filename='changed.mp4'");
                    return null;
                  }));
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    LegacyMediaRows.videoAvOriginal(store, original, Instant.now().toString());
                    return null;
                  }));
      store.transaction(
          () -> {
            store.execute(
                "INSERT INTO document_tombstones(document_id,workspace_id,requested_by,requested_at) VALUES(?,?,?,?)",
                original.documentId(),
                "org",
                "owner",
                Instant.now().toString());
            return null;
          });
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute("UPDATE video_av_originals SET original_blob=x''");
                    return null;
                  }));
      CleanupMaintenanceFixture.purge(store, OWNER, original.documentId());
      store.transaction(
          () -> {
            assertEquals(0, store.count("SELECT length(original_blob) FROM video_av_originals"));
            return null;
          });
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute(
                        "UPDATE video_av_originals SET original_blob=?", original.content());
                    return null;
                  }));
    }
  }

  @Test
  void partiallyNullMediaGroupAndMissingHeaderCannotSurviveTransaction() {
    try (var store = new SqliteAuthorityStore(directory)) {
      register(store);
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute(
                        "INSERT INTO video_av_windows(publication_id,id,ordinal,start_tick,end_tick,clip_sha256) VALUES(?,?,?,?,?,?)",
                        "unknown",
                        "av-" + "a".repeat(64),
                        0,
                        0,
                        10,
                        "b".repeat(64));
                    return null;
                  }));
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute(
                        "INSERT INTO video_av_windows(publication_id,id,ordinal,start_tick,end_tick,clip_sha256,frame_count,frames_manifest_sha256,first_local_tick,end_local_tick,visual_physical_id,visual_entry_sha256) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                        "unknown",
                        "av-" + "a".repeat(64),
                        0,
                        0,
                        10,
                        "b".repeat(64),
                        1,
                        "c".repeat(64),
                        0,
                        10,
                        "seg-" + "d".repeat(64),
                        "e".repeat(64));
                    return null;
                  }));
      store.transaction(
          () -> {
            assertEquals(0, store.count("SELECT COUNT(*) FROM video_av_windows"));
            return null;
          });
    }
  }

  @Test
  void missingGuardAndExtraColumnFailClosedOnReopen() {
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            store.execute("DROP TRIGGER video_av_windows_sealed");
            return null;
          });
    }
    assertThrows(IllegalStateException.class, () -> new SqliteAuthorityStore(directory));
  }

  private static DocumentOriginal register(SqliteAuthorityStore store) {
    byte[] raw = new byte[16];
    System.arraycopy("ftyp".getBytes(StandardCharsets.US_ASCII), 0, raw, 4, 4);
    var original =
        new DocumentOriginal(
            "doc",
            "rev",
            "clip.mp4",
            "video",
            "video/mp4",
            ModelValues.sha256(raw),
            raw.length,
            raw);
    return store.transaction(
        () -> {
          var management = new ManagementRepository(store);
          management.insertDocument(
              OWNER,
              new SyntheticDocument(
                  original.documentId(),
                  original.filename(),
                  original.documentType(),
                  original.mediaType(),
                  original.revisionId(),
                  original.sourceSha256(),
                  original.sizeBytes()),
              Instant.now().toString());
          management.insertGrant(original.documentId(), "owner", "owner");
          LegacyMediaRows.videoAvOriginal(store, original, Instant.now().toString());
          return original;
        });
  }
}
