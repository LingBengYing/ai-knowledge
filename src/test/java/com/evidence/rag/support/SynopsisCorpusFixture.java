package com.evidence.rag.support;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AudioCompilation;
import com.evidence.rag.model.domain.AudioTranscriptSpan;
import com.evidence.rag.model.domain.AudioTranscription;
import com.evidence.rag.model.domain.ImageDimensions;
import com.evidence.rag.model.domain.ImageOcrOptions;
import com.evidence.rag.model.domain.ImageRecall;
import com.evidence.rag.model.domain.ImageTextRegion;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.ParsedImage;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.model.domain.VideoCompilation;
import com.evidence.rag.model.domain.VideoFrameOcr;
import com.evidence.rag.model.domain.VideoOcrCompilation;
import com.evidence.rag.model.domain.VideoOcrSegment;
import com.evidence.rag.model.domain.VisualIngestionOptions;
import com.evidence.rag.repository.IndexingRepository;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.repository.SynopsisMaterialRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.IndexingService;
import com.evidence.rag.service.IngestionService;
import com.evidence.rag.tool.parser.AudioPcm;
import com.evidence.rag.tool.parser.TextParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Shared real-publication fixture; synthetic compilations are not decoder or model quality proof.
 */
public final class SynopsisCorpusFixture {
  public static final byte[] ORIGINAL_VIDEO =
      new byte[] {0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 0};
  private static final String AUDIO = "java-audio-compiler-v1:" + "a".repeat(64);
  private static final String VIDEO = "java-video-compiler-v2:" + "b".repeat(64);
  private final SqliteAuthorityStore store;
  private final Actor actor;
  private final IndexTarget target;

  public SynopsisCorpusFixture(SqliteAuthorityStore store, Actor actor, IndexTarget target) {
    this.store = store;
    this.actor = actor;
    this.target = target;
  }

  public PublicationVersion text(String text) {
    var ingestion = ingestion(null, null, null, null);
    var upload =
        ingestion.uploadDocument(
            actor, "source.txt", "text/plain", text.getBytes(StandardCharsets.UTF_8));
    var claim = ingestion.claimIngestion(actor.workspaceId()).orElseThrow();
    assertTrue(
        ingestion.completeIngestion(
            claim, new TextParser().parse(claim.filename(), claim.mimeType(), claim.content())));
    return index(upload.documentId());
  }

  public PublicationVersion image(boolean ocr) {
    var ingestion =
        ingestion(
            ocr ? new ImageOcrOptions(Path.of("/synthetic/tesseract"), "eng", "5.5.3") : null,
            ocr ? null : new VisualIngestionOptions("vision-v1"),
            null,
            null);
    var upload =
        ingestion.uploadDocument(
            actor, "source.png", "image/png", VideoCompilationFixture.image().content());
    var claim = ingestion.claimIngestion(actor.workspaceId()).orElseThrow();
    assertTrue(
        ocr
            ? ingestion.completeImageIngestion(
                claim,
                new ParsedImage(
                    new TextParser()
                        .parse(
                            "ocr.txt", "text/plain", "Budget42".getBytes(StandardCharsets.UTF_8)),
                    new ImageDimensions(2, 2),
                    List.of(new ImageTextRegion(0, 8, 0, 0, 2, 2))))
            : ingestion.completeVisualIngestion(
                claim, new ImageRecall("Misleading caption 999", "vision-v1")));
    return index(upload.documentId());
  }

  public PublicationVersion audio(List<String> texts) {
    var ingestion = ingestion(null, null, AUDIO, null);
    byte[] pcm = new byte[texts.size() * 32000];
    byte[] original = AudioPcm.wav(pcm, 0, pcm.length);
    var upload = ingestion.uploadDocument(actor, "source.wav", "audio/wav", original);
    var claim = ingestion.claimIngestion(actor.workspaceId()).orElseThrow();
    var spans =
        IntStream.range(0, texts.size())
            .mapToObj(i -> new AudioTranscriptSpan(i, i * 1000L, (i + 1) * 1000L, texts.get(i)))
            .toList();
    assertTrue(
        ingestion.completeAudioIngestion(
            claim,
            new AudioCompilation(
                ModelValues.sha256(original),
                "decoder-v1",
                "asr-v1",
                AUDIO,
                texts.size() * 1000L,
                spans)));
    return index(upload.documentId());
  }

  public PublicationVersion video(int count) {
    var ingestion = ingestion(null, null, null, VIDEO);
    var upload = ingestion.uploadDocument(actor, "source.mp4", "video/mp4", ORIGINAL_VIDEO);
    var claim = ingestion.claimIngestion(actor.workspaceId()).orElseThrow();
    var frames =
        IntStream.range(0, count)
            .mapToObj(i -> VideoCompilationFixture.frame(i, i * 1_000_000L, 200_000))
            .toList();
    var ocr = new ArrayList<VideoFrameOcr>();
    for (int i = 0; i < count; i++) {
      ocr.add(
          new VideoFrameOcr(
              i,
              VideoCompilationFixture.image().sha256(),
              new ImageDimensions(2, 2),
              i == 0 ? "OCR42" : "",
              i == 0 ? List.of(new VideoOcrSegment(0, 0, 5, "OCR42")) : List.of(),
              i == 0 ? List.of(new ImageTextRegion(0, 5, 0, 0, 2, 2)) : List.of()));
    }
    var sha = ModelValues.sha256(ORIGINAL_VIDEO);
    var spans =
        IntStream.range(0, count)
            .mapToObj(
                i ->
                    new AudioTranscriptSpan(
                        i, i * 1000L, (i + 1) * 1000L, i == 0 ? "附近转录" : "远处转录"))
            .toList();
    var transcript =
        new AudioTranscription(
            sha, "decoder-v1", "asr-v1", "transcription-v1", count * 16000L, spans);
    assertTrue(
        ingestion.completeVideoIngestion(
            claim,
            new VideoCompilation(
                sha,
                "decoder-v1",
                VIDEO,
                0,
                count * 1_000_000L,
                frames,
                transcript,
                new VideoOcrCompilation("ocr-v1", ocr))));
    return index(upload.documentId());
  }

  private IngestionService ingestion(
      ImageOcrOptions ocr, VisualIngestionOptions visual, String audio, String video) {
    return new IngestionService(
        store,
        new IngestionRepository(store),
        new ManagementRepository(store),
        new DocumentPermissionPolicy(),
        ocr,
        visual,
        audio,
        video);
  }

  private PublicationVersion index(String documentId) {
    var indexing =
        new IndexingService(
            store,
            new IndexingRepository(store),
            new ManagementRepository(store),
            new DocumentPermissionPolicy());
    indexing.createIndexing(actor, documentId, target);
    var claim = indexing.claimIndexing(actor.workspaceId()).orElseThrow();
    var digests = new LinkedHashMap<String, String>();
    for (var item : claim.items()) {
      digests.put(
          RetrievalProjection.physicalSegmentId(claim.projectionGenerationId(), item.evidenceId()),
          "a".repeat(64));
    }
    var manifest =
        new RetrievalProjection.RevisionManifest(
            actor.workspaceId(), documentId, claim.projectionGenerationId(), digests);
    assertTrue(
        indexing.completeIndexing(
            claim,
            digests,
            new VerifiedRevision(target.projectionIdentity(), manifest.sha256(), digests.size())));
    return store.transaction(
        () -> new SynopsisMaterialRepository(store).publication(actor, documentId).orElseThrow());
  }
}
