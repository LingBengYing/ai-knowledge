package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VideoAvEvidence;
import com.evidence.rag.model.domain.VideoAvMode;
import com.evidence.rag.model.domain.VideoAvProof;
import com.evidence.rag.model.domain.VideoAvProofIdentity;
import com.evidence.rag.model.domain.VideoAvRoute;
import com.evidence.rag.model.domain.VideoAvTraceDraft;
import com.evidence.rag.repository.SqliteAuthorityStore;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.Function;

class VideoAvScopeSealBoundaryTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"revision", "source-bytes", "filename", "media-type"})
  void publicationLookupRequiresExactSavedOriginalIdentity(String changed) {
    try (var fixture = new VideoAvTestFixture(directory)) {
      var original = fixture.register("library", 1, 1, 16000, true);
      byte[] bytes = original.content();
      if (changed.equals("source-bytes")) {
        bytes[bytes.length - 1] ^= 1;
      }
      var alternate =
          new DocumentOriginal(
              original.documentId(),
              changed.equals("revision") ? "another-revision" : original.revisionId(),
              changed.equals("filename") ? "another.mp4" : original.filename(),
              "video",
              changed.equals("media-type") ? "video/webm" : original.mediaType(),
              ModelValues.sha256(bytes),
              bytes.length,
              bytes);
      assertTrue(
          fixture
              .store
              .transaction(
                  () ->
                      fixture.repository.findPublication(
                          original, VideoAvTestFixture.TARGETS, VideoAvTestFixture.PROFILE))
              .isPresent());
      assertTrue(
          fixture
              .store
              .transaction(
                  () ->
                      fixture.repository.findPublication(
                          alternate, VideoAvTestFixture.TARGETS, VideoAvTestFixture.PROFILE))
              .isEmpty());
      assertEquals(1, fixture.count("SELECT count(*) FROM video_av_publications"));
      assertEquals(0, fixture.decodes);
    }
  }

  @Test
  void emptyAllScopeCannotHydrateOrSealAfterNewRawVideoArrives() {
    try (var fixture = new VideoAvTestFixture(directory)) {
      var all = VideoAvStoredSourceBoundaryTest.scope(fixture, DocumentSelection.allDocuments());
      var selected =
          VideoAvStoredSourceBoundaryTest.scope(fixture, DocumentSelection.selected(List.of()));
      assertTrue(
          fixture
              .store
              .transaction(() -> fixture.repository.hydrate(all, VideoAvRoute.VISUAL, List.of()))
              .isEmpty());
      fixture.register("new-no-audio", 1, 1, 0, false);
      var refusal =
          new VideoAvTraceDraft(
              VideoAvProofIdentity.sha(VideoAvTestFixture.QUESTION),
              null,
              "abstained",
              "empty_scope",
              VideoAvMode.JOINT,
              List.of(),
              VideoAvTestFixture.MODEL,
              VideoAvAnswerService.POLICY_REVISION);
      assertEquals(
          "scope_changed",
          assertThrows(
                  ApplicationException.class,
                  () ->
                      fixture.store.transaction(
                          () -> fixture.repository.hydrate(all, VideoAvRoute.VISUAL, List.of())))
              .code());
      assertThrows(
          ApplicationException.class,
          () -> fixture.store.transaction(() -> fixture.repository.finish(all, refusal)));
      assertEquals(0, fixture.count("SELECT count(*) FROM video_av_traces"));
      assertEquals(
          "abstained",
          fixture.store.transaction(() -> fixture.repository.finish(selected, refusal)).status());
      assertEquals(0, fixture.count("SELECT count(*) FROM video_av_trace_documents"));
      assertEquals(0, fixture.textEmbeds);
    }
  }

  @Test
  void libraryBeyond128ProceedsToMissingReceiptCheckWithoutProviderWork() {
    try (var fixture = new VideoAvTestFixture(directory);
        var answers = fixture.answers()) {
      for (int index = 0; index < 129; index++) {
        fixture.register("raw-" + index, 1, 1, 0, false);
      }
      assertEquals(
          "video_av_index_required",
          assertThrows(
                  ApplicationException.class,
                  () ->
                      answers.answer(
                          VideoAvTestFixture.OWNER,
                          VideoAvAnswerServiceTest.all(VideoAvMode.AUDIO)))
              .code());
      assertEquals(0, fixture.decodes);
      assertEquals(0, fixture.textEmbeds);
      assertTrue(fixture.queries.isEmpty());
      assertEquals(0, fixture.count("SELECT count(*) FROM video_av_traces"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"foreign-publication", "duplicate-window", "model-rebinding"})
  void sealRejectsProofsOutsideItsExactScopeOrRepeatedSourceAndModel(String changed) {
    try (var fixture = new VideoAvTestFixture(directory)) {
      fixture.register("selected", 1, 1, 16000, true);
      fixture.register("other", 1, 1, 16000, true);
      var scope =
          VideoAvStoredSourceBoundaryTest.scope(
              fixture, DocumentSelection.selected(List.of("selected")));
      var publication = fixture.publications.get(changed.equals("foreign-publication") ? 1 : 0);
      VideoAvProof proof =
          VideoAvStoredSourceBoundaryTest.proof(
              new VideoAvEvidence(publication, publication.windows().getFirst()));
      var proofs = changed.equals("duplicate-window") ? List.of(proof, proof) : List.of(proof);
      var draft =
          changed.equals("model-rebinding")
              ? new VideoAvTraceDraft(
                  VideoAvProofIdentity.sha(VideoAvTestFixture.QUESTION),
                  VideoAvProofIdentity.sha(VideoAvTestFixture.FACT),
                  "answered",
                  null,
                  VideoAvMode.JOINT,
                  proofs,
                  "another-analysis-model",
                  VideoAvAnswerService.POLICY_REVISION)
              : VideoAvStoredSourceBoundaryTest.draft(proofs);
      assertThrows(
          ApplicationException.class,
          () -> fixture.store.transaction(() -> fixture.repository.finish(scope, draft)));
      assertEquals(0, fixture.count("SELECT count(*) FROM video_av_traces"));
      assertEquals(0, fixture.count("SELECT count(*) FROM video_av_trace_documents"));
      assertEquals(0, fixture.count("SELECT count(*) FROM video_av_trace_evidence"));
    }
  }

  @Test
  void hydrateAcceptsWholeSixtyFourButNeverTruncatesSixtyFiveAuthorizedIds() {
    try (var fixture = new VideoAvTestFixture(directory)) {
      fixture.register("library", 65, 65, 0, true);
      var scope = VideoAvStoredSourceBoundaryTest.scope(fixture, DocumentSelection.allDocuments());
      var ids =
          scope.publications().getFirst().windows().stream()
              .map(window -> window.visualPhysicalId())
              .toList();
      var first =
          fixture.store.transaction(
              () -> fixture.repository.hydrate(scope, VideoAvRoute.VISUAL, ids.subList(0, 64)));
      assertEquals(64, first.size());
      assertEquals(63, first.getLast().window().ordinal());
      assertThrows(
          ApplicationException.class,
          () ->
              fixture.store.transaction(
                  () -> fixture.repository.hydrate(scope, VideoAvRoute.VISUAL, ids)));
      assertEquals(0, fixture.count("SELECT count(*) FROM video_av_traces"));
    }
  }

  @Test
  void audioOriginalCannotBeRegisteredInTheGenuineVideoAuthority() {
    try (var fixture = new VideoAvTestFixture(directory)) {
      byte[] bytes = {1, 2, 3};
      var audio =
          new DocumentOriginal(
              "audio",
              "revision",
              "sound.wav",
              "audio",
              "audio/wav",
              ModelValues.sha256(bytes),
              bytes.length,
              bytes);
      assertThrows(
          ApplicationException.class,
          () ->
              fixture.store.transaction(
                  () -> {
                    fixture.repository.insertOriginal(audio, "2026-10-03T00:00:00Z");
                    return null;
                  }));
      assertEquals(0, fixture.count("SELECT count(*) FROM video_av_originals"));
      assertFalse(
          fixture.store.transaction(() -> fixture.repository.managedEvidence("audio")).isPresent());
    }
  }

  @Test
  void uncitedSameLengthRawCorruptionAfterVerificationRejectsTheWholeTrace() {
    try (var fixture = new VideoAvTestFixture(directory);
        var answers = fixture.answers()) {
      fixture.register("cited", 1, 1, 16000, true);
      var uncited = fixture.register("uncited", 1, 1, 0, true);
      byte[] changed = uncited.content();
      changed[changed.length - 1] ^= 1;
      String mutation =
          "UPDATE video_av_originals SET original_blob=x'"
              + HexFormat.of().formatHex(changed)
              + "' WHERE document_id='uncited'";
      var blocked = assertThrows(AssertionError.class, () -> ordinarySql(fixture.store, mutation));
      assertTrue(blocked.getCause() instanceof SQLException);
      assertFalse(
          blocked.getCause().getMessage().contains("no such function"),
          blocked.getCause().getMessage());
      // Only this synthetic DB's ordinary and v22 blob guards are removed for offline corruption.
      ordinarySql(fixture.store, "DROP TRIGGER video_av_originals_no_update");
      ordinarySql(fixture.store, "DROP TRIGGER cleanup_video_av_originals_purge");
      fixture.afterVerify = () -> ordinarySql(fixture.store, mutation);
      assertEquals(
          "scope_changed",
          assertThrows(
                  ApplicationException.class,
                  () ->
                      answers.answer(
                          VideoAvTestFixture.OWNER,
                          VideoAvAnswerServiceTest.all(VideoAvMode.JOINT)))
              .code());
      assertEquals(1, fixture.verifies.size());
      assertEquals(0, fixture.count("SELECT count(*) FROM video_av_traces"));
      assertEquals(0, fixture.count("SELECT count(*) FROM video_av_trace_evidence"));
    }
  }

  private static void ordinarySql(SqliteAuthorityStore store, String sql) {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + store.libraryPath())) {
      // This direct connection has no authorized replacement transaction. Register the v26
      // predicate as false so the actual original-blob guards reject ordinary writes.
      Function.create(
          connection,
          "java_replacement_authorized",
          new Function() {
            @Override
            protected void xFunc() throws SQLException {
              result(0);
            }
          });
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
}
