package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AudioTranscriptSpan;
import com.evidence.rag.model.domain.AudioTranscription;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.ImageDimensions;
import com.evidence.rag.model.domain.ImageRecall;
import com.evidence.rag.model.domain.ImageTextRegion;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VideoAssessment;
import com.evidence.rag.model.domain.VideoCompilation;
import com.evidence.rag.model.domain.VideoFrameOcr;
import com.evidence.rag.model.domain.VideoFrameRecall;
import com.evidence.rag.model.domain.VideoOcrCompilation;
import com.evidence.rag.model.domain.VideoOcrSegment;
import com.evidence.rag.model.domain.VideoSubtitleCompilation;
import com.evidence.rag.model.domain.VideoSubtitleCue;
import com.evidence.rag.model.domain.VideoSubtitleTrack;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.model.dto.VideoAnswerResult;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.AnswerTestContext;
import com.evidence.rag.support.VideoCompilationFixture;
import com.evidence.rag.support.VideoSubtitleCompilationFixture;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Genuine anchored role changes must not turn stored, current-authorized media into model work. */
class ManagedVideoSourcesBoundaryTest {
  private static final String TEXT = "星港项目的识别码为A-42。星港项目的预算为47万元。";
  private static final String QUESTION = "星港项目的识别码是什么？星港项目的预算是多少？";
  @TempDir Path directory;

  @ParameterizedTest
  @EnumSource(SourceType.class)
  void anchoredRoleSwitchKeepsExactStoredSourcesWithoutProposalOrProviders(SourceType type)
      throws Exception {
    try (var saved = save(type)) {
      readExact(saved.producer, saved);
      var current = saved.switchRoles();
      var oldCalls = List.copyOf(saved.oldModels.calls);
      var projectionCalls = List.copyOf(saved.fixture.projection.calls);
      var facts = List.copyOf(saved.facts.contexts);
      try (var operation = saved.fixture.authority.store().operationGate().enter()) {
        assertEquals(current, saved.fixture.runtime.capture());
        readExact(current.answers(), saved);
      }
      assertEquals(oldCalls, saved.oldModels.calls);
      assertTrue(((AnswerTestContext.RecordingModels) current.models()).calls.isEmpty());
      assertEquals(projectionCalls, saved.fixture.projection.calls);
      assertEquals(facts, saved.facts.contexts);
      assertEquals(
          1, Long.parseLong(saved.fixture.scalar("SELECT COUNT(*) FROM query_traces", null)));
    }
  }

  @ParameterizedTest
  @EnumSource(SourceType.class)
  void anchoredSourcesStillRequireTheOriginalActorAndEverySelectedDocument(SourceType type)
      throws Exception {
    try (var saved = save(type)) {
      readExact(saved.producer, saved);
      var current = saved.switchRoles();
      var other = new Actor(TextRoleSwitchTestFixture.ADMIN.workspaceId(), "other-reader");
      assertNotFound(() -> current.answers().videoSource(other, saved.result.answerId(), 1));
      try (var connection =
              DriverManager.getConnection(
                  "jdbc:sqlite:" + saved.fixture.authority.store().libraryPath());
          var statement =
              connection.prepareStatement(
                  "DELETE FROM document_acl WHERE document_id=? AND principal_id=?")) {
        statement.setString(1, saved.nonCandidate);
        statement.setString(2, TextRoleSwitchTestFixture.ADMIN.principalId());
        assertEquals(1, statement.executeUpdate());
      }
      assertNotFound(
          () ->
              current
                  .answers()
                  .videoSource(TextRoleSwitchTestFixture.ADMIN, saved.result.answerId(), 1));
      assertNotFound(
          () ->
              current
                  .answers()
                  .videoFrame(TextRoleSwitchTestFixture.ADMIN, saved.result.answerId(), 1));
      assertNotFound(
          () ->
              current
                  .answers()
                  .videoContent(TextRoleSwitchTestFixture.ADMIN, saved.result.answerId(), 1));
      assertTrue(((AnswerTestContext.RecordingModels) current.models()).calls.isEmpty());
    }
  }

  @ParameterizedTest
  @EnumSource(SourceType.class)
  void closedAnchoredSourceReaderStillRefusesEveryStoredRead(SourceType type) {
    try (var saved = save(type)) {
      readExact(saved.producer, saved);
      var current = saved.switchRoles();
      current.answers().close();
      assertUnavailable(
          () ->
              current
                  .answers()
                  .videoSource(TextRoleSwitchTestFixture.ADMIN, saved.result.answerId(), 1));
      assertUnavailable(
          () ->
              current
                  .answers()
                  .videoFrame(TextRoleSwitchTestFixture.ADMIN, saved.result.answerId(), 1));
      assertUnavailable(
          () ->
              current
                  .answers()
                  .videoContent(TextRoleSwitchTestFixture.ADMIN, saved.result.answerId(), 1));
      assertTrue(((AnswerTestContext.RecordingModels) current.models()).calls.isEmpty());
    }
  }

  @Test
  void sourceOnlyAnchoredBundleDoesNotEnableAnyNewVideoAnswerMode() {
    try (var saved = save(SourceType.ORDINARY)) {
      var current = saved.switchRoles();
      var command =
          new AnswerCommand(
              VideoAnswerFixture.QUESTION,
              DocumentSelection.selected(List.of(saved.video, saved.nonCandidate)));
      assertUnavailable(
          () ->
              current
                  .answers()
                  .answerVideo(
                      TextRoleSwitchTestFixture.ADMIN, command, VideoAssessment.Mode.JOINT));
      assertUnavailable(
          () -> current.answers().answerVideoOcr(TextRoleSwitchTestFixture.ADMIN, command));
      assertUnavailable(
          () -> current.answers().answerVideoSubtitle(TextRoleSwitchTestFixture.ADMIN, command));
      assertTrue(((AnswerTestContext.RecordingModels) current.models()).calls.isEmpty());
    }
  }

  private SavedAnswer save(SourceType type) {
    var fixture = new TextRoleSwitchTestFixture(directory);
    AnswerService producer = null;
    try {
      var first =
          fixture.activate(1, TextRoleSwitchTestFixture.roles("rerank-v1", "generation-v1"), null);
      var models = (AnswerTestContext.RecordingModels) first.models();
      String other = fixture.publish(first.target());
      String video = publish(fixture, first.target(), type);
      var facts = new VideoAnswerFixture.Facts();
      var proposals =
          new VideoAnswerProposalService(
              fixture.evidence,
              models,
              facts,
              facts,
              fixture.projection,
              first.target(),
              Duration.ofSeconds(10));
      producer =
          new AnswerService(
              fixture.evidence,
              models,
              fixture.projection,
              first.target(),
              Duration.ofSeconds(10),
              1,
              proposals,
              null,
              first.indexAnchor());
      var command =
          new AnswerCommand(
              type == SourceType.ORDINARY ? VideoAnswerFixture.QUESTION : QUESTION,
              DocumentSelection.selected(List.of(video, other)));
      var result =
          switch (type) {
            case ORDINARY ->
                producer.answerVideo(
                    TextRoleSwitchTestFixture.ADMIN, command, VideoAssessment.Mode.JOINT);
            case OCR -> producer.answerVideoOcr(TextRoleSwitchTestFixture.ADMIN, command);
            case SUBTITLE -> producer.answerVideoSubtitle(TextRoleSwitchTestFixture.ADMIN, command);
          };
      assertEquals("answered", result.status(), result.reason());
      assertTrue(!result.citations().isEmpty());
      return new SavedAnswer(fixture, first, producer, models, facts, type, video, other, result);
    } catch (RuntimeException | Error failure) {
      if (producer != null) {
        producer.close();
      }
      fixture.close();
      throw failure;
    }
  }

  private static String publish(
      TextRoleSwitchTestFixture fixture, IndexTarget target, SourceType type) {
    var store = fixture.authority.store();
    var compilation = compilation(type);
    var ingestion =
        new IngestionService(
            store,
            new IngestionRepository(store),
            new ManagementRepository(store),
            new DocumentPermissionPolicy(),
            null,
            null,
            null,
            compilation.compilerRevision());
    var upload =
        ingestion.uploadDocument(
            TextRoleSwitchTestFixture.ADMIN,
            "stored-source.mp4",
            "video/mp4",
            VideoAnswerFixture.ORIGINAL);
    var claim =
        ingestion.claimIngestion(TextRoleSwitchTestFixture.ADMIN.workspaceId()).orElseThrow();
    assertTrue(ingestion.completeVideoIngestion(claim, compilation));
    fixture.authority.createIndexing(TextRoleSwitchTestFixture.ADMIN, upload.documentId(), target);
    var index =
        fixture
            .authority
            .claimIndexing(TextRoleSwitchTestFixture.ADMIN.workspaceId())
            .orElseThrow();
    var entries =
        index.items().stream()
            .map(
                item ->
                    new RetrievalProjection.Entry(
                        RetrievalProjection.physicalSegmentId(
                            index.projectionGenerationId(), item.evidenceId()),
                        index.workspaceId(),
                        index.documentId(),
                        index.projectionGenerationId(),
                        item.recallText(),
                        List.of(1.0, 0.0)))
            .toList();
    fixture.projection.data.upsert(entries);
    var hashes = new TreeMap<String, String>();
    entries.forEach(entry -> hashes.put(entry.segmentId(), RetrievalProjection.entryDigest(entry)));
    assertTrue(
        fixture.authority.completeIndexing(
            index,
            hashes,
            fixture.projection.data.verify(
                new RetrievalProjection.RevisionManifest(
                    index.workspaceId(),
                    index.documentId(),
                    index.projectionGenerationId(),
                    hashes))));
    return upload.documentId();
  }

  private static VideoCompilation compilation(SourceType type) {
    var image = VideoCompilationFixture.image();
    var frame =
        new VideoFrameRecall(
            VideoCompilationFixture.frame(0, 0, 200_000).frame(),
            new ImageRecall("故意误导的召回描述：红灯，预算999万元。", "fixture-description-v1"));
    String source = ModelValues.sha256(VideoAnswerFixture.ORIGINAL);
    if (type == SourceType.ORDINARY) {
      return new VideoCompilation(
          source,
          "fixture-decoder-v1",
          VideoCompilationFixture.COMPILER,
          0,
          2_000_000,
          List.of(frame),
          new AudioTranscription(
              source,
              "fixture-decoder-v1",
              "asr-v1",
              "transcription-v1",
              32_000,
              List.of(
                  new AudioTranscriptSpan(0, 0, 1000, VideoAnswerFixture.TRANSCRIPT),
                  new AudioTranscriptSpan(1, 1000, 2000, ""))));
    }
    if (type == SourceType.OCR) {
      int end = TEXT.codePointCount(0, TEXT.length());
      return new VideoCompilation(
          source,
          "fixture-decoder-v1",
          VideoOcrAnswerServiceTest.COMPILER,
          0,
          2_000_000,
          List.of(frame),
          null,
          new VideoOcrCompilation(
              "ocr-v1",
              List.of(
                  new VideoFrameOcr(
                      0,
                      image.sha256(),
                      new ImageDimensions(2, 2),
                      TEXT,
                      List.of(new VideoOcrSegment(0, 0, end, TEXT)),
                      List.of(new ImageTextRegion(0, end, 0, 0, 2, 2))))));
    }
    return new VideoCompilation(
        source,
        "decoder-v2",
        VideoSubtitleCompilationFixture.COMPILER,
        2_000_000,
        2_500_000,
        List.of(frame),
        null,
        null,
        new VideoSubtitleCompilation(
            2,
            1,
            1,
            List.of(
                new VideoSubtitleTrack(
                    2,
                    "mov_text",
                    1,
                    1000,
                    null,
                    List.of(new VideoSubtitleCue(0, 2500, 1000, TEXT, "b".repeat(64)))))));
  }

  private static void readExact(AnswerService answers, SavedAnswer saved) {
    for (var citation : saved.result.citations()) {
      assertEquals(
          citation,
          assertDoesNotThrow(
                  () ->
                      answers.videoSource(
                          TextRoleSwitchTestFixture.ADMIN,
                          saved.result.answerId(),
                          citation.number()))
              .citation());
      var original =
          assertDoesNotThrow(
              () ->
                  answers.videoContent(
                      TextRoleSwitchTestFixture.ADMIN, saved.result.answerId(), citation.number()));
      assertEquals("video/mp4", original.mediaType());
      assertArrayEquals(VideoAnswerFixture.ORIGINAL, original.content());
      assertEquals(citation.sourceSha256(), ModelValues.sha256(original.content()));
      if (saved.type == SourceType.SUBTITLE) {
        assertNull(citation.frame());
        assertNotFound(
            () ->
                answers.videoFrame(
                    TextRoleSwitchTestFixture.ADMIN, saved.result.answerId(), citation.number()));
      } else {
        assertArrayEquals(
            VideoCompilationFixture.image().content(),
            assertDoesNotThrow(
                    () ->
                        answers.videoFrame(
                            TextRoleSwitchTestFixture.ADMIN,
                            saved.result.answerId(),
                            citation.number()))
                .content());
      }
    }
  }

  private static void assertNotFound(Runnable read) {
    var failure = assertThrows(ApplicationException.class, read::run);
    assertEquals(FailureKind.NOT_FOUND, failure.kind());
    assertEquals("not_found", failure.code());
  }

  private static void assertUnavailable(Runnable read) {
    var failure = assertThrows(ApplicationException.class, read::run);
    assertEquals(FailureKind.UNAVAILABLE, failure.kind());
    assertEquals("answers_unavailable", failure.code());
  }

  private enum SourceType {
    ORDINARY,
    OCR,
    SUBTITLE
  }

  private record SavedAnswer(
      TextRoleSwitchTestFixture fixture,
      TextRuntimeSnapshot first,
      AnswerService producer,
      AnswerTestContext.RecordingModels oldModels,
      VideoAnswerFixture.Facts facts,
      SourceType type,
      String video,
      String nonCandidate,
      VideoAnswerResult result)
      implements AutoCloseable {
    TextRuntimeSnapshot switchRoles() {
      var current =
          fixture.activate(
              2,
              TextRoleSwitchTestFixture.roles("rerank-v2", "generation-v2"),
              first.indexAnchor());
      assertEquals(first.target(), current.target());
      assertEquals(first.indexAnchor(), current.indexAnchor());
      assertNotEquals(first.modelsRevision(), current.modelsRevision());
      assertEquals(
          "media_text_configuration_mismatch",
          assertThrows(
                  ApplicationException.class,
                  () ->
                      new LegacyTextProfileGuard(fixture.runtime, first.target())
                          .requireCompatible())
              .code());
      return current;
    }

    @Override
    public void close() {
      producer.close();
      fixture.close();
    }
  }
}
