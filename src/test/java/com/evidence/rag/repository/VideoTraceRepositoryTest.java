package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AudioTranscriptSpan;
import com.evidence.rag.model.domain.AudioTranscription;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.EvidenceScope;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.TraceDraft;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.model.domain.VideoAssessment;
import com.evidence.rag.model.domain.VideoCompilation;
import com.evidence.rag.model.domain.VideoSourceEvidence;
import com.evidence.rag.model.domain.VideoTraceEvidence;
import com.evidence.rag.model.domain.VideoTraceFact;
import com.evidence.rag.model.domain.VideoTraceProof;
import com.evidence.rag.model.entity.TraceVideoCitationEntity;
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

class VideoTraceRepositoryTest {
  private static final Actor OWNER = new Actor("org", "owner");
  private static final String HASH = "a".repeat(64);
  private static final String OTHER = "b".repeat(64);
  private static final String NOW = "2026-09-20T08:00:00Z";
  private static final byte[] ORIGINAL = "0000ftypisom00000000".getBytes(StandardCharsets.UTF_8);
  @TempDir Path directory;

  @Test
  void versionElevenAddsHashOnlyProofFactsAndRealPublicationForeignKeys() {
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(16, store.count("PRAGMA user_version"));
            assertEquals(16, store.count("SELECT version FROM format_info"));
            assertEquals(
                3,
                store.count(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('video_trace_proofs','video_trace_facts','video_trace_evidence')"));
            assertEquals(
                4,
                store.count(
                    "SELECT COUNT(*) FROM pragma_foreign_key_list('video_trace_evidence') WHERE \"table\" IN ('video_frame_publication_entries','video_transcript_publication_entries')"));
            assertEquals(
                0,
                store.count(
                    "SELECT COUNT(*) FROM pragma_table_info('video_trace_facts') WHERE name IN ('question','requirement','answer','quote')"));
            return null;
          });
    }
  }

  @Test
  void fullScopeHydratesOnlySelectedOriginalFrameAndCompleteTranscriptWithRealPhysicalIds() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      fixture.publish(OWNER, "Other document remains in the answer snapshot.");
      var ids = publishVideo(fixture);
      var scope = scope(fixture);
      assertEquals(2, scope.publications().size());
      var repository = new EvidenceRepository(fixture.authority.store());
      fixture
          .authority
          .store()
          .transaction(
              () -> {
                assertEquals(1, repository.findVideoPublications(scope).size());
                var candidates = repository.findPublishedVideoCandidates(scope, ids);
                assertEquals(4, candidates.size());
                var visual =
                    candidates.stream()
                        .filter(
                            c ->
                                c.kind() == VideoTraceEvidence.Kind.VISUAL
                                    && c.recallText().equals("合成蓝色区域 0"))
                        .findFirst()
                        .orElseThrow();
                var groups =
                    repository.findPublishedVideoGroups(scope, List.of(visual.physicalSegmentId()));
                assertEquals(1, groups.size());
                var group = groups.getFirst();
                var actual =
                    repository.findPublishedVideoEvidence(
                        scope, group.publication().publicationId(), group.group().id());
                assertEquals(group, actual.source());
                assertArrayEquals(
                    VideoCompilationFixture.image().content(),
                    actual.proofInput().frame().image().content());
                assertEquals(
                    "蓝色圆形。\n\u2003\n预算42万元。", actual.proofInput().transcript().contextText());
                assertEquals(
                    "video-transcript:" + group.group().revisionId(),
                    actual.proofInput().transcript().contextId());
                assertEquals(0, actual.transcriptSpan().span().startMs());
                assertEquals(1000, actual.transcriptSpan().span().endMs());
                assertEquals(
                    group.group().transcriptSpanId(),
                    actual.proofInput().transcript().physicalId());
                assertNotEquals(
                    group.group().transcriptSpanId(), group.transcriptPhysicalSegmentId());
                assertEquals(group.framePhysicalSegmentId(), visual.physicalSegmentId());
                var blankVisual =
                    candidates.stream()
                        .filter(
                            c ->
                                c.kind() == VideoTraceEvidence.Kind.VISUAL
                                    && c.recallText().equals("合成蓝色区域 1"))
                        .findFirst()
                        .orElseThrow();
                var blankGroup =
                    repository
                        .findPublishedVideoGroups(scope, List.of(blankVisual.physicalSegmentId()))
                        .getFirst();
                var blankSource =
                    repository.findPublishedVideoEvidence(
                        scope, blankGroup.publication().publicationId(), blankGroup.group().id());
                assertNull(blankSource.proofInput().transcript());
                assertNull(blankSource.source().transcriptPhysicalSegmentId());
                assertEquals("\u2003", blankSource.transcriptSpan().span().text());
                assertEquals(1000, blankSource.transcriptSpan().span().startMs());
                assertEquals(2000, blankSource.transcriptSpan().span().endMs());
                assertArrayEquals(
                    ORIGINAL,
                    repository.findVideoOriginal(OWNER, group.publication(), ORIGINAL.length));
                assertNull(
                    repository.findVideoOriginal(OWNER, group.publication(), ORIGINAL.length - 1));
                assertTrue(
                    repository.findPublishedVideoGroups(scope, List.of("missing")).isEmpty());
                assertNull(
                    repository.findPublishedVideoEvidence(
                        scope, group.publication().publicationId(), "missing"));
                return null;
              });
    }
  }

  @Test
  void jointTraceSealsFullScopeBothModalitiesAndOrderedHashOnlyFactsAcrossReopen() {
    VideoTraceProof expectedProof;
    List<TraceVideoCitationEntity> expected;
    EvidenceScope snapshot;
    try (var fixture = new PublishedCorpusFixture(directory)) {
      fixture.publish(OWNER, "Unrelated snapshot member is retained.");
      var ids = publishVideo(fixture);
      snapshot = scope(fixture);
      var repository = new EvidenceRepository(fixture.authority.store());
      var group =
          fixture
              .authority
              .store()
              .transaction(
                  () ->
                      repository.findPublishedVideoGroups(snapshot, ids).stream()
                          .filter(
                              g ->
                                  g.group().frameId() != null
                                      && g.transcriptPhysicalSegmentId() != null)
                          .findFirst()
                          .orElseThrow());
      var source =
          fixture
              .authority
              .store()
              .transaction(
                  () ->
                      repository.findPublishedVideoEvidence(
                          snapshot, group.publication().publicationId(), group.group().id()));
      var visual =
          new VideoTraceEvidence(
              1,
              VideoTraceEvidence.Kind.VISUAL,
              group.framePhysicalSegmentId(),
              null,
              null,
              0.8,
              0.9,
              HASH);
      var transcript =
          new VideoTraceEvidence(
              2,
              VideoTraceEvidence.Kind.TRANSCRIPT,
              group.transcriptPhysicalSegmentId(),
              0,
              5,
              0.7,
              0.85,
              OTHER);
      expectedProof =
          new VideoTraceProof(
              group.publication().publicationId(),
              group.group().id(),
              VideoAssessment.Mode.JOINT,
              List.of(new VideoTraceFact(0, HASH, 1, 0), new VideoTraceFact(1, OTHER, 0, 1)),
              "text-v1",
              "vision-v1",
              "policy-v1");
      expected =
          List.of(
              new TraceVideoCitationEntity(
                  visual,
                  group.publication().publicationId(),
                  group.group().id(),
                  group.group().frameId(),
                  null,
                  group.publication().sourceSha256(),
                  group.manifestSha256(),
                  source.proofInput().frame().image().sha256(),
                  null,
                  null),
              new TraceVideoCitationEntity(
                  transcript,
                  group.publication().publicationId(),
                  group.group().id(),
                  null,
                  group.group().transcriptSpanId(),
                  group.publication().sourceSha256(),
                  group.manifestSha256(),
                  null,
                  sha("蓝色圆形。"),
                  sha("蓝色圆形。")));
      var draft =
          new TraceDraft(
              HASH,
              OTHER,
              "answered",
              null,
              "text-v1",
              "prompt-v1",
              "policy-v1",
              List.of(),
              List.of(),
              List.of(),
              List.of(visual, transcript),
              expectedProof);
      fixture
          .authority
          .store()
          .transaction(
              () -> {
                repository.insertTrace(
                    "video-trace", snapshot, draft, List.of(), List.of(), List.of(), expected, NOW);
                return null;
              });
      assertEquals("蓝色圆形。", new VideoSourceEvidence(source, transcript).quote());
    }
    try (var store = new SqliteAuthorityStore(directory)) {
      var repository = new EvidenceRepository(store);
      store.transaction(
          () -> {
            assertEquals(snapshot, repository.findTraceScope(OWNER, "video-trace"));
            assertEquals(expectedProof, repository.findTraceVideoProof(OWNER, "video-trace"));
            assertEquals(
                expected.getFirst(), repository.findTraceVideoCitation(OWNER, "video-trace", 1));
            assertEquals(
                expected.getLast(), repository.findTraceVideoCitation(OWNER, "video-trace", 2));
            assertNull(repository.findTraceVideoProof(new Actor("org", "other"), "video-trace"));
            assertNull(repository.findTraceVideoCitation(OWNER, "video-trace", 3));
            assertEquals(
                2,
                store.count(
                    "SELECT COUNT(*) FROM query_trace_documents WHERE trace_id='video-trace'"));
            assertThrows(
                RuntimeException.class,
                () ->
                    store.execute(
                        "UPDATE video_trace_facts SET visual_support=0 WHERE trace_id='video-trace'"));
            assertThrows(
                RuntimeException.class,
                () ->
                    store.execute("DELETE FROM video_trace_evidence WHERE trace_id='video-trace'"));
            return null;
          });
    }
  }

  @Test
  void realTranscriptSpanIdentityCannotMasqueradeAsItsPublishedPhysicalIdentity() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var ids = publishVideo(fixture);
      var snapshot = scope(fixture);
      var store = fixture.authority.store();
      var repository = new EvidenceRepository(store);
      var source =
          store.transaction(
              () -> {
                var group =
                    repository.findPublishedVideoGroups(snapshot, ids).stream()
                        .filter(
                            g ->
                                g.group().frameId() != null
                                    && g.transcriptPhysicalSegmentId() != null)
                        .findFirst()
                        .orElseThrow();
                return repository.findPublishedVideoEvidence(
                    snapshot, group.publication().publicationId(), group.group().id());
              });
      var group = source.source();
      var locator =
          new VideoTraceEvidence(
              1,
              VideoTraceEvidence.Kind.TRANSCRIPT,
              group.group().transcriptSpanId(),
              0,
              5,
              0.8,
              0.9,
              HASH);
      var proof =
          new VideoTraceProof(
              group.publication().publicationId(),
              group.group().id(),
              VideoAssessment.Mode.TRANSCRIPT,
              List.of(new VideoTraceFact(0, HASH, 0, 1)),
              "text-v1",
              "vision-v1",
              "policy-v1");
      var draft =
          new TraceDraft(
              HASH,
              OTHER,
              "answered",
              null,
              "text-v1",
              "prompt-v1",
              "policy-v1",
              List.of(),
              List.of(),
              List.of(),
              List.of(locator),
              proof);
      var citation =
          new TraceVideoCitationEntity(
              locator,
              group.publication().publicationId(),
              group.group().id(),
              null,
              group.group().transcriptSpanId(),
              group.publication().sourceSha256(),
              group.manifestSha256(),
              null,
              sha("蓝色圆形。"),
              sha("蓝色圆形。"));
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    repository.insertTrace(
                        "invalid-physical",
                        snapshot,
                        draft,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(citation),
                        NOW);
                    return null;
                  }));
      assertNull(store.transaction(() -> repository.findTraceScope(OWNER, "invalid-physical")));
    }
  }

  @Test
  void leadingEmptySpanUsesExistingVideoContextOffsetsAndSealsTheExactExcerpt() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var template = VideoCompilationFixture.compilation(true);
      var audio = template.audio();
      var compilation =
          new VideoCompilation(
              template.sourceSha256(),
              template.decoderRevision(),
              template.compilerRevision(),
              template.timelineOriginUs(),
              template.durationUs(),
              template.frames(),
              new AudioTranscription(
                  template.sourceSha256(),
                  audio.decoderRevision(),
                  audio.modelRevision(),
                  audio.transcriptionRevision(),
                  audio.sampleCount(),
                  List.of(
                      new AudioTranscriptSpan(0, 0, 1000, ""),
                      new AudioTranscriptSpan(1, 1000, 2000, "预算42万元。"),
                      new AudioTranscriptSpan(2, 2000, 4000, ""))));
      var ids = publishVideo(fixture, compilation);
      var snapshot = scope(fixture);
      var store = fixture.authority.store();
      var repository = new EvidenceRepository(store);
      store.transaction(
          () -> {
            var group =
                repository.findPublishedVideoGroups(snapshot, ids).stream()
                    .filter(g -> g.transcriptPhysicalSegmentId() != null)
                    .findFirst()
                    .orElseThrow();
            var source =
                repository.findPublishedVideoEvidence(
                    snapshot, group.publication().publicationId(), group.group().id());
            var transcript = source.proofInput().transcript();
            assertEquals("预算42万元。\n", transcript.contextText());
            assertEquals(sha("预算42万元。\n"), transcript.contextSha256());
            assertEquals(0, transcript.startCodePoint());
            assertEquals(7, transcript.endCodePoint());
            var locator =
                new VideoTraceEvidence(
                    1,
                    VideoTraceEvidence.Kind.TRANSCRIPT,
                    group.transcriptPhysicalSegmentId(),
                    0,
                    7,
                    0.8,
                    0.9,
                    HASH);
            var proof =
                new VideoTraceProof(
                    group.publication().publicationId(),
                    group.group().id(),
                    VideoAssessment.Mode.TRANSCRIPT,
                    List.of(new VideoTraceFact(0, HASH, 0, 1)),
                    "text-v1",
                    "vision-v1",
                    "policy-v1");
            var draft =
                new TraceDraft(
                    HASH,
                    OTHER,
                    "answered",
                    null,
                    "text-v1",
                    "prompt-v1",
                    "policy-v1",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(locator),
                    proof);
            var citation =
                new TraceVideoCitationEntity(
                    locator,
                    group.publication().publicationId(),
                    group.group().id(),
                    null,
                    group.group().transcriptSpanId(),
                    group.publication().sourceSha256(),
                    group.manifestSha256(),
                    null,
                    sha("预算42万元。"),
                    sha("预算42万元。"));
            repository.insertTrace(
                "leading-empty",
                snapshot,
                draft,
                List.of(),
                List.of(),
                List.of(),
                List.of(citation),
                NOW);
            assertEquals(citation, repository.findTraceVideoCitation(OWNER, "leading-empty", 1));
            assertEquals("预算42万元。", new VideoSourceEvidence(source, locator).quote());
            return null;
          });
    }
  }

  static List<String> publishVideo(PublishedCorpusFixture fixture) {
    return publishVideo(fixture, VideoCompilationFixture.compilation(true));
  }

  private static List<String> publishVideo(
      PublishedCorpusFixture fixture, VideoCompilation template) {
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
    var uploaded = ingestion.uploadDocument(OWNER, "recording.mp4", "video/mp4", ORIGINAL);
    var claim = ingestion.claimIngestion(OWNER.workspaceId()).orElseThrow();
    var audio = template.audio();
    var source = ModelValues.sha256(ORIGINAL);
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

  private static EvidenceScope scope(PublishedCorpusFixture fixture) {
    return fixture.evidence.snapshot(
        OWNER, DocumentSelection.allDocuments(), PublishedCorpusFixture.TARGET);
  }

  private static String sha(String value) {
    return ModelValues.sha256(value.getBytes(StandardCharsets.UTF_8));
  }
}
