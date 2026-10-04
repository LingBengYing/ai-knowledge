package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.EvidenceScope;
import com.evidence.rag.model.domain.ImageDimensions;
import com.evidence.rag.model.domain.ImageTextRegion;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.TraceDraft;
import com.evidence.rag.model.domain.TraceEvidence;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.model.domain.VideoCompilation;
import com.evidence.rag.model.domain.VideoFrameOcr;
import com.evidence.rag.model.domain.VideoOcrCompilation;
import com.evidence.rag.model.domain.VideoOcrSegment;
import com.evidence.rag.model.entity.TraceVideoOcrCitationEntity;
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
            assertEquals(25, store.count("PRAGMA user_version"));
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
            var publications = repository.findVideoOcrPublications(scope);
            assertEquals(1, publications.size());
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
            assertNull(
                repository.findVideoFrame(
                    new Actor("org", "other"), source.publication(), source.source().frameId()));
            assertEquals(
                List.of(), repository.findPublishedVideoOcrEvidence(scope, List.of("unknown")));
            return null;
          });
    }
  }

  @Test
  void ocrTraceSealsExactChunkAndFullScopeWithoutVideoJointProofAcrossReopen() {
    TraceVideoOcrCitationEntity expected;
    try (var fixture = new PublishedCorpusFixture(directory)) {
      fixture.publish(OWNER, "Unrelated document remains in the trace.");
      var ids = publish(fixture);
      var scope = scope(fixture);
      var store = fixture.authority.store();
      var repository = new EvidenceRepository(store);
      expected =
          store.transaction(
              () -> {
                var source = repository.findPublishedVideoOcrEvidence(scope, ids).getFirst();
                var trace =
                    new TraceEvidence(1, source.physicalSegmentId(), 0, 7, 0.8, 0.9, List.of(HASH));
                var citation =
                    new TraceVideoOcrCitationEntity(
                        trace,
                        source.publication().publicationId(),
                        source.source().id(),
                        source.source().frameId(),
                        source.publication().sourceSha256(),
                        source.frame().frameSha256(),
                        source.ocrManifestSha256(),
                        sha(source.frame().text()),
                        sha(source.frame().text()));
                var draft =
                    new TraceDraft(
                        HASH,
                        HASH,
                        "answered",
                        null,
                        "model",
                        "prompt",
                        "policy",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        null,
                        List.of(trace));
                repository.insertTrace(
                    "ocr-trace",
                    scope,
                    draft,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(citation),
                    NOW);
                assertEquals(
                    2, store.count("SELECT scope_count FROM query_traces WHERE id='ocr-trace'"));
                assertEquals(0, store.count("SELECT COUNT(*) FROM video_trace_proofs"));
                assertThrows(
                    RuntimeException.class,
                    () ->
                        store.execute(
                            "UPDATE video_ocr_trace_evidence SET quote_sha256=?", "b".repeat(64)));
                assertThrows(
                    RuntimeException.class, () -> store.execute("DELETE FROM video_frame_ocr"));
                assertNull(
                    repository.findTraceVideoOcrCitation(
                        new Actor("org", "other"), "ocr-trace", 1));
                return citation;
              });
    }
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(
                expected,
                new EvidenceRepository(store).findTraceVideoOcrCitation(OWNER, "ocr-trace", 1));
            assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
            return null;
          });
    }
  }

  @Test
  void ocrCitationCannotUseCaptionPhysicalIdentityOrEscapeItsChunk() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var ids = publish(fixture);
      var scope = scope(fixture);
      var store = fixture.authority.store();
      var repository = new EvidenceRepository(store);
      var source =
          store.transaction(() -> repository.findPublishedVideoOcrEvidence(scope, ids).getFirst());
      String captionId =
          ids.stream()
              .filter(id -> !id.equals(source.physicalSegmentId()))
              .findFirst()
              .orElseThrow();
      for (var locator :
          List.of(
              new TraceEvidence(1, captionId, 0, 7, 0.8, 0.9, List.of(HASH)),
              new TraceEvidence(1, source.physicalSegmentId(), 0, 8, 0.8, 0.9, List.of(HASH)))) {
        var citation =
            new TraceVideoOcrCitationEntity(
                locator,
                source.publication().publicationId(),
                source.source().id(),
                source.source().frameId(),
                source.publication().sourceSha256(),
                source.frame().frameSha256(),
                source.ocrManifestSha256(),
                sha(source.frame().text()),
                sha(source.frame().text()));
        var draft =
            new TraceDraft(
                HASH,
                HASH,
                "answered",
                null,
                "model",
                "prompt",
                "policy",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(locator));
        assertThrows(
            RuntimeException.class,
            () ->
                store.transaction(
                    () -> {
                      repository.insertTrace(
                          "invalid-ocr",
                          scope,
                          draft,
                          List.of(),
                          List.of(),
                          List.of(),
                          List.of(),
                          List.of(citation),
                          NOW);
                      return null;
                    }));
        store.transaction(
            () -> {
              assertEquals(
                  0,
                  store.count(
                      "SELECT COUNT(*) FROM query_trace_documents WHERE trace_id='invalid-ocr'"));
              assertEquals(
                  0,
                  store.count(
                      "SELECT COUNT(*) FROM video_ocr_trace_evidence WHERE trace_id='invalid-ocr'"));
              return null;
            });
      }
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

  static String sha(String text) {
    return ModelValues.sha256(text.getBytes(StandardCharsets.UTF_8));
  }
}
