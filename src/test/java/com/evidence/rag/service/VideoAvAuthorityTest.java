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
import com.evidence.rag.model.domain.VideoAvFact;
import com.evidence.rag.model.domain.VideoAvMode;
import com.evidence.rag.model.domain.VideoAvProofIdentity;
import com.evidence.rag.model.domain.VideoAvRequirement;
import com.evidence.rag.model.domain.VideoAvRoute;
import com.evidence.rag.model.domain.VideoAvScope;
import com.evidence.rag.model.domain.VideoAvTraceDraft;
import com.evidence.rag.model.dto.DocumentPatchCommand;
import com.evidence.rag.model.dto.VideoAvCitationResult;
import com.evidence.rag.model.query.DocumentQuery;
import com.evidence.rag.repository.CleanupMaintenanceFixture;
import com.evidence.rag.repository.DocumentLifecycleRepository;
import com.evidence.rag.repository.IndexingRepository;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.tool.answer.VideoAvProofBinding;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VideoAvAuthorityTest {
  @TempDir Path directory;

  @Test
  void restartKeepsPreciseModeEpochWholeProofAndOriginalWithoutModels() {
    String trace;
    byte[] original;
    VideoAvCitationResult citation;
    try (var fixture = new VideoAvTestFixture(directory);
        var answers = fixture.answers()) {
      original = fixture.register("library", 1, 1, 0, true).content();
      var result =
          answers.answer(
              VideoAvTestFixture.OWNER, VideoAvAnswerServiceTest.all(VideoAvMode.VISUAL));
      trace = result.answerId();
      citation = result.citations().getFirst();
      assertNull(citation.window().audio());
    }
    try (var fixture = new VideoAvTestFixture(directory);
        var answers = fixture.answers()) {
      assertEquals(citation, answers.source(VideoAvTestFixture.OWNER, trace, 1).citation());
      assertArrayEquals(original, answers.content(VideoAvTestFixture.OWNER, trace, 1).content());
      assertEquals(0, fixture.decodes);
      assertEquals(0, fixture.textEmbeds);
      assertTrue(fixture.drafts.isEmpty());
      assertEquals(
          citation,
          answers
              .source(new Actor(VideoAvTestFixture.OWNER.workspaceId(), "reader"), trace, 1)
              .citation());
      assertThrows(
          ApplicationException.class,
          () -> answers.source(new Actor("other-workspace", "owner"), trace, 1));
      assertThrows(
          ApplicationException.class, () -> answers.source(VideoAvTestFixture.OWNER, trace, 2));
      assertThrows(
          ApplicationException.class, () -> answers.source(VideoAvTestFixture.OWNER, trace, 0));
    }
  }

  @Test
  void realRawManagementStateTagsAndOriginalNeverInventLegacyTasks() {
    try (var fixture = new VideoAvTestFixture(directory)) {
      var original = fixture.register("library", 1, 1, 0, false);
      var management = management(fixture);
      var before =
          management
              .listDocuments(
                  VideoAvTestFixture.OWNER,
                  new DocumentQuery("", "video", "ready", null, null, "updated_desc", 1, 20))
              .items()
              .getFirst();
      assertFalse(before.syntheticFixture());
      assertFalse(before.canIndex());
      assertNull(before.latestJob());
      assertNull(before.latestIndexJob());
      assertEquals(original.revisionId(), before.registeredRevisionId());
      var changed =
          management.updateDocument(
              VideoAvTestFixture.OWNER,
              "library",
              new DocumentPatchCommand(true, "音画对照", false, null, true, List.of("视频")));
      assertEquals("音画对照", changed.displayName());
      assertEquals(List.of("视频"), changed.tags());
      fixture.publish(original, fixture.compilations.get(original.sourceSha256()));
      var after =
          management
              .listDocuments(
                  VideoAvTestFixture.OWNER,
                  new DocumentQuery("", "video", "parsed", null, "视频", "updated_desc", 1, 20))
              .items()
              .getFirst();
      assertEquals("indexed", after.indexStatus());
      assertEquals(original.revisionId(), after.activeRevisionId());
      assertEquals(fixture.publications.getFirst().id(), after.indexPublicationId());
      assertEquals(1, after.segmentCount());
      assertArrayEquals(
          original.content(),
          management
              .documentContent(VideoAvTestFixture.OWNER, "library", original.revisionId())
              .content());
      assertEquals(0, fixture.count("SELECT count(*) FROM ingestion_jobs"));
      assertEquals(0, fixture.count("SELECT count(*) FROM indexing_jobs"));
    }
  }

  @Test
  void tombstoneClearsOnlyNewRawBytesAndRetainsAllSealedHistory() throws Exception {
    try (var fixture = new VideoAvTestFixture(directory);
        var answers = fixture.answers()) {
      var original = fixture.register("library", 1, 1, 16000, true);
      var result =
          answers.answer(VideoAvTestFixture.OWNER, VideoAvAnswerServiceTest.all(VideoAvMode.JOINT));
      var lifecycle =
          new DocumentLifecycleService(
              fixture.store,
              new DocumentLifecycleRepository(fixture.store),
              fixture.management,
              new IngestionRepository(fixture.store),
              new IndexingRepository(fixture.store),
              new DocumentPermissionPolicy());
      assertEquals(
          "pending", lifecycle.removeDocument(VideoAvTestFixture.OWNER, "library").cleanupStatus());
      try (var connection =
              DriverManager.getConnection("jdbc:sqlite:" + fixture.store.libraryPath());
          var statement = connection.createStatement();
          var rows =
              statement.executeQuery(
                  "SELECT original_blob FROM video_av_originals WHERE document_id='library'")) {
        assertTrue(rows.next());
        assertArrayEquals(original.content(), rows.getBytes(1));
      }
      CleanupMaintenanceFixture.purge(fixture.store, VideoAvTestFixture.OWNER, "library");
      assertEquals(
          0,
          fixture.count(
              "SELECT length(original_blob) FROM video_av_originals WHERE document_id='library'"));
      assertEquals(1, fixture.count("SELECT count(*) FROM video_av_publications"));
      assertEquals(1, fixture.count("SELECT count(*) FROM video_av_windows"));
      assertEquals(1, fixture.count("SELECT count(*) FROM video_av_traces"));
      assertTrue(
          fixture
              .store
              .transaction(
                  () -> fixture.repository.findOriginal(VideoAvTestFixture.OWNER, "library"))
              .isEmpty());
      assertThrows(
          ApplicationException.class,
          () -> answers.source(VideoAvTestFixture.OWNER, result.answerId(), 1));
      assertEquals(
          "pending", lifecycle.removeDocument(VideoAvTestFixture.OWNER, "library").cleanupStatus());
    }
  }

  @Test
  void proofCannotChangeQuestionAnswerModeOrStableFactOrderBeforeSeal() {
    try (var fixture = new VideoAvTestFixture(directory)) {
      fixture.register("library", 1, 1, 16000, true);
      var scope = scope(fixture, DocumentSelection.allDocuments());
      var window = scope.publications().getFirst().windows().getFirst();
      var evidence =
          fixture
              .store
              .transaction(
                  () ->
                      fixture.repository.hydrate(
                          scope, VideoAvRoute.VISUAL, List.of(window.visualPhysicalId())))
              .getFirst();
      String questionSha = VideoAvProofIdentity.sha(VideoAvTestFixture.QUESTION);
      var fact =
          new VideoAvFact(
              VideoAvProofIdentity.stableFactId(
                  questionSha, 0, VideoAvTestFixture.FACT, VideoAvRequirement.JOINT),
              VideoAvTestFixture.FACT,
              VideoAvRequirement.JOINT,
              true,
              true);
      var proof =
          VideoAvProofBinding.create(
              questionSha,
              evidence,
              VideoAvMode.JOINT,
              List.of(fact),
              VideoAvTestFixture.MODEL,
              VideoAvAnswerService.POLICY_REVISION);
      var questionChanged =
          new VideoAvTraceDraft(
              VideoAvProofIdentity.sha("不同完整问题"),
              VideoAvProofIdentity.sha(VideoAvTestFixture.FACT),
              "answered",
              null,
              VideoAvMode.JOINT,
              List.of(proof),
              VideoAvTestFixture.MODEL,
              VideoAvAnswerService.POLICY_REVISION);
      assertThrows(
          ApplicationException.class,
          () -> fixture.store.transaction(() -> fixture.repository.finish(scope, questionChanged)));
      var answerChanged =
          new VideoAvTraceDraft(
              questionSha,
              VideoAvProofIdentity.sha("另一回答"),
              "answered",
              null,
              VideoAvMode.JOINT,
              List.of(proof),
              VideoAvTestFixture.MODEL,
              VideoAvAnswerService.POLICY_REVISION);
      assertThrows(
          ApplicationException.class,
          () -> fixture.store.transaction(() -> fixture.repository.finish(scope, answerChanged)));
      assertThrows(
          ApplicationException.class,
          () ->
              new VideoAvTraceDraft(
                  questionSha,
                  VideoAvProofIdentity.sha(VideoAvTestFixture.FACT),
                  "answered",
                  null,
                  VideoAvMode.VISUAL,
                  List.of(proof),
                  VideoAvTestFixture.MODEL,
                  VideoAvAnswerService.POLICY_REVISION));
      var wrongOrdinal =
          new VideoAvFact(
              VideoAvProofIdentity.stableFactId(
                  questionSha, 1, VideoAvTestFixture.FACT, VideoAvRequirement.JOINT),
              VideoAvTestFixture.FACT,
              VideoAvRequirement.JOINT,
              true,
              true);
      assertThrows(
          ApplicationException.class,
          () ->
              VideoAvProofBinding.create(
                  questionSha,
                  evidence,
                  VideoAvMode.JOINT,
                  List.of(wrongOrdinal),
                  VideoAvTestFixture.MODEL,
                  VideoAvAnswerService.POLICY_REVISION));
      assertEquals(0, fixture.count("SELECT count(*) FROM video_av_traces"));
      assertEquals(0, fixture.count("SELECT count(*) FROM video_av_trace_documents"));
    }
  }

  @Test
  void completeScopeAndRouteAreCheckedEvenForEmptyCandidateList() {
    try (var fixture = new VideoAvTestFixture(directory)) {
      fixture.register("first", 1, 1, 16000, true);
      fixture.register("second", 1, 1, 0, true);
      var scope = scope(fixture, DocumentSelection.allDocuments());
      var incomplete =
          new VideoAvScope(
              scope.actor(), scope.selection(), List.of(scope.publications().getFirst()));
      assertThrows(
          ApplicationException.class,
          () ->
              fixture.store.transaction(
                  () -> fixture.repository.hydrate(incomplete, VideoAvRoute.VISUAL, List.of())));
      var id = scope.publications().getFirst().windows().getFirst().visualPhysicalId();
      assertThrows(
          ApplicationException.class,
          () ->
              fixture.store.transaction(
                  () -> fixture.repository.hydrate(scope, VideoAvRoute.VISUAL, List.of(id, id))));
      assertThrows(
          ApplicationException.class,
          () ->
              fixture.store.transaction(
                  () ->
                      fixture.repository.hydrate(
                          scope, VideoAvRoute.VISUAL, List.of(id, "foreign-tail"))));
      assertThrows(
          ApplicationException.class,
          () ->
              fixture.store.transaction(
                  () -> fixture.repository.hydrate(scope, VideoAvRoute.AUDIO, List.of(id))));
      fixture.register("new-uncited", 1, 1, 0, false);
      assertFalse(
          fixture.store.transaction(
              () ->
                  fixture.repository.current(
                      scope, VideoAvTestFixture.TARGETS, VideoAvTestFixture.PROFILE)));
    }
  }

  @Test
  void sourceReadRechecksUncitedScopeAndPinnedProfile() {
    try (var fixture = new VideoAvTestFixture(directory);
        var answers = fixture.answers()) {
      fixture.register("cited", 1, 1, 16000, true);
      fixture.register("uncited", 1, 1, 0, true);
      var result =
          answers.answer(VideoAvTestFixture.OWNER, VideoAvAnswerServiceTest.all(VideoAvMode.JOINT));
      assertThrows(
          ApplicationException.class,
          () ->
              fixture.store.transaction(
                  () ->
                      fixture.repository.source(
                          VideoAvTestFixture.OWNER,
                          result.answerId(),
                          1,
                          VideoAvTestFixture.TARGETS,
                          "0".repeat(64))));
      fixture.sql(
          "INSERT INTO document_tombstones SELECT id,workspace_id,'owner','2026-10-08T00:00:00Z' FROM documents WHERE id='uncited'");
      assertThrows(
          ApplicationException.class,
          () -> answers.source(VideoAvTestFixture.OWNER, result.answerId(), 1));
    }
  }

  private static VideoAvScope scope(VideoAvTestFixture fixture, DocumentSelection selection) {
    return fixture.store.transaction(
        () ->
            fixture.repository.scope(
                VideoAvTestFixture.OWNER,
                selection,
                VideoAvTestFixture.TARGETS,
                VideoAvTestFixture.PROFILE));
  }

  private static ManagementService management(VideoAvTestFixture fixture) {
    return new ManagementService(
        fixture.store,
        fixture.management,
        new IngestionRepository(fixture.store),
        new IndexingRepository(fixture.store),
        new DocumentPermissionPolicy());
  }
}
