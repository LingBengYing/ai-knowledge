package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.SoundProfile;
import com.evidence.rag.model.domain.SoundPublication;
import com.evidence.rag.model.domain.SoundSpan;
import com.evidence.rag.model.domain.SyntheticDocument;
import com.evidence.rag.tool.parser.AudioPcm;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SoundMigrationTest {
  private static final List<String> TABLES =
      List.of(
          "sound_trace_evidence",
          "sound_trace_documents",
          "sound_traces",
          "sound_spans",
          "sound_publications",
          "sound_originals");
  @TempDir Path directory;

  @Test
  void freshV19HasAllFixedTablesGuardsAndDeferredChildren() {
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(25, store.count("PRAGMA user_version"));
            assertEquals(25, store.count("SELECT version FROM format_info"));
            for (String table : TABLES) {
              assertEquals(0, store.count("SELECT COUNT(*) FROM " + table));
            }
            assertEquals(
                24,
                store.count(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name LIKE 'sound_%'"));
            assertEquals(
                21, store.count("SELECT COUNT(*) FROM pragma_table_info('sound_publications')"));
            return null;
          });
    }
  }

  @Test
  void v18UpgradeCreatesBackupAndKeepsOldTablesUntouched() throws Exception {
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            new ManagementRepository(store)
                .insertDocument(
                    new Actor("org", "owner"),
                    new SyntheticDocument(
                        "existing", "old.wav", "audio", "audio/wav", "old-rev", "a".repeat(64), 1),
                    Instant.now().toString());
            new ManagementRepository(store).insertGrant("existing", "owner", "owner");
            return null;
          });
    }
    restoreVersionEighteen(directory);
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(25, store.count("PRAGMA user_version"));
            assertEquals(
                1,
                store.count(
                    "SELECT COUNT(*) FROM documents WHERE id='existing' AND active_revision_id='old-rev'"));
            assertEquals(0, store.count("SELECT COUNT(*) FROM audio_vector_publications"));
            return null;
          });
    }
    try (var files = Files.list(directory)) {
      assertTrue(
          files.anyMatch(
              path -> path.getFileName().toString().startsWith("java-library.v18-before-v19-")));
    }
  }

  @Test
  void missingSealingGuardIsRejectedOnReopen() throws Exception {
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            store.execute("DROP TRIGGER sound_spans_sealed");
            return null;
          });
    }
    assertThrows(IllegalStateException.class, () -> new SqliteAuthorityStore(directory));
  }

  @Test
  void originalMetadataIsImmutableAndBlobCanOnlyBeErasedAfterTombstone() throws Exception {
    try (var store = new SqliteAuthorityStore(directory)) {
      var original = register(store);
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute(
                        "UPDATE sound_originals SET original_blob=x'' WHERE document_id=?",
                        original.documentId());
                    return null;
                  }));
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute(
                        "UPDATE sound_originals SET filename='replaced.wav' WHERE document_id=?",
                        original.documentId());
                    return null;
                  }));
      store.transaction(
          () -> {
            store.execute(
                "INSERT INTO document_tombstones VALUES(?,?,?,?)",
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
                    store.execute(
                        "UPDATE sound_originals SET original_blob=x'' WHERE document_id=?",
                        original.documentId());
                    return null;
                  }));
      CleanupMaintenanceFixture.purge(store, new Actor("org", "owner"), original.documentId());
      store.transaction(
          () -> {
            assertEquals(0, store.count("SELECT length(original_blob) FROM sound_originals"));
            assertEquals(
                original.sizeBytes(), store.count("SELECT size_bytes FROM sound_originals"));
            return null;
          });
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute("UPDATE sound_originals SET original_blob=?", original.content());
                    return null;
                  }));
    }
  }

  @Test
  void publicationSealsWholeSampleCoverageAndRejectsLaterChildOrMutation() {
    try (var store = new SqliteAuthorityStore(directory)) {
      var original = register(store);
      var publication = publication(original);
      store.transaction(
          () -> {
            new SoundRepository(store).insertPublication(publication);
            return null;
          });
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute("UPDATE sound_spans SET recall_text='changed'");
                    return null;
                  }));
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute("DELETE FROM sound_publications");
                    return null;
                  }));
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    var span = publication.spans().getFirst();
                    store.execute(
                        "INSERT INTO sound_spans(publication_id,id,ordinal,start_sample,end_sample,pcm_sha256,recall_text,physical_segment_id,entry_sha256) VALUES(?,?,?,?,?,?,?,?,?)",
                        publication.id(),
                        SoundProfile.spanId(original.revisionId(), 1),
                        1,
                        1,
                        2,
                        span.pcmSha256(),
                        "",
                        SoundProfile.physicalSegmentId(publication.generationId(), "other"),
                        span.entrySha256());
                    return null;
                  }));
      store.transaction(
          () -> {
            assertEquals(1, store.count("SELECT COUNT(*) FROM sound_spans"));
            return null;
          });
    }
  }

  @Test
  void downgradeFixtureRefusesToDiscardGenuineSoundHistory() throws Exception {
    try (var store = new SqliteAuthorityStore(directory)) {
      register(store);
    }
    assertThrows(AssertionError.class, () -> restoreVersionEighteen(directory));
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(1, store.count("SELECT COUNT(*) FROM sound_originals"));
            return null;
          });
    }
  }

  @Test
  void fullLibraryTraceCannotSealAnOmittedAuthorizedOriginal() {
    try (var store = new SqliteAuthorityStore(directory)) {
      register(store);
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute(
                        "INSERT INTO sound_traces VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        "trace",
                        "org",
                        "owner",
                        1,
                        0,
                        0,
                        "a".repeat(64),
                        null,
                        "abstained",
                        "no_evidence",
                        "sound-v1",
                        "java-sound-answer-v1",
                        Instant.now().toString());
                    return null;
                  }));
      store.transaction(
          () -> {
            assertEquals(0, store.count("SELECT COUNT(*) FROM sound_traces"));
            return null;
          });
    }
  }

  private static DocumentOriginal register(SqliteAuthorityStore store) {
    byte[] bytes = AudioPcm.wav(new byte[2], 0, 2);
    var original =
        new DocumentOriginal(
            "doc",
            "rev",
            "tone.wav",
            "audio",
            "audio/wav",
            ModelValues.sha256(bytes),
            bytes.length,
            bytes);
    store.transaction(
        () -> {
          var management = new ManagementRepository(store);
          management.insertDocument(
              new Actor("org", "owner"),
              new SyntheticDocument(
                  "doc",
                  "tone.wav",
                  "audio",
                  "audio/wav",
                  "rev",
                  original.sourceSha256(),
                  original.sizeBytes()),
              Instant.now().toString());
          management.insertGrant("doc", "owner", "owner");
          new SoundRepository(store).insertOriginal(original, Instant.now().toString());
          return null;
        });
    return original;
  }

  private static SoundPublication publication(DocumentOriginal original) {
    String generation = UUID.randomUUID().toString();
    var target = new IndexTarget("embedding-v1", "a".repeat(64), "embedding-v1", 2);
    String id = SoundProfile.spanId(original.revisionId(), 0);
    var span =
        new SoundSpan(
            id,
            0,
            0,
            1,
            "b".repeat(64),
            "",
            SoundProfile.physicalSegmentId(generation, id),
            "c".repeat(64));
    var spans = List.of(span);
    return new SoundPublication(
        "pub",
        "org",
        "doc",
        "rev",
        original.sourceSha256(),
        "tone.wav",
        "audio/wav",
        original.sizeBytes(),
        generation,
        target,
        "sound-v1",
        "decoder-v1",
        1,
        1,
        spans,
        SoundProfile.manifestSha256("org", "doc", generation, spans),
        SoundProfile.fingerprint(target, "sound-v1", "decoder-v1", 1),
        Instant.now().toString());
  }

  /**
   * Test-only downgrade refuses every nonempty new table, preserving real source and proof history.
   */
  static void restoreVersionEighteen(Path directory) throws SQLException {
    VideoAvMigrationTest.restoreVersionNineteen(directory);
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement()) {
      int version;
      try (var rows = statement.executeQuery("PRAGMA user_version")) {
        assertTrue(rows.next());
        version = rows.getInt(1);
      }
      if (version <= 18) {
        return;
      }
      assertEquals(19, version);
      for (String table : TABLES) {
        try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
          assertTrue(rows.next());
          assertEquals(0, rows.getInt(1), "Cannot discard sound source or proof history");
        }
      }
      statement.execute("PRAGMA foreign_keys=ON");
      connection.setAutoCommit(false);
      for (String table : TABLES) {
        statement.execute("DROP TABLE " + table);
      }
      statement.execute("UPDATE format_info SET version=18");
      statement.execute("PRAGMA user_version=18");
      connection.commit();
    }
  }
}
