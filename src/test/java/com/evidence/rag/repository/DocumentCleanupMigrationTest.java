package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.support.PublishedCorpusFixture;
import com.evidence.rag.support.SubtitleCorpusFixture;
import com.evidence.rag.support.SynopsisCorpusFixture;
import com.evidence.rag.support.VideoSubtitleCompilationFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** New observable format and ordinary-writer boundaries, using a real temporary SQLite library. */
class DocumentCleanupMigrationTest {
  @TempDir Path directory;

  @Test
  void newLibraryHasV22RealPayloadMarkersAndAllPriorQueryGuards() {
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(25, store.count("PRAGMA user_version"));
            assertEquals(25, store.count("SELECT version FROM format_info"));
            for (String table : CleanupPayloadTables.names()) {
              assertEquals(
                  1,
                  store.count(
                      "SELECT COUNT(*) FROM pragma_table_info(?) WHERE name='payload_purged'",
                      table));
            }
            assertEquals(
                9,
                store.count(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name LIKE 'video_av_query_%'"));
            assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
            assertEquals(1, store.count("PRAGMA foreign_keys"));
            return null;
          });
    }
  }

  @Test
  void OrdinarySqlCannotManufactureConnectionLocalMaintenance() {
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(
                0, store.count("SELECT java_cleanup_authorized('forged','corpus_pages','forged')"));
            return null;
          });
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute("INSERT INTO cleanup_authorization VALUES('forged')");
                    return null;
                  }));
      assertTrue(store.operationGate().isIdle());
    }
  }

  @Test
  void realV21UpgradePreservesBytesAndRowsAndRegistersOnlyItsOwnActualSnapshot() throws Exception {
    String document;
    byte[] original = "真正旧版正文，升级后仍逐字保存。".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    try (var fixture = new PublishedCorpusFixture(directory)) {
      document =
          fixture
              .publish(
                  new Actor("org", "owner"),
                  new String(original, java.nio.charset.StandardCharsets.UTF_8))
              .documentId();
    }
    CleanupV21Fixture.restoreVersionTwentyOne(directory);
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(25, store.count("PRAGMA user_version"));
            assertEquals(
                1,
                store.count(
                    "SELECT COUNT(*) FROM corpus_documents WHERE document_id=? AND original_blob=? AND payload_purged=0",
                    document,
                    original));
            assertEquals(
                0,
                store.count(
                    "SELECT inventory_known FROM cleanup_document_provenance WHERE document_id=?",
                    document));
            assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
            return null;
          });
      var backups = store.managedBackups();
      assertTrue(backups.known());
      assertEquals(4, backups.files().size());
      for (var backup : backups.files()) {
        assertEquals(Files.size(directory.resolve(backup.relativePath())), backup.sizeBytes());
        assertEquals(
            com.evidence.rag.model.domain.ModelValues.sha256(
                Files.readAllBytes(directory.resolve(backup.relativePath()))),
            backup.sha256());
      }
      assertEquals(
          1,
          backups.files().stream()
              .filter(backup -> backup.relativePath().startsWith("java-library.v21-before-v22-"))
              .count());
      assertEquals(
          1,
          backups.files().stream()
              .filter(backup -> backup.relativePath().startsWith("java-library.v22-before-v23-"))
              .count());
      assertEquals(
          1,
          backups.files().stream()
              .filter(backup -> backup.relativePath().startsWith("java-library.v23-before-v24-"))
              .count());
      assertEquals(
          1,
          backups.files().stream()
              .filter(backup -> backup.relativePath().startsWith("java-library.v24-before-v25-"))
              .count());
    }
  }

  @Test
  void preexistingUnregisteredSnapshotKeepsManagedInventoryUnknownInsteadOfGuessingOwnership()
      throws Exception {
    try (var ignored = new SqliteAuthorityStore(directory)) {}
    CleanupV21Fixture.restoreVersionTwentyOne(directory);
    Path unknown = directory.resolve("java-library.v20-before-v21-unregistered.db");
    Files.copy(directory.resolve("java-library.db"), unknown, StandardCopyOption.COPY_ATTRIBUTES);
    byte[] unchanged = Files.readAllBytes(unknown);
    try (var store = new SqliteAuthorityStore(directory)) {
      org.junit.jupiter.api.Assertions.assertFalse(store.managedBackups().known());
      assertEquals(4, store.managedBackups().files().size());
      assertEquals(
          1,
          store.managedBackups().files().stream()
              .filter(backup -> backup.relativePath().startsWith("java-library.v24-before-v25-"))
              .count());
    }
    org.junit.jupiter.api.Assertions.assertArrayEquals(unchanged, Files.readAllBytes(unknown));
  }

  @Test
  void ordinaryTextWritesKeepPayloadMarkerDefaultAndPublishExactOriginalEvidence() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var actor = new Actor("org", "owner");
      String original = "升级后的普通文字必须完整解析并发布。";
      var claim = fixture.publish(actor, original);
      var store = fixture.authority.store();
      store.transaction(
          () -> {
            assertEquals(
                1,
                store.count(
                    "SELECT COUNT(*) FROM corpus_pages WHERE revision_id=? AND text=? AND payload_purged=0",
                    claim.revisionId(),
                    original));
            assertEquals(
                claim.items().size(),
                store.count(
                    "SELECT COUNT(*) FROM corpus_segments WHERE revision_id=? AND payload_purged=0",
                    claim.revisionId()));
            assertEquals(
                1,
                store.count(
                    "SELECT COUNT(*) FROM index_publications WHERE document_id=?",
                    claim.documentId()));
            return null;
          });
    }
  }

  @Test
  void distinctSilentSpansAndSubtitleClearCuesRetainSqliteNullableUniqueSemantics() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var actor = new Actor("org", "owner");
      var store = fixture.authority.store();
      var audio =
          new SynopsisCorpusFixture(store, actor, PublishedCorpusFixture.TARGET)
              .audio(List.of("", "完整有声片段。", ""));
      var video =
          SubtitleCorpusFixture.publish(
              fixture, actor, VideoSubtitleCompilationFixture.compilation(true));
      store.transaction(
          () -> {
            assertEquals(
                3,
                store.count(
                    "SELECT COUNT(*) FROM audio_spans WHERE revision_id=? AND payload_purged=0",
                    audio.sourceRevisionId()));
            assertEquals(
                2,
                store.count(
                    "SELECT COUNT(*) FROM audio_spans WHERE revision_id=? AND index_ordinal IS NULL AND text=''",
                    audio.sourceRevisionId()));
            assertEquals(
                2,
                store.count(
                    "SELECT COUNT(*) FROM video_subtitle_cues WHERE revision_id=? AND index_ordinal IS NULL AND text='' AND payload_purged=0",
                    video.sourceRevisionId()));
            assertEquals(
                3,
                store.count(
                    "SELECT COUNT(*) FROM video_subtitle_cues WHERE revision_id=? AND index_ordinal IS NOT NULL",
                    video.sourceRevisionId()));
            assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
            return null;
          });
    }
  }
}
