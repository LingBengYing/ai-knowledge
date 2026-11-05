package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.AudioTranscription;
import com.evidence.rag.model.domain.EvidenceScope;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.model.domain.VideoCompilation;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.IngestionService;
import com.evidence.rag.support.PublishedCorpusFixture;
import com.evidence.rag.support.VideoCompilationFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VideoOcrMigrationTest {
  @TempDir Path directory;

  @Test
  void versionElevenUpgradePreservesOldPublicationAndOneRestorableBackup() throws Exception {
    EvidenceScope expected;
    List<String> videoIds;
    try (var fixture = new PublishedCorpusFixture(directory)) {
      fixture.publish(VideoOcrRepositoryTest.OWNER, "A retained v11 document.");
      videoIds = publishVideo(fixture);
      expected = VideoOcrRepositoryTest.scope(fixture);
    }
    restoreVersionEleven(directory);
    for (int reopen = 0; reopen < 2; reopen++) {
      try (var fixture = new PublishedCorpusFixture(directory)) {
        assertEquals(expected, VideoOcrRepositoryTest.scope(fixture));
        fixture
            .authority
            .store()
            .transaction(
                () -> {
                  assertEquals(
                      HistoricalSchemaV25Fixture.CURRENT_VERSION,
                      fixture.authority.store().count("PRAGMA user_version"));
                  assertEquals(
                      4,
                      new EvidenceRepository(fixture.authority.store())
                          .findPublishedVideoCandidates(expected, videoIds)
                          .size());
                  assertEquals(
                      0,
                      fixture
                          .authority
                          .store()
                          .count("SELECT COUNT(*) FROM video_ocr_compilations"));
                  return null;
                });
      }
    }
    List<Path> backups;
    try (var files = Files.list(directory)) {
      backups =
          files
              .filter(
                  path ->
                      path.getFileName().toString().startsWith("java-library.v11-before-v12-")
                          && path.toString().endsWith(".db"))
              .toList();
    }
    assertEquals(1, backups.size());
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + backups.getFirst());
        var statement = connection.createStatement();
        var rows = statement.executeQuery("PRAGMA user_version")) {
      assertTrue(rows.next());
      assertEquals(11, rows.getInt(1));
    }
    Path restored = Files.createDirectory(directory.resolve("restored"));
    Files.copy(backups.getFirst(), restored.resolve("java-library.db"));
    try (var fixture = new PublishedCorpusFixture(restored)) {
      assertEquals(expected, VideoOcrRepositoryTest.scope(fixture));
    }
  }

  /** Only empty synthetic OCR fixtures may be downgraded for historical migration tests. */
  static void restoreVersionEleven(Path directory) throws SQLException {
    SynopsisMigrationTest.restoreVersionTwelve(directory);
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement()) {
      statement.execute("PRAGMA foreign_keys=ON");
      try (var version = statement.executeQuery("PRAGMA user_version")) {
        assertTrue(version.next());
        assertEquals(12, version.getInt(1));
      }
      var tables =
          List.of(
              "video_ocr_trace_evidence",
              "video_ocr_publication_entries",
              "video_ocr_regions",
              "video_ocr_segments",
              "video_frame_ocr",
              "video_ocr_compilations");
      for (String table : tables) {
        try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
          assertTrue(rows.next());
          assertEquals(0, rows.getInt(1), "Migration fixture must not discard OCR evidence");
        }
      }
      var names = new ArrayList<String>();
      var definitions = new ArrayList<String>();
      try (var rows =
          statement.executeQuery("SELECT name,sql FROM sqlite_master WHERE type='trigger'")) {
        while (rows.next()) {
          names.add(rows.getString(1));
          definitions.add(rows.getString(2));
        }
      }
      for (int i = 0; i < names.size(); i++) {
        String name = names.get(i);
        String original = definitions.get(i);
        if (name.startsWith("video_ocr_")
            || name.startsWith("video_frame_ocr_")
            || name.endsWith("_no_ocr_collision")
            || name.equals("corpus_revision_video_ocr_complete")
            || name.equals("index_publication_video_ocr_complete")) {
          statement.execute("DROP TRIGGER " + name);
          continue;
        }
        String restored =
            original
                .replace("GLOB 'java-video-compiler-v[12]:*'", "LIKE 'java-video-compiler-v1:%'")
                .replace("+(SELECT COUNT(*) FROM video_ocr_segments WHERE revision_id=r.id)", "")
                .replace(
                    "+(SELECT COUNT(*) FROM video_ocr_publication_entries WHERE publication_id=p.id)",
                    "")
                .replace(
                    "+(SELECT COUNT(*) FROM video_ocr_trace_evidence WHERE trace_id=NEW.id)", "")
                .replace(
                    " UNION ALL SELECT citation_ordinal FROM video_ocr_trace_evidence WHERE trace_id=NEW.id",
                    "");
        if (!restored.equals(original)) {
          statement.execute("DROP TRIGGER " + name);
          statement.execute(restored);
        }
      }
      for (String table : tables) statement.execute("DROP TABLE " + table);
      statement.execute("UPDATE format_info SET version=11");
      statement.execute("PRAGMA user_version=11");
    }
  }

  /** A complete text-only video publication (no OCR sidecar), as published before v12. */
  private static List<String> publishVideo(PublishedCorpusFixture fixture) {
    var owner = VideoOcrRepositoryTest.OWNER;
    var original = VideoOcrRepositoryTest.ORIGINAL;
    var template = VideoCompilationFixture.compilation(true);
    var store = fixture.authority.store();
    var ingestion =
        new IngestionService(
            store,
            new IngestionRepository(store),
            new ManagementRepository(store),
            new DocumentPermissionPolicy(),
            null,
            null,
            null,
            VideoCompilationFixture.COMPILER);
    var uploaded = ingestion.uploadDocument(owner, "recording.mp4", "video/mp4", original);
    var claim = ingestion.claimIngestion(owner.workspaceId()).orElseThrow();
    var audio = template.audio();
    var source = ModelValues.sha256(original);
    var compilation =
        new VideoCompilation(
            source,
            template.decoderRevision(),
            template.compilerRevision(),
            template.timelineOriginUs(),
            template.durationUs(),
            template.frames(),
            new AudioTranscription(
                source,
                audio.decoderRevision(),
                audio.modelRevision(),
                audio.transcriptionRevision(),
                audio.sampleCount(),
                audio.spans()));
    assertTrue(ingestion.completeVideoIngestion(claim, compilation));
    fixture
        .authority
        .indexing()
        .createIndexing(owner, uploaded.documentId(), PublishedCorpusFixture.TARGET);
    var index = fixture.authority.indexing().claimIndexing(owner.workspaceId()).orElseThrow();
    var ids = PublishedCorpusFixture.physicalIds(index);
    var digests = new LinkedHashMap<String, String>();
    ids.forEach(id -> digests.put(id, VideoOcrRepositoryTest.HASH));
    var manifest =
        new RetrievalProjection.RevisionManifest(
            owner.workspaceId(), uploaded.documentId(), index.projectionGenerationId(), digests);
    assertTrue(
        fixture
            .authority
            .indexing()
            .completeIndexing(
                index,
                digests,
                new VerifiedRevision(
                    PublishedCorpusFixture.TARGET.projectionIdentity(),
                    manifest.sha256(),
                    digests.size())));
    return ids;
  }
}
