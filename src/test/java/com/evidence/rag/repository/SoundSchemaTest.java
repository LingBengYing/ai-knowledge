package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.SyntheticDocument;
import com.evidence.rag.tool.parser.AudioPcm;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SoundSchemaTest {
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
  void freshLibraryHasAllSoundTablesGuardsAndDeferredChildren() {
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
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name LIKE 'sound_%'"));
            assertEquals(
                21, store.count("SELECT COUNT(*) FROM pragma_table_info('sound_publications')"));
            return null;
          });
    }
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
      String publication =
          store.transaction(
              () ->
                  LegacyMediaRows.soundPublication(
                      store, "org", original, "", Instant.now().toString()));
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
                    store.execute(
                        "INSERT INTO sound_spans(publication_id,id,ordinal,start_sample,end_sample,pcm_sha256,recall_text,physical_segment_id,entry_sha256) VALUES(?,?,?,?,?,?,?,?,?)",
                        publication,
                        "sound-" + "e".repeat(64),
                        1,
                        16000,
                        16001,
                        "b".repeat(64),
                        "",
                        "seg-" + "f".repeat(64),
                        "c".repeat(64));
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
          LegacyMediaRows.soundOriginal(store, original, Instant.now().toString());
          return null;
        });
    return original;
  }
}
