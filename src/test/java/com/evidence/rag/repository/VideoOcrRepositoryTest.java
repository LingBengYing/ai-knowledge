package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.EvidenceScope;
import com.evidence.rag.model.domain.ImageDimensions;
import com.evidence.rag.model.domain.ImageTextRegion;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.model.domain.VideoCompilation;
import com.evidence.rag.model.domain.VideoFrameOcr;
import com.evidence.rag.model.domain.VideoOcrCompilation;
import com.evidence.rag.model.domain.VideoOcrSegment;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.IngestionService;
import com.evidence.rag.support.PublishedCorpusFixture;
import com.evidence.rag.support.VideoCompilationFixture;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VideoOcrRepositoryTest {
  static final Actor OWNER = new Actor("org", "owner");
  static final String HASH = "a".repeat(64);
  static final String COMPILER = "java-video-compiler-v2:" + "d".repeat(64);
  static final byte[] ORIGINAL = "0000ftypisom00000000".getBytes(StandardCharsets.UTF_8);
  static final String NOW = "2026-09-20T08:00:00Z";
  @TempDir Path directory;

  @Test
  void versionTwelveHasIndependentOcrSidecarAndRealPublicationForeignKeys() {
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(
                HistoricalSchemaV25Fixture.CURRENT_VERSION, store.count("PRAGMA user_version"));
            assertEquals(
                6,
                store.count(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('video_ocr_compilations','video_frame_ocr','video_ocr_segments','video_ocr_regions','video_ocr_publication_entries','video_ocr_trace_evidence')"));
            assertTrue(
                store.count(
                        "SELECT COUNT(*) FROM pragma_foreign_key_list('video_ocr_trace_evidence') WHERE \"table\"='video_ocr_publication_entries'")
                    > 0);
            return null;
          });
    }
  }

  @Test
  void completeOcrRoundTripsEmptyFrameAndAddsOnlyNonemptyChunksToPublicationAndScope() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      fixture.publish(OWNER, "Full selected scope contains this unrelated document.");
      var ids = publish(fixture);
      var scope = scope(fixture);
      assertEquals(2, scope.publications().size());
      var store = fixture.authority.store();
      var repository = new EvidenceRepository(store);
      store.transaction(
          () -> {
            assertEquals(2, store.count("SELECT projection_count FROM video_compilations"));
            assertEquals(1, store.count("SELECT projection_count FROM video_ocr_compilations"));
            assertEquals(3, ids.size());
            var source = repository.findPublishedVideoOcrEvidence(scope, ids).getFirst();
            assertEquals("预算42万元。", source.frame().text());
            assertEquals(0, source.source().segment().start());
            assertEquals(7, source.source().segment().end());
            assertEquals(0, source.framePresentationUs());
            assertEquals(200_000, source.frameDurationUs());
            assertEquals(List.of(new ImageTextRegion(0, 7, 0, 0, 2, 2)), source.frame().regions());
            assertNotEquals(source.source().id(), source.physicalSegmentId());
            assertEquals(2, repository.findPublishedVideoCandidates(scope, ids).size());
            var compilation =
                new IngestionRepository(store)
                    .findVideoCompilation(source.publication().sourceRevisionId())
                    .orElseThrow();
            assertEquals("", compilation.ocr().frames().get(1).text());
            assertEquals(List.of(), compilation.ocr().frames().get(1).segments());
            var frame =
                repository.findVideoFrame(OWNER, source.publication(), source.source().frameId());
            assertArrayEquals(VideoCompilationFixture.image().content(), frame.image().content());
            assertEquals(
                List.of(), repository.findPublishedVideoOcrEvidence(scope, List.of("unknown")));
            return null;
          });
    }
  }

  static List<String> publish(PublishedCorpusFixture fixture) {
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
            COMPILER);
    var uploaded = ingestion.uploadDocument(OWNER, "screen.mp4", "video/mp4", ORIGINAL);
    var claim = ingestion.claimIngestion(OWNER.workspaceId()).orElseThrow();
    var frames =
        List.of(
            VideoCompilationFixture.frame(0, 0, 200_000),
            VideoCompilationFixture.frame(1, 1_200_000, 200_000));
    var dimensions = new ImageDimensions(2, 2);
    var ocr =
        new VideoOcrCompilation(
            "ocr-v1",
            List.of(
                new VideoFrameOcr(
                    0,
                    frames.getFirst().frame().image().sha256(),
                    dimensions,
                    "预算42万元。",
                    List.of(new VideoOcrSegment(0, 0, 7, "预算42万元。")),
                    List.of(new ImageTextRegion(0, 7, 0, 0, 2, 2))),
                new VideoFrameOcr(
                    1,
                    frames.get(1).frame().image().sha256(),
                    dimensions,
                    "",
                    List.of(),
                    List.of())));
    var compilation =
        new VideoCompilation(
            ModelValues.sha256(ORIGINAL), "decoder-v1", COMPILER, 0, 1_400_000, frames, null, ocr);
    assertTrue(ingestion.completeVideoIngestion(claim, compilation));
    fixture
        .authority
        .indexing()
        .createIndexing(OWNER, uploaded.documentId(), PublishedCorpusFixture.TARGET);
    var index = fixture.authority.indexing().claimIndexing(OWNER.workspaceId()).orElseThrow();
    var ids = PublishedCorpusFixture.physicalIds(index);
    var digests = new LinkedHashMap<String, String>();
    ids.forEach(id -> digests.put(id, HASH));
    var manifest =
        new RetrievalProjection.RevisionManifest(
            OWNER.workspaceId(), uploaded.documentId(), index.projectionGenerationId(), digests);
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

  static EvidenceScope scope(PublishedCorpusFixture fixture) {
    return fixture.evidence.snapshot(
        OWNER, DocumentSelection.allDocuments(), PublishedCorpusFixture.TARGET);
  }
}
