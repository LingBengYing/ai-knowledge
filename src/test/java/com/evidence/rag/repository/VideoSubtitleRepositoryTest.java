package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.model.domain.VideoCompilation;
import com.evidence.rag.model.domain.VideoSubtitleEvidence;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.IngestionService;
import com.evidence.rag.support.PublishedCorpusFixture;
import com.evidence.rag.support.VideoSubtitleCompilationFixture;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class VideoSubtitleRepositoryTest {
  private static final Actor OWNER = new Actor("org", "owner");
  private static final String HASH = "a".repeat(64);
  @TempDir Path directory;

  @Test
  void completeSubtitleProductRoundTripsAllTracksClearPacketsAndOcrSealAcrossRestart() {
    String revision;
    var expected = VideoSubtitleCompilationFixture.compilation(true);
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var created = ingest(fixture, expected);
      revision = created[1];
      var store = fixture.authority.store();
      store.transaction(
          () -> {
            var restored =
                IngestionRepository.readVideoCompilation(store, created[1]).orElseThrow();
            assertEquals(expected.subtitles(), restored.subtitles());
            assertEquals(expected.ocr(), restored.ocr());
            assertEquals(
                VideoSubtitleEvidence.fromCompilation(created[1], expected),
                IngestionRepository.readVideoSubtitleEvidence(store, created[1]).orElseThrow());
            assertEquals(1, store.count("SELECT ocr_expected FROM video_subtitle_compilations"));
            assertEquals(2, store.count("SELECT COUNT(*) FROM video_subtitle_tracks"));
            assertEquals(5, store.count("SELECT COUNT(*) FROM video_subtitle_cues"));
            assertEquals(
                2,
                store.count(
                    "SELECT COUNT(*) FROM video_subtitle_cues WHERE index_ordinal IS NULL AND start_us IS NULL AND end_us IS NULL"));
            assertEquals(2, store.count("SELECT projection_count FROM video_compilations"));
            assertEquals(
                3, store.count("SELECT projection_count FROM video_subtitle_compilations"));
            assertThrows(
                RuntimeException.class,
                () -> store.execute("UPDATE video_subtitle_cues SET text='changed'"));
            assertThrows(
                RuntimeException.class, () -> store.execute("DELETE FROM video_subtitle_tracks"));
            return null;
          });
    }
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(
                expected.subtitles(),
                IngestionRepository.readVideoCompilation(store, revision)
                    .orElseThrow()
                    .subtitles());
            assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
            return null;
          });
    }
  }

  @Test
  void indexClaimAndPublicationIncludeEveryNonblankSubtitleAfterBaseAndOcr() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var created = ingest(fixture, VideoSubtitleCompilationFixture.compilation(true));
      fixture.authority.indexing().createIndexing(OWNER, created[0], PublishedCorpusFixture.TARGET);
      var claim = fixture.authority.indexing().claimIndexing(OWNER.workspaceId()).orElseThrow();
      assertEquals(6, claim.items().size());
      assertEquals(
          List.of("屏幕文字", "预算😀42万元。", "补充条件：须经审批。", "English only"),
          claim.items().subList(2, 6).stream().map(item -> item.recallText()).toList());
      var physical = PublishedCorpusFixture.physicalIds(claim);
      var digests = new LinkedHashMap<String, String>();
      physical.forEach(id -> digests.put(id, HASH));
      var manifest =
          new RetrievalProjection.RevisionManifest(
              OWNER.workspaceId(), created[0], claim.projectionGenerationId(), digests);
      assertTrue(
          fixture
              .authority
              .indexing()
              .completeIndexing(
                  claim,
                  digests,
                  new VerifiedRevision(
                      PublishedCorpusFixture.TARGET.projectionIdentity(),
                      manifest.sha256(),
                      digests.size())));
      fixture
          .authority
          .store()
          .transaction(
              () -> {
                var store = fixture.authority.store();
                assertEquals(6, store.count("SELECT segment_count FROM index_publications"));
                assertEquals(
                    3, store.count("SELECT COUNT(*) FROM video_subtitle_publication_entries"));
                assertEquals(
                    0,
                    store.count(
                        "SELECT COUNT(*) FROM video_subtitle_publication_entries e JOIN video_subtitle_cues c ON c.id=e.video_subtitle_cue_id WHERE c.index_ordinal IS NULL"));
                assertNotEquals(claim.items().getLast().evidenceId(), physical.getLast());
                return null;
              });
    }
  }

  @Test
  void completeNoTrackProductStillPersistsHeaderAndCannotBeConfusedWithLegacyNull() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var created = ingest(fixture, VideoSubtitleCompilationFixture.compilation(false, List.of()));
      fixture
          .authority
          .store()
          .transaction(
              () -> {
                var store = fixture.authority.store();
                assertEquals(1, store.count("SELECT COUNT(*) FROM video_subtitle_compilations"));
                assertEquals(
                    0, store.count("SELECT ocr_expected FROM video_subtitle_compilations"));
                assertEquals(0, store.count("SELECT COUNT(*) FROM video_subtitle_tracks"));
                assertEquals(
                    List.of(),
                    IngestionRepository.readVideoCompilation(store, created[1])
                        .orElseThrow()
                        .subtitles()
                        .tracks());
                assertEquals(
                    0,
                    IngestionRepository.readVideoSubtitleEvidence(store, created[1])
                        .orElseThrow()
                        .projectionCount());
                assertTrue(
                    IngestionRepository.readVideoSubtitleEvidence(store, "missing").isEmpty());
                return null;
              });
    }
  }

  @ParameterizedTest
  @CsvSource({
    "video_subtitle_compilations,1=1",
    "video_subtitle_cues,NEW.stream_index=3",
    "video_frame_ocr,NEW.ordinal=1"
  })
  void omittedSubtitleHeaderTailPacketOrExpectedOcrCannotCommitPartialAuthority(
      String table, String predicate) {
    try (var fixture = new PublishedCorpusFixture(directory)) {
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
              VideoSubtitleCompilationFixture.COMPILER,
              true);
      var uploaded =
          ingestion.uploadDocument(
              OWNER, "subtitles.mp4", "video/mp4", VideoSubtitleCompilationFixture.ORIGINAL);
      var claim = ingestion.claimIngestion(OWNER.workspaceId()).orElseThrow();
      store.transaction(
          () -> {
            store.execute(
                "CREATE TRIGGER synthetic_omitted_material BEFORE INSERT ON "
                    + table
                    + " WHEN "
                    + predicate
                    + " BEGIN SELECT RAISE(IGNORE); END");
            return null;
          });
      assertThrows(
          RuntimeException.class,
          () ->
              ingestion.completeVideoIngestion(
                  claim, VideoSubtitleCompilationFixture.compilation(true)));
      assertEquals("processing", ingestion.ingestionStatus(OWNER, uploaded.taskId()).state());
      store.transaction(
          () -> {
            assertEquals(0, store.count("SELECT COUNT(*) FROM video_compilations"));
            assertEquals(0, store.count("SELECT COUNT(*) FROM video_subtitle_compilations"));
            return null;
          });
    }
  }

  private static String[] ingest(PublishedCorpusFixture fixture, VideoCompilation compilation) {
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
            VideoSubtitleCompilationFixture.COMPILER,
            compilation.ocr() != null);
    var uploaded =
        ingestion.uploadDocument(
            OWNER, "subtitles.mp4", "video/mp4", VideoSubtitleCompilationFixture.ORIGINAL);
    var claim = ingestion.claimIngestion(OWNER.workspaceId()).orElseThrow();
    assertTrue(ingestion.completeVideoIngestion(claim, compilation));
    return new String[] {uploaded.documentId(), claim.revisionId()};
  }
}
