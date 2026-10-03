package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.VideoAvMode;
import com.evidence.rag.model.domain.VideoAvProofIdentity;
import com.evidence.rag.model.domain.VideoAvQueryManifest;
import com.evidence.rag.model.domain.VideoAvQueryTrace;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.model.dto.VideoAvAnswerCommand;
import com.evidence.rag.model.dto.VideoAvQueryAnswerResult;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.repository.VideoAvRepository;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real temporary SQLite: complete hash groups, sealing, restart and fail-closed source reads. */
class VideoAvQueryTracePersistenceTest {
  @TempDir Path directory;

  @Test
  void failedTailPersistsEveryInputHashAndNoPartialPreparedDetails() throws Exception {
    try (var fixture = new VideoAvQueryTestFixture(directory);
        var answers = fixture.answers()) {
      fixture.base.register("library", 1, 1, 16000, true);
      var first = fixture.reference("first", 1, 1, 16000);
      var last = fixture.reference("bad-tail", 1, 1, 16000);
      fixture.failedSource = last.sha256();
      var result =
          answers.answerAttached(VideoAvTestFixture.OWNER, command(), List.of(first, last));
      assertEquals("abstained", result.result().status());
      assertEquals("parser_invalid_output", result.result().reasonCode());
      assertSavedGroup(result);
      try (var connection = DriverManager.getConnection(database());
          var statement = connection.createStatement();
          var rows =
              statement.executeQuery("SELECT * FROM video_av_query_attachments ORDER BY ordinal")) {
        for (var expected : List.of(first, last)) {
          assertTrue(rows.next());
          assertEquals(expected.sha256(), rows.getString("source_sha256"));
          assertEquals("not_prepared", rows.getString("status"));
          for (String field :
              List.of(
                  "content_sha256",
                  "window_count",
                  "visual_window_count",
                  "audio_window_count",
                  "audio_present")) {
            assertNull(rows.getObject(field));
          }
        }
        assertFalse(rows.next());
      }
      assertEquals(0, fixture.base.textEmbeds);
      assertEquals(0, fixture.base.count("SELECT count(*) FROM video_av_trace_evidence"));
    }
  }

  @Test
  void completeRepeatedInputGroupAndLibrarySourceSurviveRestartWithoutQueryProviders()
      throws Exception {
    String traceId;
    byte[] original;
    try (var fixture = new VideoAvQueryTestFixture(directory);
        var answers = fixture.answers()) {
      original = fixture.base.register("library", 1, 1, 16000, true).content();
      var input = fixture.reference("query", 2, 2, 32000);
      var result =
          answers.answerAttached(VideoAvTestFixture.OWNER, command(), List.of(input, input));
      assertEquals("answered", result.result().status());
      traceId = result.result().answerId();
      assertSavedGroup(result);
      assertEquals(1, fixture.base.count("SELECT count(*) FROM video_av_traces"));
      assertEquals(1, fixture.base.count("SELECT count(*) FROM documents"));
      assertEquals(1, fixture.base.count("SELECT count(*) FROM video_av_publications"));
      assertEquals(3, fixture.base.decodes);
    }
    try (var store = new SqliteAuthorityStore(directory)) {
      var repository = new VideoAvRepository(store);
      var source =
          store.transaction(
              () ->
                  repository.source(
                      VideoAvTestFixture.OWNER,
                      traceId,
                      1,
                      VideoAvTestFixture.TARGETS,
                      VideoAvTestFixture.PROFILE));
      assertArrayEquals(original, source.original().content());
      assertEquals("library", source.proof().evidence().publication().documentId());
      assertThrows(
          ApplicationException.class,
          () ->
              store.transaction(
                  () ->
                      repository.source(
                          new Actor(VideoAvTestFixture.OWNER.workspaceId(), "other"),
                          traceId,
                          1,
                          VideoAvTestFixture.TARGETS,
                          VideoAvTestFixture.PROFILE)));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "manifest",
        "profile",
        "question",
        "mode",
        "count",
        "fractional-count",
        "policy",
        "embedding"
      })
  void corruptedHeaderCannotReleasePreviouslyValidLibrarySource(String change) throws Exception {
    try (var fixture = new VideoAvQueryTestFixture(directory);
        var answers = fixture.answers()) {
      fixture.base.register("library", 1, 1, 16000, true);
      var input = fixture.reference("query", 1, 1, 16000);
      var result =
          answers.answerAttached(VideoAvTestFixture.OWNER, command(), List.of(input, input));
      String assignment =
          switch (change) {
            case "manifest" -> "manifest_sha256='" + "0".repeat(64) + "'";
            case "profile" -> "profile_fingerprint='" + "0".repeat(64) + "'";
            case "question" -> "question_sha256='" + "0".repeat(64) + "'";
            case "mode" -> "mode='VISUAL'";
            case "count" -> "attachment_count=1";
            case "fractional-count" -> "attachment_count=2.5";
            case "policy" -> "preparation_revision='java-video-av-query-preparation-v0'";
            default -> "embedding_revision='synthetic-foreign-space-v1'";
          };
      corruptAfterGuardRejects("video_av_query_preparations", assignment, "");
      assertThrows(
          ApplicationException.class,
          () -> answers.source(VideoAvTestFixture.OWNER, result.result().answerId(), 1));
      assertThrows(
          ApplicationException.class,
          () -> answers.content(VideoAvTestFixture.OWNER, result.result().answerId(), 1));
      assertEquals(3, fixture.base.decodes);
      assertEquals(1, fixture.base.textEmbeds);
      assertEquals(1, fixture.base.drafts.size());
      assertEquals(1, fixture.base.verifies.size());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "tail-order",
        "tail-source",
        "tail-count",
        "tail-mode",
        "tail-status",
        "tail-audio",
        "fractional-window",
        "fractional-ordinal",
        "fractional-audio",
        "overflow-count"
      })
  void corruptedUncitedQueryTailInvalidatesWholeSourceReceipt(String change) throws Exception {
    try (var fixture = new VideoAvQueryTestFixture(directory);
        var answers = fixture.answers()) {
      fixture.base.register("library", 1, 1, 16000, true);
      var first = fixture.reference("head", 1, 1, 16000);
      var last = fixture.reference("tail", 2, 2, 32000);
      var result =
          answers.answerAttached(VideoAvTestFixture.OWNER, command(), List.of(first, last));
      String assignment =
          switch (change) {
            case "tail-order" -> "ordinal=2";
            case "tail-source" -> "source_sha256='" + "0".repeat(64) + "'";
            case "tail-count" -> "window_count=3";
            case "tail-mode" -> "used_mode='VISUAL'";
            case "tail-status" ->
                "status='not_prepared',content_sha256=NULL,window_count=NULL,"
                    + "visual_window_count=NULL,audio_window_count=NULL,audio_present=NULL";
            case "fractional-window" -> "window_count=2.5";
            case "fractional-ordinal" -> "ordinal=1.5";
            case "fractional-audio" -> "audio_present=1.5";
            case "overflow-count" -> "window_count=4294967298";
            default -> "audio_present=2";
          };
      corruptAfterGuardRejects("video_av_query_attachments", assignment, " WHERE ordinal=1");
      assertThrows(
          ApplicationException.class,
          () -> answers.source(VideoAvTestFixture.OWNER, result.result().answerId(), 1));
      assertThrows(
          ApplicationException.class,
          () -> answers.content(VideoAvTestFixture.OWNER, result.result().answerId(), 1));
      assertEquals(3, fixture.base.decodes);
      assertEquals(1, fixture.base.textEmbeds);
    }
  }

  @Test
  void originalEightArgumentTraceStillReadsWithoutAnyQuerySidecar() {
    try (var fixture = new VideoAvTestFixture(directory);
        var answers = fixture.answers()) {
      var original = fixture.register("library", 1, 1, 16000, true);
      var result = answers.answer(VideoAvTestFixture.OWNER, command());
      assertEquals("answered", result.status());
      assertArrayEquals(
          original.content(),
          answers.content(VideoAvTestFixture.OWNER, result.answerId(), 1).content());
      assertEquals(0, fixture.count("SELECT count(*) FROM video_av_query_preparations"));
      assertEquals(0, fixture.count("SELECT count(*) FROM video_av_query_attachments"));
    }
  }

  private void assertSavedGroup(VideoAvQueryAnswerResult result) throws Exception {
    var attachments =
        result.queryAttachments().stream()
            .map(
                a ->
                    new VideoAvQueryManifest(
                        a.ordinal(),
                        a.sourceSha256(),
                        a.mediaKind(),
                        a.compilerRevision(),
                        a.contentSha256(),
                        a.windowCount(),
                        a.visualWindowCount(),
                        a.audioWindowCount(),
                        a.audioPresent(),
                        VideoAvMode.valueOf(a.usedMode()),
                        a.status()))
            .toList();
    var expected =
        new VideoAvQueryTrace(
            VideoAvMode.JOINT,
            VideoAvProofIdentity.sha(VideoAvTestFixture.QUESTION),
            VideoAvTestFixture.PROFILE,
            VideoAvTestFixture.EMBEDDING,
            attachments);
    try (var connection = DriverManager.getConnection(database());
        var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT * FROM video_av_query_preparations")) {
      assertTrue(rows.next());
      assertEquals(result.result().answerId(), rows.getString("trace_id"));
      assertEquals(attachments.size(), rows.getInt("attachment_count"));
      assertEquals("JOINT", rows.getString("mode"));
      assertEquals(expected.questionSha256(), rows.getString("question_sha256"));
      assertEquals(expected.profileFingerprint(), rows.getString("profile_fingerprint"));
      assertEquals(expected.embeddingRevision(), rows.getString("embedding_revision"));
      assertEquals(expected.preparationRevision(), rows.getString("preparation_revision"));
      assertEquals(expected.manifestSha256(), rows.getString("manifest_sha256"));
      assertFalse(rows.next());
    }
  }

  private void corruptAfterGuardRejects(String table, String assignment, String where)
      throws Exception {
    String update = "UPDATE " + table + " SET " + assignment + where;
    try (var connection = DriverManager.getConnection(database());
        var statement = connection.createStatement()) {
      assertThrows(SQLException.class, () -> statement.execute(update));
      var triggers = new ArrayList<String>();
      try (var rows =
          statement.executeQuery(
              "SELECT name FROM sqlite_master WHERE type='trigger' AND tbl_name='" + table + "'")) {
        while (rows.next()) {
          triggers.add(rows.getString(1));
        }
      }
      for (String trigger : triggers) {
        statement.execute("DROP TRIGGER " + trigger);
      }
      statement.execute("PRAGMA ignore_check_constraints=ON");
      statement.execute(update);
    }
  }

  private String database() {
    return "jdbc:sqlite:" + directory.resolve("java-library.db");
  }

  private static VideoAvAnswerCommand command() {
    return new VideoAvAnswerCommand(
        new AnswerCommand(
            VideoAvTestFixture.QUESTION, DocumentSelection.selected(List.of("library"))),
        VideoAvMode.JOINT);
  }
}
