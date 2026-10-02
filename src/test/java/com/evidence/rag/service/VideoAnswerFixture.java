package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.FactTextModels;
import com.evidence.rag.client.model.FactVisionModels;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.AudioTranscriptSpan;
import com.evidence.rag.model.domain.AudioTranscription;
import com.evidence.rag.model.domain.ImageRecall;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.QuestionFact;
import com.evidence.rag.model.domain.VideoCompilation;
import com.evidence.rag.model.domain.VideoFrame;
import com.evidence.rag.model.domain.VideoFrameRecall;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.AnswerTestContext;
import com.evidence.rag.support.VideoCompilationFixture;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Synthetic compiler output with real SQLite/publication; native decoding is tested separately. */
final class VideoAnswerFixture {
  static final String QUESTION = "指示灯的颜色是什么？重启等待时间是多少？";
  static final String TRANSCRIPT = "重启等待时间是5秒。";
  static final byte[] ORIGINAL = {
    0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 0, 'i', 's', 'o', 'm', 'm', 'p',
    '4', '2'
  };

  private VideoAnswerFixture() {}

  static String publish(AnswerTestContext context, String tail) {
    var store = context.authority.store();
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
    var upload = ingestion.uploadDocument(context.owner, "indicator.mp4", "video/mp4", ORIGINAL);
    var claim = ingestion.claimIngestion(context.owner.workspaceId()).orElseThrow();
    var image = VideoCompilationFixture.image();
    var compilation =
        new VideoCompilation(
            ModelValues.sha256(ORIGINAL),
            "fixture-decoder-v1",
            VideoCompilationFixture.COMPILER,
            0,
            2_000_000,
            List.of(
                new VideoFrameRecall(
                    new VideoFrame(0, 0, 200_000, image, 2, 2),
                    new ImageRecall("故意误导的召回描述：红灯，等待99秒。", "fixture-description-v1"))),
            new AudioTranscription(
                ModelValues.sha256(ORIGINAL),
                "fixture-decoder-v1",
                "asr-v1",
                "transcription-v1",
                32_000,
                List.of(
                    new AudioTranscriptSpan(0, 0, 1000, TRANSCRIPT),
                    new AudioTranscriptSpan(1, 1000, 2000, tail))));
    assertTrue(ingestion.completeVideoIngestion(claim, compilation));
    context.authority.createIndexing(context.owner, upload.documentId(), context.target);
    var indexing = context.authority.claimIndexing(context.owner.workspaceId()).orElseThrow();
    var entries =
        indexing.items().stream()
            .map(
                item ->
                    new RetrievalProjection.Entry(
                        RetrievalProjection.physicalSegmentId(
                            indexing.projectionGenerationId(), item.evidenceId()),
                        context.owner.workspaceId(),
                        indexing.documentId(),
                        indexing.projectionGenerationId(),
                        item.recallText(),
                        List.of(1.0, 0.0)))
            .toList();
    context.projection.data.initialize();
    context.projection.data.upsert(entries);
    var hashes = new TreeMap<String, String>();
    entries.forEach(entry -> hashes.put(entry.segmentId(), RetrievalProjection.entryDigest(entry)));
    assertTrue(
        context.authority.completeIndexing(
            indexing,
            hashes,
            context.projection.data.verify(
                new RetrievalProjection.RevisionManifest(
                    context.owner.workspaceId(),
                    indexing.documentId(),
                    indexing.projectionGenerationId(),
                    hashes))));
    return upload.documentId();
  }

  static VideoAnswerProposalService proposals(AnswerTestContext context, Facts facts) {
    return new VideoAnswerProposalService(
        context.evidence,
        context.models,
        facts,
        facts,
        context.projection,
        context.target,
        Duration.ofSeconds(10));
  }

  static final class Facts implements FactTextModels, FactVisionModels {
    String revision = "fixture-fact-v1";
    Runnable afterDraft = () -> {};
    final List<String> contexts = new ArrayList<>();

    @Override
    public String revision() {
      return revision;
    }

    @Override
    public VisionModels.Draft draftFact(String question, QuestionFact fact, VisualImage image) {
      contexts.add(question);
      org.junit.jupiter.api.Assertions.assertArrayEquals(
          VideoCompilationFixture.image().content(), image.content());
      afterDraft.run();
      return fact.requirement().contains("颜色")
          ? new VisionModels.Draft(false, List.of("指示灯的颜色是蓝色。"))
          : new VisionModels.Draft(true, List.of());
    }

    @Override
    public VisionModels.Verification verifyFact(
        String question, QuestionFact fact, VisualImage image, List<String> claims) {
      contexts.add(question);
      return new VisionModels.Verification(true, claims.stream().map(claim -> true).toList());
    }

    @Override
    public TextModels.Extraction extractFact(
        String question, QuestionFact fact, List<TextModels.Evidence> evidence) {
      contexts.add(question);
      return fact.requirement().contains("等待")
          ? new TextModels.Extraction(
              List.of(new TextModels.Quote(evidence.getFirst().id(), TRANSCRIPT)), false)
          : new TextModels.Extraction(List.of(), true);
    }
  }
}
