package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.AudioCompilation;
import com.evidence.rag.model.domain.AudioTranscriptSpan;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.AnswerTestContext;
import com.evidence.rag.tool.parser.AudioPcm;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.IntStream;

/** Synthetic ASR fixture through real authority/publication; does not claim recognition quality. */
final class AudioTestFixture {
  static final String COMPILER = "java-audio-compiler-v1:" + "a".repeat(64);

  private AudioTestFixture() {}

  static Published publish(AnswerTestContext context, List<String> texts) {
    var store = context.authority.store();
    var ingestion =
        new IngestionService(
            store,
            new IngestionRepository(store),
            new ManagementRepository(store),
            new DocumentPermissionPolicy(),
            null,
            null,
            COMPILER);
    byte[] pcm = new byte[texts.size() * 32_000];
    byte[] original = AudioPcm.wav(pcm, 0, pcm.length);
    var uploaded = ingestion.uploadDocument(context.owner, "meeting.wav", "audio/wav", original);
    var claim = ingestion.claimIngestion(context.owner.workspaceId()).orElseThrow();
    var spans =
        IntStream.range(0, texts.size())
            .mapToObj(
                index ->
                    new AudioTranscriptSpan(
                        index, index * 1000L, (index + 1) * 1000L, texts.get(index)))
            .toList();
    assertTrue(
        ingestion.completeAudioIngestion(
            claim,
            new AudioCompilation(
                ModelValues.sha256(original),
                "test-audio-decoder-v1",
                "test-audio-model-v1",
                COMPILER,
                texts.size() * 1000L,
                spans)));
    context.authority.createIndexing(context.owner, uploaded.documentId(), context.target);
    var indexing = context.authority.claimIndexing(context.owner.workspaceId()).orElseThrow();
    var entries =
        indexing.items().stream()
            .map(
                item ->
                    new RetrievalProjection.Entry(
                        RetrievalProjection.physicalSegmentId(
                            indexing.projectionGenerationId(), item.evidenceId()),
                        context.owner.workspaceId(),
                        uploaded.documentId(),
                        indexing.projectionGenerationId(),
                        item.recallText(),
                        List.of(1.0, 0.0)))
            .toList();
    context.projection.data.initialize();
    for (int offset = 0; offset < entries.size(); offset += RetrievalProjection.MAX_BATCH) {
      context.projection.data.upsert(
          entries.subList(
              offset, Math.min(offset + RetrievalProjection.MAX_BATCH, entries.size())));
    }
    var digests = new TreeMap<String, String>();
    entries.forEach(
        entry -> digests.put(entry.segmentId(), RetrievalProjection.entryDigest(entry)));
    var manifest =
        new RetrievalProjection.RevisionManifest(
            context.owner.workspaceId(),
            uploaded.documentId(),
            indexing.projectionGenerationId(),
            digests);
    assertTrue(
        context.authority.completeIndexing(
            indexing, digests, context.projection.data.verify(manifest)));
    return new Published(
        uploaded.documentId(),
        claim.revisionId(),
        entries.stream().map(RetrievalProjection.Entry::segmentId).toList(),
        original);
  }

  record Published(
      String documentId, String revisionId, List<String> physicalIds, byte[] original) {}
}
