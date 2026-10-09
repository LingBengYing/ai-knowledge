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
import com.evidence.rag.model.domain.SoundProof;
import com.evidence.rag.model.domain.SoundScope;
import com.evidence.rag.model.domain.SoundTraceDraft;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.model.dto.DocumentPatchCommand;
import com.evidence.rag.model.dto.SoundCitationResult;
import com.evidence.rag.model.query.DocumentQuery;
import com.evidence.rag.repository.CleanupMaintenanceFixture;
import com.evidence.rag.repository.DocumentLifecycleRepository;
import com.evidence.rag.repository.IndexingRepository;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.tool.answer.SoundProofBinding;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SoundAuthorityTest {
  @TempDir Path directory;

  @Test
  void restartReadsImmutableWholeProofAndOriginalWithoutDecodeOrModels() {
    String answerId;
    byte[] original;
    SoundCitationResult citation;
    try (var fixture = new SoundTestFixture(directory);
        var answers = fixture.answers()) {
      original = fixture.register("library", SoundTestFixture.pcm(32002, 3), true).content();
      var answer = answers.answer(SoundTestFixture.OWNER, command("library"));
      answerId = answer.answerId();
      citation = answer.citations().getFirst();
    }
    try (var fixture = new SoundTestFixture(directory);
        var answers = fixture.answers()) {
      assertEquals(citation, answers.source(SoundTestFixture.OWNER, answerId, 1).citation());
      assertArrayEquals(original, answers.content(SoundTestFixture.OWNER, answerId, 1).content());
      assertEquals(0, fixture.decodes);
      assertEquals(0, fixture.textEmbeds);
      assertTrue(fixture.drafts.isEmpty());
      assertEquals(
          citation,
          answers
              .source(new Actor(SoundTestFixture.OWNER.workspaceId(), "other-reader"), answerId, 1)
              .citation());
      assertThrows(
          ApplicationException.class,
          () -> answers.source(new Actor("other-workspace", "owner"), answerId, 1));
      assertThrows(
          ApplicationException.class, () -> answers.source(SoundTestFixture.OWNER, answerId, 2));
    }
  }

  @Test
  void genuineSoundMetadataAndTagsNeverInventLegacyTasks() {
    try (var fixture = new SoundTestFixture(directory)) {
      var original = fixture.register("library", SoundTestFixture.pcm(32002, 4), false);
      var management = management(fixture);
      var query = new DocumentQuery("", "audio", "ready", null, null, "updated_desc", 1, 20);
      var before = management.listDocuments(SoundTestFixture.OWNER, query).items().getFirst();
      assertFalse(before.syntheticFixture());
      assertFalse(before.canIndex());
      assertNull(before.latestJob());
      assertNull(before.latestIndexJob());
      assertEquals(original.revisionId(), before.registeredRevisionId());
      var changed =
          management.updateDocument(
              SoundTestFixture.OWNER,
              "library",
              new DocumentPatchCommand(true, "合成声音", false, null, true, List.of("声音")));
      assertEquals("合成声音", changed.displayName());
      assertEquals(List.of("声音"), changed.tags());
      fixture.publish(original, SoundTestFixture.pcm(32002, 4));
      var after =
          management
              .listDocuments(
                  SoundTestFixture.OWNER,
                  new DocumentQuery("", "audio", "parsed", null, "声音", "updated_desc", 1, 20))
              .items()
              .getFirst();
      assertEquals("parsed", after.status());
      assertEquals("indexed", after.indexStatus());
      assertEquals(original.revisionId(), after.activeRevisionId());
      assertEquals(fixture.publications.getFirst().id(), after.indexPublicationId());
      assertFalse(after.canIndex());
      assertNull(after.latestJob());
      assertNull(after.latestIndexJob());
      assertEquals(0, fixture.count("SELECT count(*) FROM ingestion_jobs"));
      assertEquals(0, fixture.count("SELECT count(*) FROM indexing_jobs"));
    }
  }

  @Test
  void removalErasesOnlyNewOriginalBytesAndKeepsImmutableHistory() throws Exception {
    try (var fixture = new SoundTestFixture(directory);
        var answers = fixture.answers()) {
      var original = fixture.register("library", SoundTestFixture.pcm(32000, 6), true);
      var answer = answers.answer(SoundTestFixture.OWNER, command("library"));
      var lifecycle =
          new DocumentLifecycleService(
              fixture.store,
              new DocumentLifecycleRepository(fixture.store),
              fixture.management,
              new IngestionRepository(fixture.store),
              new IndexingRepository(fixture.store),
              new DocumentPermissionPolicy());
      assertEquals(
          "pending", lifecycle.removeDocument(SoundTestFixture.OWNER, "library").cleanupStatus());
      try (var connection =
              DriverManager.getConnection("jdbc:sqlite:" + fixture.store.libraryPath());
          var statement = connection.createStatement();
          var rows =
              statement.executeQuery(
                  "SELECT original_blob FROM sound_originals WHERE document_id='library'")) {
        assertTrue(rows.next());
        assertArrayEquals(original.content(), rows.getBytes(1));
      }
      CleanupMaintenanceFixture.purge(fixture.store, SoundTestFixture.OWNER, "library");
      assertEquals(
          0,
          fixture.count(
              "SELECT length(original_blob) FROM sound_originals WHERE document_id='library'"));
      assertEquals(1, fixture.count("SELECT count(*) FROM sound_publications"));
      assertEquals(1, fixture.count("SELECT count(*) FROM sound_traces"));
      assertTrue(
          fixture
              .store
              .transaction(() -> fixture.repository.findOriginal(SoundTestFixture.OWNER, "library"))
              .isEmpty());
      assertThrows(
          ApplicationException.class,
          () -> answers.source(SoundTestFixture.OWNER, answer.answerId(), 1));
      assertEquals(
          "pending", lifecycle.removeDocument(SoundTestFixture.OWNER, "library").cleanupStatus());
    }
  }

  @Test
  void proofCannotBeReboundToDifferentQuestionOrAnswerBeforeSeal() {
    try (var fixture = new SoundTestFixture(directory)) {
      fixture.register("library", SoundTestFixture.pcm(32000, 4), true);
      var scope = scope(fixture, DocumentSelection.selected(List.of("library")));
      var source =
          fixture
              .store
              .transaction(
                  () ->
                      fixture.repository.hydrate(
                          scope,
                          List.of(
                              fixture
                                  .publications
                                  .getFirst()
                                  .spans()
                                  .getFirst()
                                  .physicalSegmentId())))
              .getFirst();
      var proof =
          SoundProofBinding.create(
              SoundProofBinding.sha(SoundTestFixture.QUESTION),
              source,
              List.of(SoundTestFixture.FACT),
              SoundTestFixture.MODEL,
              SoundAnswerService.POLICY_REVISION);
      var changedQuestion =
          new SoundTraceDraft(
              SoundProofBinding.sha("另外一个完整问题？"),
              SoundProofBinding.sha(SoundTestFixture.FACT),
              "answered",
              null,
              List.of(proof),
              SoundTestFixture.MODEL,
              SoundAnswerService.POLICY_REVISION);
      assertThrows(
          ApplicationException.class,
          () -> fixture.store.transaction(() -> fixture.repository.finish(scope, changedQuestion)));
      var changedAnswer =
          new SoundTraceDraft(
              SoundProofBinding.sha(SoundTestFixture.QUESTION),
              SoundProofBinding.sha("另一份回答。"),
              "answered",
              null,
              List.of(proof),
              SoundTestFixture.MODEL,
              SoundAnswerService.POLICY_REVISION);
      assertThrows(
          ApplicationException.class,
          () -> fixture.store.transaction(() -> fixture.repository.finish(scope, changedAnswer)));
      assertEquals(0, fixture.count("SELECT count(*) FROM sound_trace_documents"));
      assertEquals(0, fixture.count("SELECT count(*) FROM sound_traces"));
    }
  }

  @Test
  void fullScopeAndEveryPhysicalMappingAreRecheckedEvenWhenNoCandidateIsRequested() {
    try (var fixture = new SoundTestFixture(directory)) {
      fixture.register("first", SoundTestFixture.pcm(32000, 1), true);
      fixture.register("second", SoundTestFixture.pcm(32000, 2), true);
      var scope = scope(fixture, DocumentSelection.allDocuments());
      var incomplete =
          new SoundScope(
              scope.actor(), scope.selection(), List.of(scope.publications().getFirst()));
      assertThrows(
          ApplicationException.class,
          () -> fixture.store.transaction(() -> fixture.repository.hydrate(incomplete, List.of())));
      var id = scope.publications().getFirst().spans().getFirst().physicalSegmentId();
      assertThrows(
          ApplicationException.class,
          () ->
              fixture.store.transaction(() -> fixture.repository.hydrate(scope, List.of(id, id))));
      assertThrows(
          ApplicationException.class,
          () ->
              fixture.store.transaction(
                  () -> fixture.repository.hydrate(scope, List.of(id, "foreign-tail"))));
      fixture.register("new-uncited", SoundTestFixture.pcm(32000, 3), false);
      assertFalse(
          fixture.store.transaction(
              () ->
                  fixture.repository.current(
                      scope, SoundTestFixture.TARGET, SoundTestFixture.PROFILE)));
    }
  }

  @Test
  void sourceReadRejectsUncitedScopeLossAndCurrentProfileMismatch() {
    try (var fixture = new SoundTestFixture(directory);
        var answers = fixture.answers()) {
      fixture.register("cited", SoundTestFixture.pcm(32000, 1), true);
      fixture.register("uncited", SoundTestFixture.pcm(32000, 1), true);
      var answer =
          answers.answer(
              SoundTestFixture.OWNER,
              new AnswerCommand(SoundTestFixture.QUESTION, DocumentSelection.allDocuments()));
      assertThrows(
          ApplicationException.class,
          () ->
              fixture.store.transaction(
                  () ->
                      fixture.repository.source(
                          SoundTestFixture.OWNER,
                          answer.answerId(),
                          1,
                          SoundTestFixture.TARGET,
                          "0".repeat(64))));
      fixture.sql(
          "INSERT INTO document_tombstones SELECT id,workspace_id,'owner','2026-10-08T00:00:00Z' FROM documents WHERE id='uncited'");
      assertThrows(
          ApplicationException.class,
          () -> answers.source(SoundTestFixture.OWNER, answer.answerId(), 1));
    }
  }

  @Test
  void canonicalUnicodeFactsBindExactBytesWithoutTranscriptFields() {
    try (var fixture = new SoundTestFixture(directory)) {
      fixture.register("library", SoundTestFixture.pcm(2, 1), true);
      var scope = scope(fixture, DocumentSelection.selected(List.of("library")));
      var source =
          fixture
              .store
              .transaction(
                  () ->
                      fixture.repository.hydrate(
                          scope,
                          List.of(
                              fixture
                                  .publications
                                  .getFirst()
                                  .spans()
                                  .getFirst()
                                  .physicalSegmentId())))
              .getFirst();
      var facts = List.of("听见金属声“叮”🔔。", "标签为\\\"合成\\\"。");
      var proof =
          SoundProofBinding.create(
              SoundProofBinding.sha(SoundTestFixture.QUESTION),
              source,
              facts,
              SoundTestFixture.MODEL,
              SoundAnswerService.POLICY_REVISION);
      assertEquals(SoundProofBinding.sha(SoundProof.factsJson(facts)), proof.factsSha256());
      assertTrue(
          SoundProofBinding.matches(
              proof,
              SoundProofBinding.sha(SoundTestFixture.QUESTION),
              SoundTestFixture.MODEL,
              SoundAnswerService.POLICY_REVISION));
      assertFalse(
          SoundProofBinding.matches(
              proof,
              SoundProofBinding.sha("不同问题"),
              SoundTestFixture.MODEL,
              SoundAnswerService.POLICY_REVISION));
      var draft =
          new SoundTraceDraft(
              SoundProofBinding.sha(SoundTestFixture.QUESTION),
              SoundProofBinding.sha(String.join("\n", facts)),
              "answered",
              null,
              List.of(proof),
              SoundTestFixture.MODEL,
              SoundAnswerService.POLICY_REVISION);
      var receipt = fixture.store.transaction(() -> fixture.repository.finish(scope, draft));
      assertEquals(
          facts,
          fixture
              .store
              .transaction(
                  () ->
                      fixture.repository.source(
                          SoundTestFixture.OWNER,
                          receipt.traceId(),
                          1,
                          SoundTestFixture.TARGET,
                          SoundTestFixture.PROFILE))
              .proof()
              .facts());
    }
  }

  private static SoundScope scope(SoundTestFixture fixture, DocumentSelection selection) {
    return fixture.store.transaction(
        () ->
            fixture.repository.scope(
                SoundTestFixture.OWNER,
                selection,
                SoundTestFixture.TARGET,
                SoundTestFixture.PROFILE));
  }

  private static ManagementService management(SoundTestFixture fixture) {
    return new ManagementService(
        fixture.store,
        fixture.management,
        new IngestionRepository(fixture.store),
        new IndexingRepository(fixture.store),
        new DocumentPermissionPolicy());
  }

  private static AnswerCommand command(String id) {
    return new AnswerCommand(SoundTestFixture.QUESTION, DocumentSelection.selected(List.of(id)));
  }
}
