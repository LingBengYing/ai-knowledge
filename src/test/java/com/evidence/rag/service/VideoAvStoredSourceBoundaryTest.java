package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.VideoAvEvidence;
import com.evidence.rag.model.domain.VideoAvFact;
import com.evidence.rag.model.domain.VideoAvMode;
import com.evidence.rag.model.domain.VideoAvProof;
import com.evidence.rag.model.domain.VideoAvProofIdentity;
import com.evidence.rag.model.domain.VideoAvRequirement;
import com.evidence.rag.model.domain.VideoAvScope;
import com.evidence.rag.model.domain.VideoAvTraceDraft;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.tool.answer.VideoAvProofBinding;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.Function;

/**
 * Sealed data first rejects normal mutation; isolated corruption then exercises source
 * revalidation.
 */
class VideoAvStoredSourceBoundaryTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "non-object",
        "extra-field",
        "id-number",
        "text-number",
        "requirement-number",
        "visual-string",
        "audio-string",
        "unknown-requirement",
        "noncanonical-spacing"
      })
  void savedFactShapeCannotBecomeAnAlternateSourceContract(String change) {
    try (var fixture = new VideoAvTestFixture(directory);
        var answers = fixture.answers()) {
      fixture.register("library", 1, 1, 16000, true);
      var answer =
          answers.answer(VideoAvTestFixture.OWNER, VideoAvAnswerServiceTest.all(VideoAvMode.JOINT));
      var facts = facts();
      String canonical = VideoAvProofIdentity.canonicalFactsJson(facts);
      String encoded =
          switch (change) {
            case "non-object" -> "[true]";
            case "extra-field" -> canonical.replace("}]", ",\"supported\":true}]");
            case "id-number" ->
                canonical.replace("\"id\":\"" + facts.getFirst().id() + "\"", "\"id\":7");
            case "text-number" ->
                canonical.replace("\"text\":\"" + VideoAvTestFixture.FACT + "\"", "\"text\":7");
            case "requirement-number" ->
                canonical.replace("\"requirement\":\"JOINT\"", "\"requirement\":7");
            case "visual-string" ->
                canonical.replace(
                    "\"visual_contribution\":true", "\"visual_contribution\":\"true\"");
            case "audio-string" ->
                canonical.replace("\"audio_contribution\":true", "\"audio_contribution\":\"true\"");
            case "unknown-requirement" -> canonical.replace("\"JOINT\"", "\"CAPTION\"");
            default -> " " + canonical;
          };
      corruptAfterGuardRejects(
          fixture,
          "video_av_trace_evidence_no_update",
          "UPDATE video_av_trace_evidence SET facts_json=" + literal(encoded));
      assertThrows(
          ApplicationException.class,
          () -> answers.source(VideoAvTestFixture.OWNER, answer.answerId(), 1));
      assertThrows(
          ApplicationException.class,
          () -> answers.content(VideoAvTestFixture.OWNER, answer.answerId(), 1));
      assertEquals(1, fixture.decodes);
      assertEquals(1, fixture.textEmbeds);
      assertEquals(1, fixture.drafts.size());
      assertEquals(1, fixture.verifies.size());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "scope-count",
        "scope-order",
        "citation-count",
        "citation-order",
        "question-hash",
        "answer-hash",
        "proof-hash"
      })
  void savedTraceCountsOrderAndBindingsAreRevalidatedOnEveryRead(String change) {
    try (var fixture = new VideoAvTestFixture(directory);
        var answers = fixture.answers()) {
      fixture.register("library", 1, 1, 16000, true);
      var answer =
          answers.answer(VideoAvTestFixture.OWNER, VideoAvAnswerServiceTest.all(VideoAvMode.JOINT));
      String table =
          switch (change) {
            case "scope-order" -> "video_av_trace_documents";
            case "citation-order", "proof-hash" -> "video_av_trace_evidence";
            default -> "video_av_traces";
          };
      String assignment =
          switch (change) {
            case "scope-count" -> "scope_count=2";
            case "scope-order" -> "ordinal=1";
            case "citation-count" -> "citation_count=2";
            case "citation-order" -> "ordinal=2";
            case "question-hash" -> "question_sha256='" + "0".repeat(64) + "'";
            case "answer-hash" -> "answer_sha256='" + "0".repeat(64) + "'";
            default -> "proof_sha256='" + "0".repeat(64) + "'";
          };
      corruptAfterGuardRejects(
          fixture, table + "_no_update", "UPDATE " + table + " SET " + assignment);
      assertThrows(
          ApplicationException.class,
          () -> answers.source(VideoAvTestFixture.OWNER, answer.answerId(), 1));
      assertEquals(1, fixture.decodes, "Saved source rejection never reparses original media");
      assertEquals(1, fixture.textEmbeds);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"window-count", "window-manifest", "source-sha", "filename", "media-type", "size"})
  void savedPublicationMustStillMatchWholeWindowGroupAndOriginalMetadata(String change) {
    try (var fixture = new VideoAvTestFixture(directory);
        var answers = fixture.answers()) {
      fixture.register("library", 1, 1, 16000, true);
      var answer =
          answers.answer(VideoAvTestFixture.OWNER, VideoAvAnswerServiceTest.all(VideoAvMode.JOINT));
      String assignment =
          switch (change) {
            case "window-count" -> "window_count=2";
            case "window-manifest" -> "window_manifest_sha256='" + "0".repeat(64) + "'";
            case "source-sha" -> "source_sha256='" + "0".repeat(64) + "'";
            case "media-type" -> "media_type='video/webm'";
            case "size" -> "size_bytes=size_bytes+1";
            default -> "filename='different.mp4'";
          };
      corruptAfterGuardRejects(
          fixture,
          "video_av_publications_no_update",
          "UPDATE video_av_publications SET " + assignment);
      assertThrows(
          ApplicationException.class,
          () -> answers.source(VideoAvTestFixture.OWNER, answer.answerId(), 1));
      assertEquals(1, fixture.decodes);
      assertEquals(1, fixture.textEmbeds);
    }
  }

  @Test
  void requestingSecondCitationStillValidatesFirstProofAndAllOriginalBytes() {
    try (var fixture = new VideoAvTestFixture(directory)) {
      var original = fixture.register("library", 2, 2, 32000, true);
      var scope = scope(fixture, DocumentSelection.allDocuments());
      var publication = scope.publications().getFirst();
      var proofs =
          publication.windows().stream()
              .map(window -> proof(new VideoAvEvidence(publication, window)))
              .toList();
      var receipt =
          fixture.store.transaction(() -> fixture.repository.finish(scope, draft(proofs)));
      var second =
          fixture.store.transaction(
              () ->
                  fixture.repository.source(
                      VideoAvTestFixture.OWNER,
                      receipt.traceId(),
                      2,
                      VideoAvTestFixture.TARGETS,
                      VideoAvTestFixture.PROFILE));
      assertEquals(2, second.ordinal());
      assertEquals(1, second.proof().evidence().window().ordinal());
      assertArrayEquals(original.content(), second.original().content());
      assertThrows(
          ApplicationException.class,
          () ->
              fixture.store.transaction(
                  () ->
                      fixture.repository.source(
                          VideoAvTestFixture.OWNER,
                          receipt.traceId(),
                          33,
                          VideoAvTestFixture.TARGETS,
                          VideoAvTestFixture.PROFILE)));
      corruptAfterGuardRejects(
          fixture,
          "video_av_trace_evidence_no_update",
          "UPDATE video_av_trace_evidence SET proof_sha256='"
              + "0".repeat(64)
              + "' WHERE ordinal=1");
      assertThrows(
          ApplicationException.class,
          () ->
              fixture.store.transaction(
                  () ->
                      fixture.repository.source(
                          VideoAvTestFixture.OWNER,
                          receipt.traceId(),
                          2,
                          VideoAvTestFixture.TARGETS,
                          VideoAvTestFixture.PROFILE)));
      assertEquals(0, fixture.decodes);
      assertEquals(0, fixture.textEmbeds);
      assertTrue(fixture.drafts.isEmpty());
    }
  }

  static VideoAvScope scope(VideoAvTestFixture fixture, DocumentSelection selection) {
    return fixture.store.transaction(
        () ->
            fixture.repository.scope(
                VideoAvTestFixture.OWNER,
                selection,
                VideoAvTestFixture.TARGETS,
                VideoAvTestFixture.PROFILE));
  }

  static List<VideoAvFact> facts() {
    String question = VideoAvProofIdentity.sha(VideoAvTestFixture.QUESTION);
    return List.of(
        new VideoAvFact(
            VideoAvProofIdentity.stableFactId(
                question, 0, VideoAvTestFixture.FACT, VideoAvRequirement.JOINT),
            VideoAvTestFixture.FACT,
            VideoAvRequirement.JOINT,
            true,
            true));
  }

  static VideoAvProof proof(VideoAvEvidence evidence) {
    return VideoAvProofBinding.create(
        VideoAvProofIdentity.sha(VideoAvTestFixture.QUESTION),
        evidence,
        VideoAvMode.JOINT,
        facts(),
        VideoAvTestFixture.MODEL,
        VideoAvAnswerService.POLICY_REVISION);
  }

  static VideoAvTraceDraft draft(List<VideoAvProof> proofs) {
    return new VideoAvTraceDraft(
        VideoAvProofIdentity.sha(VideoAvTestFixture.QUESTION),
        VideoAvProofIdentity.sha(VideoAvTestFixture.FACT),
        "answered",
        null,
        VideoAvMode.JOINT,
        proofs,
        VideoAvTestFixture.MODEL,
        VideoAvAnswerService.POLICY_REVISION);
  }

  private static void corruptAfterGuardRejects(
      VideoAvTestFixture fixture, String trigger, String sql) {
    var blocked =
        assertThrows(
            AssertionError.class,
            () -> ordinarySql(fixture.store, sql),
            "Normal immutable guard must reject first");
    assertTrue(blocked.getCause() instanceof SQLException);
    assertFalse(blocked.getCause().getMessage().contains("no such function"));
    ordinarySql(fixture.store, "DROP TRIGGER " + trigger);
    if (trigger.equals("video_av_trace_evidence_no_update") && sql.contains("facts_json=")) {
      ordinarySql(fixture.store, "DROP TRIGGER cleanup_video_av_trace_evidence_purge");
    }
    ordinarySql(fixture.store, sql);
  }

  private static void ordinarySql(SqliteAuthorityStore store, String sql) {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + store.libraryPath())) {
      Function.create(
          connection,
          "java_cleanup_authorized",
          new Function() {
            @Override
            protected void xFunc() throws SQLException {
              result(0);
            }
          });
      try (var statement = connection.createStatement()) {
        statement.execute(sql);
      }
    } catch (SQLException failure) {
      throw new AssertionError(failure);
    }
  }

  private static String literal(String text) {
    return "'" + text.replace("'", "''") + "'";
  }
}
