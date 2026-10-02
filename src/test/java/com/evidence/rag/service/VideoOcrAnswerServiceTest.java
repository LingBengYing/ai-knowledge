package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.ImageDimensions;
import com.evidence.rag.model.domain.ImageRecall;
import com.evidence.rag.model.domain.ImageTextRegion;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VideoCompilation;
import com.evidence.rag.model.domain.VideoFrameOcr;
import com.evidence.rag.model.domain.VideoFrameRecall;
import com.evidence.rag.model.domain.VideoOcrCompilation;
import com.evidence.rag.model.domain.VideoOcrSegment;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.AnswerTestContext;
import com.evidence.rag.support.VideoCompilationFixture;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VideoOcrAnswerServiceTest {
  static final String TEXT = "星港项目的识别码为A-42。星港项目的预算为47万元。";
  static final String QUESTION = "星港项目的识别码是什么？星港项目的预算是多少？";
  static final String COMPILER = "java-video-compiler-v2:" + "d".repeat(64);
  @TempDir Path directory;

  @Test
  void legacyCompositionDoesNotEnableTheNewVideoMode() {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(10), 1)) {
      assertEquals(
          "answers_unavailable",
          assertThrows(
                  ApplicationException.class,
                  () ->
                      context.answers.answerVideoOcr(
                          context.owner,
                          new AnswerCommand(QUESTION, DocumentSelection.selected(List.of()))))
              .code());
      assertTrue(context.models.calls.isEmpty());
    }
  }

  @Test
  void emptyOcrScopeUsesTheSharedDurableAbstentionPath() {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(10), 1);
        var answers = videoAnswers(context)) {
      var result =
          answers.answerVideoOcr(
              context.owner, new AnswerCommand(QUESTION, DocumentSelection.selected(List.of())));
      assertEquals("abstained", result.status());
      assertEquals("empty_scope", result.reason());
      assertEquals(1, context.scalar("SELECT COUNT(*) FROM query_traces"));
      assertTrue(context.models.calls.isEmpty());
    }
  }

  @ParameterizedTest(name = "allDocuments={0}")
  @ValueSource(booleans = {true, false})
  void mixedRetrievalUsesOnlyOriginalFrameOcrAndReopensExactTypedSource(boolean allDocuments) {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(10), 1);
        var answers = videoAnswers(context)) {
      String extra = context.publish("extra.txt", "Unrelated authorized document.");
      String video = publish(context);
      var selection =
          allDocuments
              ? DocumentSelection.allDocuments()
              : DocumentSelection.selected(List.of(video, extra));
      var answer = answers.answerVideoOcr(context.owner, new AnswerCommand(QUESTION, selection));
      assertEquals("answered", answer.status(), answer.reason());
      assertTrue(answer.answer().contains("47万元"));
      assertFalse(answer.answer().contains("999"));
      assertEquals(List.of(TEXT), context.models.lastEvidence.stream().map(v -> v.text()).toList());
      assertEquals(2, context.scalar("SELECT COUNT(*) FROM query_trace_documents"));
      assertEquals(0, context.scalar("SELECT COUNT(*) FROM video_trace_proofs"));
      for (var citation : answer.citations()) {
        assertEquals("video_frame_ocr", citation.kind());
        assertEquals("machine_ocr", citation.proofOrigin());
        assertEquals("frame_interval", citation.timePrecision());
        assertNull(citation.groupId());
        assertNull(citation.transcript());
        assertEquals(0, citation.startUs());
        assertEquals(200_000, citation.endUs());
        assertFalse(citation.ocr().regions().isEmpty());
        assertEquals(
            TEXT.substring(citation.ocr().startCodePoint(), citation.ocr().endCodePoint()),
            citation.ocr().quote());
        assertEquals(
            citation,
            answers.videoSource(context.owner, answer.answerId(), citation.number()).citation());
        assertArrayEquals(
            VideoCompilationFixture.image().content(),
            answers.videoFrame(context.owner, answer.answerId(), citation.number()).content());
        assertArrayEquals(
            VideoAnswerFixture.ORIGINAL,
            answers.videoContent(context.owner, answer.answerId(), citation.number()).content());
      }
      context.revoke(extra);
      assertThrows(
          ApplicationException.class,
          () -> answers.videoSource(context.owner, answer.answerId(), 1));
    }
  }

  @Test
  void unknownMixedHitDoesNotDisappearInModalityFiltering() {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(10), 1);
        var answers = videoAnswers(context)) {
      publish(context);
      context.projection.results =
          hits -> {
            var mixed = new ArrayList<>(hits);
            mixed.add(new RetrievalProjection.Candidate("unrecognized", 1));
            return mixed;
          };
      var result =
          answers.answerVideoOcr(
              context.owner, new AnswerCommand(QUESTION, DocumentSelection.allDocuments()));
      assertEquals("abstained", result.status());
      assertEquals("upstream_invalid", result.reason());
      assertFalse(context.models.calls.contains("extract"));
    }
  }

  @Test
  void incompleteQuestionProofDoesNotBecomeAnOcrAnswer() {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(10), 1);
        var answers = videoAnswers(context)) {
      publish(context);
      context.models.extraction =
          values ->
              new com.evidence.rag.client.model.TextModels.Extraction(
                  List.of(
                      new com.evidence.rag.client.model.TextModels.Quote(
                          values.getFirst().id(), "星港项目的识别码为A-42。")),
                  false);
      var result =
          answers.answerVideoOcr(
              context.owner, new AnswerCommand(QUESTION, DocumentSelection.allDocuments()));
      assertEquals("abstained", result.status());
      assertTrue(result.citations().isEmpty());
    }
  }

  private static AnswerService videoAnswers(AnswerTestContext context) {
    return new AnswerService(
        context.evidence,
        context.models,
        context.projection,
        context.target,
        Duration.ofSeconds(10),
        1,
        VideoAnswerFixture.proposals(context, new VideoAnswerFixture.Facts()));
  }

  static String publish(AnswerTestContext context) {
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
            COMPILER);
    var upload =
        ingestion.uploadDocument(
            context.owner, "screen.mp4", "video/mp4", VideoAnswerFixture.ORIGINAL);
    var claim = ingestion.claimIngestion(context.owner.workspaceId()).orElseThrow();
    var frames =
        List.of(
            new VideoFrameRecall(
                VideoCompilationFixture.frame(0, 0, 200_000).frame(),
                new ImageRecall("星港项目的预算为999万元。", "caption-v1")),
            VideoCompilationFixture.frame(1, 1_200_000, 200_000));
    var image = VideoCompilationFixture.image();
    int length = TEXT.codePointCount(0, TEXT.length());
    var ocr =
        new VideoOcrCompilation(
            "ocr-v1",
            List.of(
                new VideoFrameOcr(
                    0,
                    image.sha256(),
                    new ImageDimensions(2, 2),
                    TEXT,
                    List.of(new VideoOcrSegment(0, 0, length, TEXT)),
                    List.of(new ImageTextRegion(0, length, 0, 0, 2, 2))),
                new VideoFrameOcr(
                    1, image.sha256(), new ImageDimensions(2, 2), "", List.of(), List.of())));
    assertTrue(
        ingestion.completeVideoIngestion(
            claim,
            new VideoCompilation(
                ModelValues.sha256(VideoAnswerFixture.ORIGINAL),
                "fixture-decoder-v1",
                COMPILER,
                0,
                1_400_000,
                frames,
                null,
                ocr)));
    context.authority.createIndexing(context.owner, upload.documentId(), context.target);
    var index = context.authority.claimIndexing(context.owner.workspaceId()).orElseThrow();
    var entries =
        index.items().stream()
            .map(
                item ->
                    new RetrievalProjection.Entry(
                        RetrievalProjection.physicalSegmentId(
                            index.projectionGenerationId(), item.evidenceId()),
                        context.owner.workspaceId(),
                        index.documentId(),
                        index.projectionGenerationId(),
                        item.recallText(),
                        List.of(1.0, 0.0)))
            .toList();
    context.projection.data.initialize();
    context.projection.data.upsert(entries);
    var hashes = new TreeMap<String, String>();
    entries.forEach(entry -> hashes.put(entry.segmentId(), RetrievalProjection.entryDigest(entry)));
    assertTrue(
        context.authority.completeIndexing(
            index,
            hashes,
            context.projection.data.verify(
                new RetrievalProjection.RevisionManifest(
                    context.owner.workspaceId(),
                    index.documentId(),
                    index.projectionGenerationId(),
                    hashes))));
    return upload.documentId();
  }
}
