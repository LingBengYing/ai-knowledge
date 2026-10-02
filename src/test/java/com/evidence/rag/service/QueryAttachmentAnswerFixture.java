package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.AudioModels;
import com.evidence.rag.client.model.QueryRankingModels;
import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.DecodedAudio;
import com.evidence.rag.model.domain.DecodedVideo;
import com.evidence.rag.model.domain.ImageRecall;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.ParsedImage;
import com.evidence.rag.model.domain.PreparedQuery;
import com.evidence.rag.model.domain.QueryAttachment;
import com.evidence.rag.model.domain.QueryAttachmentManifest;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.model.domain.VisualIngestionOptions;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.AnswerTestContext;
import com.evidence.rag.worker.parser.AudioDecoder;
import com.evidence.rag.worker.parser.ImageOcr;
import com.evidence.rag.worker.parser.VideoDecoder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/** Real publication fixture with synthetic query pixels and explicit model Adapters. */
final class QueryAttachmentAnswerFixture {
  static final Duration BUDGET = Duration.ofSeconds(10);
  static final String HINT = "查询附件含提示：指示灯颜色蓝色，等待5秒，密码123。";

  private QueryAttachmentAnswerFixture() {}

  static VisualImage image(String format) {
    try {
      return VisualSyntheticFixture.image(format);
    } catch (IOException failure) {
      throw new AssertionError("Synthetic query image encoding failed", failure);
    }
  }

  static QueryAttachmentService queries(
      AnswerTestContext context, VisionModels vision, QueryRankingModels ranking) {
    return new QueryAttachmentService(
        preparation(vision), ranking, context.models, context.projection, context.target);
  }

  static QueryPreparationService preparation(VisionModels vision) {
    var audio =
        new AudioModels() {
          public String revision() {
            return "unused-query-asr-v1";
          }

          public Transcript transcribe(byte[] wav) {
            throw new AssertionError("No query audio");
          }

          public void close() {}
        };
    var audioDecoder =
        new AudioDecoder() {
          public String revision() {
            return "unused-query-audio-v1";
          }

          public DecodedAudio decode(String filename, String mediaType, byte[] source) {
            throw new AssertionError("No query audio");
          }

          public void close() {}
        };
    var videoDecoder =
        new VideoDecoder() {
          public String revision() {
            return "unused-query-video-v1";
          }

          public DecodedVideo decode(String filename, String mediaType, byte[] source) {
            throw new AssertionError("No query video");
          }

          public void close() {}
        };
    var ocr =
        new ImageOcr() {
          public String revision() {
            return "query-no-text-v1";
          }

          public Optional<ParsedImage> read(VisualImage image) {
            return Optional.empty();
          }
        };
    return new QueryPreparationService(
        vision,
        ocr,
        new AudioCompilationService(audioDecoder, audio, 1, BUDGET),
        new VideoCompilationService(
            videoDecoder, new AudioTranscriptionService(audio, 1, BUDGET), vision, BUDGET),
        BUDGET);
  }

  static QueryAttachment attachment() {
    var image = image("jpeg");
    return new QueryAttachment("question.jpg", image.mediaType(), image.content());
  }

  static PreparedQuery prepared(String question) {
    var attachment = attachment();
    var image = new VisualImage(attachment.mediaType(), attachment.content());
    return new PreparedQuery(
        question,
        question + "\n" + HINT,
        List.of(image),
        List.of(
            new QueryAttachmentManifest(
                0,
                attachment.sha256(),
                QueryAttachment.Kind.IMAGE,
                "query-fixture-v1",
                ModelValues.sha256(HINT.getBytes(StandardCharsets.UTF_8)),
                HINT.length(),
                1,
                List.of(image.sha256()),
                false)),
        "query-fixture-preparation-v1");
  }

  static String publishImage(AnswerTestContext context, String visionRevision) {
    var store = context.authority.store();
    var ingestion =
        new IngestionService(
            store,
            new IngestionRepository(store),
            new ManagementRepository(store),
            new DocumentPermissionPolicy(),
            null,
            new VisualIngestionOptions(visionRevision));
    var image = image("png");
    ingestion.uploadDocument(context.owner, "library.png", image.mediaType(), image.content());
    var claim = ingestion.claimIngestion(context.owner.workspaceId()).orElseThrow();
    assertTrue(
        ingestion.completeVisualIngestion(
            claim, new ImageRecall("Library recall only, not query evidence.", visionRevision)));
    context.authority.createIndexing(context.owner, claim.documentId(), context.target);
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
    return claim.documentId();
  }
}
