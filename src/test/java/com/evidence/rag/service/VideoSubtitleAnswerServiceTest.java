package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.ImageRecall;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VideoCompilation;
import com.evidence.rag.model.domain.VideoFrameRecall;
import com.evidence.rag.model.domain.VideoSubtitleCompilation;
import com.evidence.rag.model.domain.VideoSubtitleCue;
import com.evidence.rag.model.domain.VideoSubtitleTrack;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.AnswerTestContext;
import com.evidence.rag.support.VideoCompilationFixture;
import com.evidence.rag.support.VideoSubtitleCompilationFixture;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VideoSubtitleAnswerServiceTest {
  static final String TEXT = "星港项目的识别码为A-42。星港项目的预算为47万元。";
  static final String QUESTION = "星港项目的识别码是什么？星港项目的预算是多少？";
  @TempDir Path directory;

  @Test
  void emptySubtitleScopePersistsAnAbstentionAndLegacyCompositionStaysDisabled() {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(10), 1);
        var answers = answers(context)) {
      assertThrows(
          ApplicationException.class,
          () ->
              context.answers.answerVideoSubtitle(
                  context.owner, new AnswerCommand(QUESTION, DocumentSelection.allDocuments())));
      var result =
          answers.answerVideoSubtitle(
              context.owner, new AnswerCommand(QUESTION, DocumentSelection.selected(List.of())));
      assertEquals("empty_scope", result.reason());
      assertEquals(1, context.scalar("SELECT COUNT(*) FROM query_traces"));
      assertTrue(context.models.calls.isEmpty());
    }
  }

  @ParameterizedTest(name = "allDocuments={0}")
  @ValueSource(booleans = {true, false})
  void mixedRecallUsesOnlyCueTextAndSourceReopensOriginalSubtitleTime(boolean allDocuments) {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(10), 1);
        var answers = answers(context)) {
      String extra = context.publish("other.txt", "Unrelated authorized text.");
      String video = publish(context, "后续字幕内容。");
      context.models.extraction =
          values ->
              new TextModels.Extraction(
                  values.stream()
                      .filter(value -> value.text().equals(TEXT))
                      .map(value -> new TextModels.Quote(value.id(), value.text()))
                      .toList(),
                  false);
      var selection =
          allDocuments
              ? DocumentSelection.allDocuments()
              : DocumentSelection.selected(List.of(extra, video));
      var result =
          answers.answerVideoSubtitle(context.owner, new AnswerCommand(QUESTION, selection));
      assertEquals("answered", result.status(), result.reason());
      assertTrue(result.answer().contains("47万元"));
      assertFalse(result.answer().contains("999"));
      assertEquals(2, context.scalar("SELECT COUNT(*) FROM query_trace_documents"));
      assertEquals(0, context.scalar("SELECT COUNT(*) FROM video_trace_proofs"));
      assertEquals(0, context.scalar("SELECT COUNT(*) FROM video_ocr_trace_evidence"));
      assertTrue(context.models.lastEvidence.stream().noneMatch(e -> e.text().contains("999")));
      for (var citation : result.citations()) {
        assertEquals("video_subtitle", citation.kind());
        assertEquals("embedded_subtitle", citation.proofOrigin());
        assertEquals("subtitle_cue", citation.timePrecision());
        assertNull(citation.groupId());
        assertNull(citation.frame());
        assertNull(citation.transcript());
        assertNull(citation.ocr());
        assertEquals(500000, citation.startUs());
        assertEquals(1500000, citation.endUs());
        assertEquals(2, citation.subtitle().streamIndex());
        assertEquals("mov_text", citation.subtitle().codec());
        assertNull(citation.subtitle().language());
        assertEquals(1, citation.subtitle().cueOrdinal());
        assertEquals(2500, citation.subtitle().pts());
        assertEquals(1000, citation.subtitle().duration());
        assertEquals(1, citation.subtitle().timeBaseNumerator());
        assertEquals(1000, citation.subtitle().timeBaseDenominator());
        assertEquals("subtitle-payload-utf8-v1", citation.subtitle().textFormat());
        assertEquals(
            citation,
            answers.videoSource(context.owner, result.answerId(), citation.number()).citation());
        assertArrayEquals(
            VideoAnswerFixture.ORIGINAL,
            answers.videoContent(context.owner, result.answerId(), citation.number()).content());
        assertThrows(
            ApplicationException.class,
            () -> answers.videoFrame(context.owner, result.answerId(), citation.number()));
      }
      context.revoke(extra);
      assertThrows(
          ApplicationException.class,
          () -> answers.videoSource(context.owner, result.answerId(), 1));
    }
  }

  @Test
  void unknownMixedHitIsNotSilentlyDiscarded() {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(10), 1);
        var answers = answers(context)) {
      publish(context, "后续字幕内容。");
      context.projection.results =
          hits -> {
            var result = new ArrayList<>(hits);
            result.add(new RetrievalProjection.Candidate("unknown", 1));
            return result;
          };
      var result =
          answers.answerVideoSubtitle(
              context.owner, new AnswerCommand(QUESTION, DocumentSelection.allDocuments()));
      assertEquals("upstream_invalid", result.reason());
      assertFalse(context.models.calls.contains("extract"));
    }
  }

  @Test
  void unselectedSameTrackTailContradictionVetoesAQuotedCandidate() throws Exception {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(10), 1);
        var answers = answers(context)) {
      publish(context, "更正：星港项目的预算不是47万元，而是53万元。");
      String firstCue;
      try (var connection =
              DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
          var statement =
              connection.prepareStatement(
                  "SELECT e.physical_segment_id FROM video_subtitle_publication_entries e JOIN video_subtitle_cues c ON c.id=e.video_subtitle_cue_id WHERE c.text=?")) {
        statement.setString(1, TEXT);
        try (var rows = statement.executeQuery()) {
          assertTrue(rows.next());
          firstCue = rows.getString(1);
        }
      }
      context.projection.results =
          hits -> hits.stream().filter(hit -> hit.segmentId().equals(firstCue)).toList();
      context.models.extraction =
          values ->
              new TextModels.Extraction(
                  values.stream()
                      .filter(value -> value.text().equals(TEXT))
                      .map(value -> new TextModels.Quote(value.id(), value.text()))
                      .toList(),
                  false);
      var result =
          answers.answerVideoSubtitle(
              context.owner, new AnswerCommand(QUESTION, DocumentSelection.allDocuments()));
      assertEquals("abstained", result.status());
      assertTrue(result.citations().isEmpty());
      assertEquals(0, context.scalar("SELECT COUNT(*) FROM video_subtitle_trace_evidence"));
    }
  }

  @Test
  void revokedUnquotedSelectedDocumentClearsSubtitleCitationsAtCommit() {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(10), 1);
        var answers = answers(context)) {
      String extra = context.publish("other.txt", "Unrelated authorized text.");
      String video = publish(context, "后续字幕内容。");
      context.models.onExtract = () -> context.revoke(extra);
      var result =
          answers.answerVideoSubtitle(
              context.owner,
              new AnswerCommand(QUESTION, DocumentSelection.selected(List.of(video, extra))));
      assertEquals("abstained", result.status());
      assertEquals("scope_changed", result.reason());
      assertEquals(0, context.scalar("SELECT COUNT(*) FROM video_subtitle_trace_evidence"));
    }
  }

  private static AnswerService answers(AnswerTestContext context) {
    return new AnswerService(
        context.evidence,
        context.models,
        context.projection,
        context.target,
        Duration.ofSeconds(10),
        1,
        VideoAnswerFixture.proposals(context, new VideoAnswerFixture.Facts()));
  }

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
            VideoSubtitleCompilationFixture.COMPILER);
    var upload =
        ingestion.uploadDocument(
            context.owner, "subtitle.mp4", "video/mp4", VideoAnswerFixture.ORIGINAL);
    var claim = ingestion.claimIngestion(context.owner.workspaceId()).orElseThrow();
    var frames =
        List.of(
            new VideoFrameRecall(
                VideoCompilationFixture.frame(0, 0, 200000).frame(),
                new ImageRecall("星港项目的预算为999万元。", "caption-v1")));
    var tracks =
        List.of(
            new VideoSubtitleTrack(
                2,
                "mov_text",
                1,
                1000,
                null,
                List.of(
                    new VideoSubtitleCue(0, 0, 0, "", "a".repeat(64)),
                    new VideoSubtitleCue(1, 2500, 1000, TEXT, "b".repeat(64)),
                    new VideoSubtitleCue(2, 3500, 1000, tail, "c".repeat(64)))));
    assertTrue(
        ingestion.completeVideoIngestion(
            claim,
            new VideoCompilation(
                ModelValues.sha256(VideoAnswerFixture.ORIGINAL),
                "decoder-v2",
                VideoSubtitleCompilationFixture.COMPILER,
                2000000,
                2500000,
                frames,
                null,
                null,
                new VideoSubtitleCompilation(2, 1, 1, tracks))));
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
