package com.evidence.rag.support;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.model.domain.VideoCompilation;
import com.evidence.rag.model.domain.VideoSubtitleCompilation;
import com.evidence.rag.model.domain.VideoSubtitleCue;
import com.evidence.rag.model.domain.VideoSubtitleTrack;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SynopsisMaterialRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.IngestionService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.IntStream;

/** Synthetic complete compilation and real local authority/index publication, with no providers. */
public final class SubtitleCorpusFixture {
  public static final String TAIL = "末尾条件：未经确认不得上线，批准码TAIL-917。";

  private SubtitleCorpusFixture() {}

  public static PublicationVersion publish(PublishedCorpusFixture fixture, Actor actor, int cues) {
    var original = VideoSubtitleCompilationFixture.compilation(true);
    var packets =
        IntStream.range(0, cues)
            .mapToObj(
                i ->
                    new VideoSubtitleCue(
                        i,
                        2500 + i * 1000L,
                        750,
                        i == cues - 1 ? TAIL : "记录😀" + i,
                        "a".repeat(64)))
            .toList();
    var tracks = List.of(new VideoSubtitleTrack(1, "mov_text", 1, 1000, "zho", packets));
    var subtitles = new VideoSubtitleCompilation(2, 1, 1, tracks);
    var compilation =
        new VideoCompilation(
            original.sourceSha256(),
            original.decoderRevision(),
            original.compilerRevision(),
            original.timelineOriginUs(),
            Math.max(original.durationUs(), subtitles.endUs()),
            original.frames(),
            original.audio(),
            original.ocr(),
            subtitles);
    return publish(fixture, actor, compilation);
  }

  public static PublicationVersion publish(
      PublishedCorpusFixture fixture, Actor actor, VideoCompilation compilation) {
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
            compilation.compilerRevision(),
            compilation.ocr() != null);
    var task =
        ingestion.uploadDocument(
            actor, "subtitles.mp4", "video/mp4", VideoSubtitleCompilationFixture.ORIGINAL);
    var input = ingestion.claimIngestion(actor.workspaceId()).orElseThrow();
    assertTrue(ingestion.completeVideoIngestion(input, compilation));
    var indexing = fixture.authority.indexing();
    indexing.createIndexing(actor, task.documentId(), PublishedCorpusFixture.TARGET);
    var claim = indexing.claimIndexing(actor.workspaceId()).orElseThrow();
    var digests = new LinkedHashMap<String, String>();
    PublishedCorpusFixture.physicalIds(claim).forEach(id -> digests.put(id, "b".repeat(64)));
    var manifest =
        new RetrievalProjection.RevisionManifest(
            actor.workspaceId(), task.documentId(), claim.projectionGenerationId(), digests);
    assertTrue(
        indexing.completeIndexing(
            claim,
            digests,
            new VerifiedRevision(
                PublishedCorpusFixture.TARGET.projectionIdentity(),
                manifest.sha256(),
                digests.size())));
    return store.transaction(
        () ->
            new SynopsisMaterialRepository(store)
                .publication(actor, task.documentId())
                .orElseThrow());
  }
}
