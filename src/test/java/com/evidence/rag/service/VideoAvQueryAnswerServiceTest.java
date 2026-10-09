package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.VideoAvModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.VideoAvMode;
import com.evidence.rag.model.domain.VideoAvRequirement;
import com.evidence.rag.model.domain.VideoAvRoute;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.model.dto.VideoAvAnswerCommand;
import com.evidence.rag.model.dto.VideoAvQueryAnswerResult;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class VideoAvQueryAnswerServiceTest {
  @TempDir Path directory;

  @ParameterizedTest
  @EnumSource(VideoAvMode.class)
  void onlyLastApplicableWindowCanRecallWhenTextAndEarlierWindowsMiss(VideoAvMode mode) {
    try (var fixture = new VideoAvQueryTestFixture(directory);
        var answers = fixture.answers()) {
      fixture.base.register("library", 1, 1, 16000, true);
      var reference = fixture.reference("tail-only-match", 3, 2, 32001);
      fixture.clipVector =
          clip ->
              List.of(
                  mode == VideoAvMode.VISUAL && clip.frames().getFirst().sourceOrdinal() == 1
                      ? 3.0
                      : 2.0,
                  1.0);
      fixture.audioVector = wave -> List.of(wave.endSample() == 32001 ? 3.0 : 2.0, 1.0);
      var libraryWindow = fixture.base.publications.getFirst().windows().getFirst();
      fixture.base.search =
          (route, query) ->
              query.vector().getFirst() == 3.0
                  ? List.of(
                      new RetrievalProjection.Candidate(
                          route == VideoAvRoute.VISUAL
                              ? libraryWindow.visualPhysicalId()
                              : libraryWindow.audioPhysicalId(),
                          1))
                  : List.of();
      var result =
          answers.answerAttached(
              VideoAvTestFixture.OWNER, command(mode, "library"), List.of(reference));
      assertEquals("answered", result.result().status());
      assertEquals(VideoAvTestFixture.FACT, result.result().answer());
      assertEquals(
          mode == VideoAvMode.VISUAL ? 3 : mode == VideoAvMode.AUDIO ? 4 : 7,
          fixture.base.queries.size());
      assertEquals(3.0, fixture.base.queries.getLast().query().vector().getFirst());
      assertEquals(1, fixture.base.drafts.size());
      assertTrue(fixture.base.questions.stream().allMatch(VideoAvTestFixture.QUESTION::equals));
      assertEquals("library", result.result().citations().getFirst().documentId());
      assertFalse(fixture.clips.contains(fixture.base.drafts.getFirst().video()));
      assertFalse(fixture.waveforms.contains(fixture.base.drafts.getFirst().audio()));
    }
  }

  @ParameterizedTest
  @EnumSource(VideoAvMode.class)
  void everyActualReferenceWindowRoutesOnlyToApplicableCollectionAndLibraryProvesQuestion(
      VideoAvMode mode) {
    try (var fixture = new VideoAvQueryTestFixture(directory);
        var answers = fixture.answers()) {
      var library = fixture.base.register("library", 1, 1, 16000, true);
      var reference = fixture.reference("motion-and-sound", 3, 2, 32001);
      fixture.beforeProvider =
          () -> assertEquals(List.of(reference.sha256()), fixture.queryDecoded);
      var result =
          answers.answerAttached(
              VideoAvTestFixture.OWNER, command(mode, "library"), List.of(reference));
      assertEquals("answered", result.result().status());
      assertEquals(mode.name(), result.mode());
      assertEquals(mode.name(), result.result().mode());
      assertEquals(mode == VideoAvMode.AUDIO ? 0 : 2, fixture.clips.size());
      assertEquals(mode == VideoAvMode.VISUAL ? 0 : 3, fixture.waveforms.size());
      assertEquals(1, fixture.base.textEmbeds);
      assertEquals(2, fixture.base.decodes, "One reference decode plus one library proof decode");
      if (mode != VideoAvMode.VISUAL) {
        assertArrayEquals(new byte[32000], fixture.waveforms.get(1).pcm());
        assertEquals(32001, fixture.waveforms.getLast().endSample());
      }
      assertEquals(3, result.queryAttachments().getFirst().windowCount());
      assertEquals("prepared", result.queryAttachments().getFirst().status());
      assertEquals(1, fixture.base.drafts.size());
      assertEquals(1, fixture.base.verifies.size());
      assertEquals("library", result.result().citations().getFirst().documentId());
      assertFalse(fixture.clips.contains(fixture.base.drafts.getFirst().video()));
      assertTrue(fixture.base.questions.stream().allMatch(VideoAvTestFixture.QUESTION::equals));
      assertArrayEquals(
          library.content(),
          answers.content(VideoAvTestFixture.OWNER, result.result().answerId(), 1).content());
      assertEquals(1, fixture.base.count("SELECT count(*) FROM documents"));
      assertEquals(1, fixture.base.count("SELECT count(*) FROM video_av_query_preparations"));
      assertEquals(1, fixture.base.count("SELECT count(*) FROM video_av_query_attachments"));
      assertEquals(0, fixture.base.count("SELECT count(*) FROM ingestion_jobs"));
    }
  }

  @Test
  void threeReferencesAreAllPreparedBeforeFirstProviderAndRepeatedSourceKeepsOrdinal() {
    try (var fixture = new VideoAvQueryTestFixture(directory);
        var answers = fixture.answers()) {
      fixture.base.register("library", 1, 1, 16000, true);
      var first = fixture.reference("first", 1, 1, 16000);
      var last = fixture.reference("last", 3, 2, 32001);
      fixture.beforeProvider =
          () ->
              assertEquals(
                  List.of(first.sha256(), first.sha256(), last.sha256()), fixture.queryDecoded);
      var result =
          answers.answerAttached(
              VideoAvTestFixture.OWNER,
              command(VideoAvMode.JOINT, "library"),
              List.of(first, first, last));
      assertEquals("answered", result.result().status());
      assertEquals(4, fixture.clips.size());
      assertEquals(5, fixture.waveforms.size());
      assertEquals(
          List.of(0, 1, 2), result.queryAttachments().stream().map(a -> a.ordinal()).toList());
      assertEquals(first.sha256(), result.queryAttachments().get(1).sourceSha256());
      assertEquals(3, fixture.base.count("SELECT count(*) FROM video_av_query_attachments"));
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = VideoAvMode.class,
      names = {"AUDIO", "JOINT"})
  void anyReferenceMissingRequiredAudioMakesEntireGroupNotPreparedAndZeroProviders(
      VideoAvMode mode) {
    try (var fixture = new VideoAvQueryTestFixture(directory);
        var answers = fixture.answers()) {
      fixture.base.register("library", 1, 1, 16000, true);
      var audio = fixture.reference("first", 1, 1, 16000);
      var visual = fixture.reference("no-audio", 1, 1, 0);
      var result =
          answers.answerAttached(
              VideoAvTestFixture.OWNER, command(mode, "library"), List.of(audio, visual));
      assertEquals("query_modality_missing", result.result().reasonCode());
      notPrepared(result);
      assertEquals(0, fixture.base.textEmbeds);
      assertTrue(fixture.clips.isEmpty());
      assertTrue(fixture.waveforms.isEmpty());
      assertEquals(2, fixture.base.count("SELECT count(*) FROM video_av_query_attachments"));
    }
  }

  @Test
  void visualModeAcceptsNoAudioReferenceWithExplicitPreparedAbsence() {
    try (var fixture = new VideoAvQueryTestFixture(directory);
        var answers = fixture.answers()) {
      fixture.base.register("library", 1, 1, 0, true);
      var reference = fixture.reference("no-audio", 2, 2, 0);
      var result =
          answers.answerAttached(
              VideoAvTestFixture.OWNER, command(VideoAvMode.VISUAL, "library"), List.of(reference));
      assertEquals("answered", result.result().status());
      assertEquals(false, result.queryAttachments().getFirst().audioPresent());
      assertEquals(0, result.queryAttachments().getFirst().audioWindowCount());
      assertTrue(fixture.waveforms.isEmpty());
    }
  }

  @Test
  void invalidLastCompilationNeverMakesAnEarlierReferencePreparedOrCallsProvider() {
    try (var fixture = new VideoAvQueryTestFixture(directory);
        var answers = fixture.answers()) {
      fixture.base.register("library", 1, 1, 16000, true);
      var first = fixture.reference("first", 1, 1, 16000);
      var last = fixture.reference("bad-tail", 1, 1, 16000);
      fixture.failedSource = last.sha256();
      var result =
          answers.answerAttached(
              VideoAvTestFixture.OWNER,
              command(VideoAvMode.JOINT, "library"),
              List.of(first, last));
      assertEquals("parser_invalid_output", result.result().reasonCode());
      notPrepared(result);
      assertEquals(0, fixture.base.textEmbeds);
      assertTrue(fixture.base.queries.isEmpty());
    }
  }

  @Test
  void emptySelectedScopeRetainsAllInputHashesWithoutDecodingAndNeverExpandsToAll() {
    try (var fixture = new VideoAvQueryTestFixture(directory);
        var answers = fixture.answers()) {
      fixture.base.register("library", 1, 1, 16000, true);
      var first = fixture.reference("first", 1, 1, 16000);
      var result =
          answers.answerAttached(
              VideoAvTestFixture.OWNER, command(VideoAvMode.JOINT), List.of(first, first));
      assertEquals("empty_scope", result.result().reasonCode());
      notPrepared(result);
      assertEquals(0, fixture.base.decodes);
      assertEquals(0, fixture.base.textEmbeds);
      assertEquals(0, fixture.base.count("SELECT count(*) FROM video_av_trace_documents"));
      assertEquals(2, fixture.base.count("SELECT count(*) FROM video_av_query_attachments"));
    }
  }

  @Test
  void missingUncitedLibraryIndexAndForeignWorkspaceSelectionFailBeforeReferenceDecode() {
    try (var fixture = new VideoAvQueryTestFixture(directory);
        var answers = fixture.answers()) {
      fixture.base.register("built", 1, 1, 16000, true);
      fixture.base.register("missing", 1, 1, 16000, false);
      var reference = fixture.reference("reference", 1, 1, 16000);
      assertEquals(
          "video_av_index_required",
          assertThrows(
                  ApplicationException.class,
                  () ->
                      answers.answerAttached(
                          VideoAvTestFixture.OWNER,
                          VideoAvAnswerServiceTest.all(VideoAvMode.JOINT),
                          List.of(reference)))
              .code());
      assertThrows(
          ApplicationException.class,
          () ->
              answers.answerAttached(
                  new Actor("other-workspace", "stranger"),
                  command(VideoAvMode.JOINT, "built"),
                  List.of(reference)));
      assertEquals(0, fixture.base.decodes);
      assertEquals(0, fixture.base.textEmbeds);
      assertEquals(0, fixture.base.count("SELECT count(*) FROM video_av_traces"));
    }
  }

  @Test
  void aggregateDurationLimitIsCheckedAfterFullPreparationAndBeforeAnyProvider() {
    try (var fixture = new VideoAvQueryTestFixture(directory);
        var answers = fixture.answers()) {
      fixture.base.register("library", 1, 1, 16000, true);
      var first = fixture.reference("long-a", 301, 301, 0);
      var last = fixture.reference("long-b", 300, 300, 0);
      var result =
          answers.answerAttached(
              VideoAvTestFixture.OWNER,
              command(VideoAvMode.VISUAL, "library"),
              List.of(first, last));
      assertEquals("query_preparation_limit", result.result().reasonCode());
      assertEquals(2, fixture.base.decodes);
      assertEquals(0, fixture.base.textEmbeds);
      notPrepared(result);
    }
  }

  @Test
  void aggregateFailureStopsBeforeThirdDecodeButPersistsAllThreeInputHashes() throws Exception {
    try (var fixture = new VideoAvQueryTestFixture(directory);
        var answers = fixture.answers()) {
      fixture.base.register("library", 1, 1, 16000, true);
      var first = fixture.reference("long-a", 301, 301, 0);
      var second = fixture.reference("long-b", 300, 300, 0);
      var third = fixture.reference("unnecessary-third", 1, 1, 0);
      var result =
          answers.answerAttached(
              VideoAvTestFixture.OWNER,
              command(VideoAvMode.VISUAL, "library"),
              List.of(first, second, third));
      assertEquals("query_preparation_limit", result.result().reasonCode());
      assertEquals(List.of(first.sha256(), second.sha256()), fixture.queryDecoded);
      assertEquals(
          List.of(first.sha256(), second.sha256(), third.sha256()),
          result.queryAttachments().stream().map(a -> a.sourceSha256()).toList());
      notPrepared(result);
      assertEquals(0, fixture.base.textEmbeds);
      assertEquals(3, fixture.base.count("SELECT count(*) FROM video_av_query_attachments"));
      try (var connection =
              DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
          var statement = connection.createStatement();
          var rows =
              statement.executeQuery(
                  "SELECT source_sha256,status FROM video_av_query_attachments ORDER BY ordinal")) {
        for (var input : List.of(first, second, third)) {
          assertTrue(rows.next());
          assertEquals(input.sha256(), rows.getString("source_sha256"));
          assertEquals("not_prepared", rows.getString("status"));
        }
        assertFalse(rows.next());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"unknown-tail", "duplicate-tail", "wrong-route"})
  void badLastQueryCandidateRefusesWithoutLibraryProofAndKeepsPreparedManifests(String failure) {
    try (var fixture = new VideoAvQueryTestFixture(directory);
        var answers = fixture.answers()) {
      fixture.base.register("library", 1, 1, 16000, true);
      var reference = fixture.reference("reference", 1, 1, 16000);
      var w = fixture.base.publications.getFirst().windows().getFirst();
      fixture.base.search =
          (route, query) -> {
            if (query.vector().getFirst() == 1.0) {
              return List.of();
            }
            String first =
                route == VideoAvRoute.VISUAL ? w.visualPhysicalId() : w.audioPhysicalId();
            String tail =
                switch (failure) {
                  case "unknown-tail" -> "outside-scope";
                  case "duplicate-tail" -> first;
                  default ->
                      route == VideoAvRoute.VISUAL ? w.audioPhysicalId() : w.visualPhysicalId();
                };
            return List.of(
                new RetrievalProjection.Candidate(first, 1),
                new RetrievalProjection.Candidate(tail, .1));
          };
      var result =
          answers.answerAttached(
              VideoAvTestFixture.OWNER, command(VideoAvMode.JOINT, "library"), List.of(reference));
      assertEquals("upstream_invalid", result.result().reasonCode());
      assertEquals("prepared", result.queryAttachments().getFirst().status());
      assertTrue(fixture.base.drafts.isEmpty());
      assertTrue(result.result().citations().isEmpty());
    }
  }

  @Test
  void attachmentOnlyOrHalfQuestionFactsNeverEnterLibraryProof() {
    try (var fixture = new VideoAvQueryTestFixture(directory);
        var answers = fixture.answers()) {
      fixture.base.register("library", 1, 1, 16000, true);
      var reference = fixture.reference("secret-only-in-query", 1, 1, 16000);
      fixture.base.draft =
          (window, mode) ->
              new VideoAvModels.Draft(
                  false,
                  List.of(new VideoAvModels.Claim("只有部分原问题得到支持。", VideoAvRequirement.JOINT)));
      var result =
          answers.answerAttached(
              VideoAvTestFixture.OWNER, command(VideoAvMode.JOINT, "library"), List.of(reference));
      assertEquals("incomplete_evidence", result.result().reasonCode());
      assertEquals("prepared", result.queryAttachments().getFirst().status());
      assertTrue(fixture.base.verifies.isEmpty());
      assertTrue(result.result().citations().isEmpty());
      assertFalse(fixture.clips.contains(fixture.base.drafts.getFirst().video()));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"withdrawn", "profile"})
  void referenceEmbeddingCannotCommitAfterUncitedScopeOrProfileChanges(String change) {
    try (var fixture = new VideoAvQueryTestFixture(directory);
        var answers = fixture.answers()) {
      fixture.base.register("library", 1, 1, 16000, true);
      fixture.base.register("uncited", 1, 1, 0, true);
      var reference = fixture.reference("reference", 1, 1, 16000);
      fixture.onClip =
          clip -> {
            if (change.equals("withdrawn")) {
              fixture.base.sql(
                  "INSERT INTO document_tombstones SELECT id,workspace_id,'owner','2026-10-08T00:00:00Z' FROM documents WHERE id='uncited'");
            } else {
              fixture.base.embeddingRevision = "changed";
            }
          };
      assertEquals(
          change.equals("withdrawn") ? "scope_changed" : "configuration_changed",
          assertThrows(
                  ApplicationException.class,
                  () ->
                      answers.answerAttached(
                          VideoAvTestFixture.OWNER,
                          VideoAvAnswerServiceTest.all(VideoAvMode.JOINT),
                          List.of(reference)))
              .code());
      assertEquals(0, fixture.base.count("SELECT count(*) FROM video_av_traces"));
      assertEquals(0, fixture.base.count("SELECT count(*) FROM video_av_query_preparations"));
    }
  }

  @Test
  void timeoutCancelsInFlightReferenceEmbeddingWithoutLateTrace() throws Exception {
    var entered = new CountDownLatch(1);
    var stopped = new CountDownLatch(1);
    try (var fixture = new VideoAvQueryTestFixture(directory);
        var answers = fixture.answers(Duration.ofSeconds(1))) {
      fixture.base.register("library", 1, 1, 16000, true);
      var reference = fixture.reference("reference", 1, 1, 16000);
      fixture.onClip =
          clip -> {
            entered.countDown();
            try {
              new CountDownLatch(1).await();
            } catch (InterruptedException cancelled) {
              Thread.currentThread().interrupt();
            } finally {
              stopped.countDown();
            }
          };
      var task =
          new FutureTask<>(
              () ->
                  assertThrows(
                          ApplicationException.class,
                          () ->
                              answers.answerAttached(
                                  VideoAvTestFixture.OWNER,
                                  command(VideoAvMode.JOINT, "library"),
                                  List.of(reference)))
                      .code());
      Thread.ofVirtual().start(task);
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      assertEquals("answer_timeout", task.get(3, TimeUnit.SECONDS));
      assertTrue(stopped.await(2, TimeUnit.SECONDS));
      assertEquals(0, fixture.base.count("SELECT count(*) FROM video_av_traces"));
    }
  }

  private static VideoAvAnswerCommand command(VideoAvMode mode, String... ids) {
    return new VideoAvAnswerCommand(
        new AnswerCommand(VideoAvTestFixture.QUESTION, DocumentSelection.selected(List.of(ids))),
        mode);
  }

  private static void notPrepared(VideoAvQueryAnswerResult result) {
    assertTrue(result.queryAttachments().stream().allMatch(a -> a.status().equals("not_prepared")));
    for (var a : result.queryAttachments()) {
      assertNull(a.contentSha256());
      assertNull(a.windowCount());
      assertNull(a.visualWindowCount());
      assertNull(a.audioWindowCount());
      assertNull(a.audioPresent());
    }
    assertTrue(result.result().citations().isEmpty());
  }
}
