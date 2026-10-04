package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.VideoAvTestFixture;
import com.evidence.rag.model.domain.AudioWaveform;
import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.model.domain.SyntheticDocument;
import com.evidence.rag.model.domain.VideoAvBuildClaim;
import com.evidence.rag.model.domain.VideoAvCompilation;
import com.evidence.rag.model.domain.VideoAvEpoch;
import com.evidence.rag.model.domain.VideoAvProfile;
import com.evidence.rag.model.domain.VideoAvPublication;
import com.evidence.rag.model.domain.VideoAvWindow;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VideoAvMigrationTest {
  private static final List<String> TABLES =
      List.of(
          "video_av_trace_evidence",
          "video_av_trace_documents",
          "video_av_traces",
          "video_av_windows",
          "video_av_publications",
          "video_av_originals");
  @TempDir Path directory;

  @Test
  void freshV20KeepsAllOldAuthorityAndAddsExactSixTablesAndDeferredChildren() {
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
  void v19UpgradeCreatesBackupWithoutChangingExistingMetadataOrSpeechState() throws Exception {
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            new ManagementRepository(store)
                .insertDocument(
                    VideoAvTestFixture.OWNER,
                    new SyntheticDocument(
                        "existing",
                        "old.txt",
                        "document",
                        "text/plain",
                        "old-rev",
                        "a".repeat(64),
                        1),
                    Instant.now().toString());
            return null;
          });
    }
    restoreVersionNineteen(directory);
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(25, store.count("PRAGMA user_version"));
            assertEquals(
                1,
                store.count(
                    "SELECT COUNT(*) FROM documents WHERE id='existing' AND active_revision_id='old-rev' AND source_sha256=?",
                    "a".repeat(64)));
            assertEquals(0, store.count("SELECT COUNT(*) FROM sound_publications"));
            return null;
          });
    }
    try (var files = Files.list(directory)) {
      assertTrue(
          files.anyMatch(
              path -> path.getFileName().toString().startsWith("java-library.v19-before-v20-")));
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
                    new VideoAvRepository(store).insertOriginal(original, Instant.now().toString());
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
      CleanupMaintenanceFixture.purge(store, VideoAvTestFixture.OWNER, original.documentId());
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
  void completeChildrenFirstPublicationSealsBothRoutesAndCannotBeModifiedOrAppended() {
    try (var store = new SqliteAuthorityStore(directory)) {
      register(store);
      var pub = VideoAvTestFixture.publication(VideoAvTestFixture.claim(true));
      store.transaction(
          () -> {
            new VideoAvRepository(store).insertPublication(pub);
            assertEquals(3, store.count("SELECT COUNT(*) FROM video_av_windows"));
            return null;
          });
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute("UPDATE video_av_windows SET end_tick=end_tick+1");
                    return null;
                  }));
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute("DELETE FROM video_av_publications");
                    return null;
                  }));
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute(
                        "INSERT INTO video_av_windows SELECT * FROM video_av_windows LIMIT 1");
                    return null;
                  }));
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute(
                        "INSERT OR REPLACE INTO video_av_publications SELECT * FROM video_av_publications");
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

  @Test
  void highLExactSqlSampleFloorSealsValidWindowsAndRejectsOneSampleOffset() {
    for (boolean wrong : List.of(false, true)) {
      try (var store = new SqliteAuthorityStore(directory.resolve(wrong ? "wrong" : "valid"))) {
        var original = register(store);
        long l = 34359738352000L, cut = 20602921602096000L;
        var epoch = new VideoAvEpoch(60000, 1, 2147483647L, l);
        var windows = new ArrayList<VideoAvWindow>();
        long start = 0;
        for (int i = 0; i < 21; i++) {
          long end = i < 19 ? (i + 1) * 30 * l : i == 19 ? cut : 600 * l;
          long first = epoch.sampleAt(start), last = epoch.sampleAt(end);
          windows.add(
              new VideoAvWindow(
                  VideoAvProfile.windowId(original.revisionId(), i),
                  i,
                  start,
                  end,
                  VideoAvTestFixture.clip(i, end - start),
                  new AudioWaveform(
                      original.sourceSha256(),
                      "decoder-v1",
                      first,
                      last,
                      new byte[Math.toIntExact((last - first) * 2)])));
          start = end;
        }
        var targets = VideoAvTestFixture.targets();
        var compilation =
            new VideoAvCompilation(
                original.sourceSha256(), "decoder-v1", epoch, 600 * l, true, windows);
        var claim =
            new VideoAvBuildClaim(
                VideoAvTestFixture.OWNER,
                original,
                targets,
                UUID.randomUUID().toString(),
                "analysis-v1",
                "decoder-v1",
                30,
                compilation,
                VideoAvProfile.fingerprint(targets, "analysis-v1", "decoder-v1", 30));
        var publication = VideoAvTestFixture.publication(claim);
        assertEquals(9593982, epoch.sampleAt(cut));
        if (wrong) {
          assertThrows(
              RuntimeException.class,
              () ->
                  store.transaction(
                      () -> {
                        insertHighL(store, publication, true);
                        return null;
                      }));
        } else {
          store.transaction(
              () -> {
                insertHighL(store, publication, false);
                assertEquals(
                    9593982,
                    store.count(
                        "SELECT audio_start_sample FROM video_av_windows WHERE ordinal=20"));
                assertEquals(
                    9593982,
                    store.count(
                        "SELECT start_tick/(ticks_per_second/16000) FROM video_av_windows JOIN video_av_publications ON publication_id=video_av_publications.id WHERE ordinal=20"));
                return null;
              });
        }
      }
    }
  }

  private static void insertHighL(SqliteAuthorityStore store, VideoAvPublication p, boolean wrong) {
    for (var w : p.windows()) {
      var v = w.video();
      var a = w.audio();
      store.execute(
          """
    INSERT INTO video_av_windows(publication_id,id,ordinal,start_tick,end_tick,clip_sha256,frame_count,frames_manifest_sha256,first_local_tick,end_local_tick,pcm_sha256,wav_sha256,audio_start_sample,audio_end_sample,sample_rate,visual_physical_id,visual_entry_sha256,audio_physical_id,audio_entry_sha256)
    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
    """,
          p.id(),
          w.id(),
          w.ordinal(),
          w.startTick(),
          w.endTick(),
          v.clipSha256(),
          v.frameCount(),
          v.framesManifestSha256(),
          v.firstLocalTick(),
          v.endLocalTick(),
          a.pcmSha256(),
          a.wavSha256(),
          a.startSample() + (wrong && w.ordinal() == 20 ? 1 : 0),
          a.endSample(),
          a.sampleRate(),
          w.visualPhysicalId(),
          w.visualEntrySha256(),
          w.audioPhysicalId(),
          w.audioEntrySha256());
    }
    var e = p.epoch();
    var v = p.visualTarget();
    var a = p.audioTarget();
    store.execute(
        """
   INSERT INTO video_av_publications(id,workspace_id,document_id,source_revision_id,source_sha256,filename,media_type,size_bytes,source_first_pts,source_time_base_num,source_time_base_den,ticks_per_second,duration_tick,has_audio,decoder_revision,analysis_model_revision,profile_fingerprint,visual_embedding_identity,visual_projection_identity,visual_model_revision,visual_dimensions,audio_embedding_identity,audio_projection_identity,audio_model_revision,audio_dimensions,chunk_seconds,window_count,window_manifest_sha256,visual_entry_count,visual_manifest_sha256,audio_entry_count,audio_manifest_sha256,created_at_ms)
   VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
   """,
        p.id(),
        p.workspaceId(),
        p.documentId(),
        p.sourceRevisionId(),
        p.sourceSha256(),
        p.filename(),
        p.mediaType(),
        p.sizeBytes(),
        e.sourceFirstPts(),
        e.sourceTimeBaseNumerator(),
        e.sourceTimeBaseDenominator(),
        e.ticksPerSecond(),
        p.durationTick(),
        1,
        p.decoderRevision(),
        p.analysisModelRevision(),
        p.profileFingerprint(),
        v.embeddingIdentity(),
        v.projectionIdentity(),
        v.modelRevision(),
        v.dimensions(),
        a.embeddingIdentity(),
        a.projectionIdentity(),
        a.modelRevision(),
        a.dimensions(),
        p.chunkSeconds(),
        p.windowCount(),
        p.windowManifestSha256(),
        p.videoWindowCount(),
        p.visualReceipt().manifestSha256(),
        p.audioWindowCount(),
        p.audioReceipt().manifestSha256(),
        p.createdAtMs());
  }

  private static DocumentOriginal register(SqliteAuthorityStore store) {
    var original = VideoAvTestFixture.original();
    return store.transaction(
        () -> {
          var management = new ManagementRepository(store);
          management.insertDocument(
              VideoAvTestFixture.OWNER,
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
          new VideoAvRepository(store).insertOriginal(original, Instant.now().toString());
          return original;
        });
  }

  /** Test-only downgrade never discards new source, publication, or trace data. */
  static void restoreVersionNineteen(Path directory) throws SQLException {
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement()) {
      int version;
      try (var rows = statement.executeQuery("PRAGMA user_version")) {
        assertTrue(rows.next());
        version = rows.getInt(1);
      }
      if (version <= 19) {
        return;
      }
      if (version == 24 || version == 25) {
        ReindexVersion23Fixture.restoreVersionTwentyThree(directory);
        version = 23;
      }
      if (version == 22 || version == 23) {
        CleanupV21Fixture.restoreVersionTwentyOne(directory);
        version = 21;
      }
      assertEquals(21, version);
      for (String table : TABLES) {
        try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
          assertTrue(rows.next());
          assertEquals(0, rows.getInt(1), "Cannot discard video audiovisual authority history");
        }
      }
      statement.execute("PRAGMA foreign_keys=ON");
      connection.setAutoCommit(false);
      for (String table : List.of("video_av_query_attachments", "video_av_query_preparations")) {
        try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
          assertTrue(rows.next());
          assertEquals(0, rows.getInt(1), "Cannot discard reference-query preparation history");
        }
        statement.execute("DROP TABLE " + table);
      }
      for (String table : TABLES) {
        statement.execute("DROP TABLE " + table);
      }
      statement.execute("UPDATE format_info SET version=19");
      statement.execute("PRAGMA user_version=19");
      connection.commit();
    }
  }
}
