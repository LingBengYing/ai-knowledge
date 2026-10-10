package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.CleanupClaim;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.VideoAvMode;
import com.evidence.rag.model.domain.VideoAvRoute;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.model.dto.VideoAvAnswerCommand;
import com.evidence.rag.repository.DocumentCleanupRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.support.DocumentWithdrawal;
import com.evidence.rag.worker.cleanup.CleanupRestoreJournal;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Actual typed publication and saved model-proof payloads, with only synthetic model substitutes.
 */
class DocumentCleanupMediaPayloadTest {
  @TempDir Path directory;

  @Test
  void soundRecallAndSavedFactsAreErasedWithoutRemovingFullTraceScopeOrProofHashes()
      throws Exception {
    String trace;
    List<String> identities;
    try (var fixture = new SoundTestFixture(directory);
        var answers = fixture.answers()) {
      fixture.register("target", SoundTestFixture.pcm(32000, 7), true);
      fixture.register("keep", SoundTestFixture.pcm(32000, 8), true);
      fixture.search =
          query ->
              List.of(
                  new RetrievalProjection.Candidate(
                      fixture.publications.getFirst().spans().getFirst().physicalSegmentId(), 1));
      var result =
          answers.answer(
              SoundTestFixture.OWNER,
              new AnswerCommand(
                  SoundTestFixture.QUESTION,
                  DocumentSelection.selected(List.of("target", "keep"))));
      assertEquals("answered", result.status());
      trace = result.answerId();
      identities =
          strings(
              directory,
              "SELECT id||':'||source_sha256||':'||manifest_sha256 FROM sound_publications ORDER BY id");
      var proof =
          strings(
              directory,
              "SELECT trace_id||':'||ordinal||':'||facts_sha256||':'||proof_sha256 FROM sound_trace_evidence ORDER BY ordinal");
      assertEquals(1, proof.size());
      long scope = fixture.count("SELECT COUNT(*) FROM sound_trace_documents");
      int models = fixture.drafts.size() + fixture.verifies.size();
      cleanup(fixture.store, SoundTestFixture.OWNER, "target");
      assertEquals(
          0,
          fixture.count(
              "SELECT length(original_blob) FROM sound_originals WHERE document_id='target'"));
      assertEquals(
          1,
          fixture.count("SELECT payload_purged FROM sound_originals WHERE document_id='target'"));
      assertEquals(
          0,
          fixture.count(
              "SELECT SUM(length(recall_text)) FROM sound_spans WHERE publication_id IN (SELECT id FROM sound_publications WHERE document_id='target')"));
      assertTrue(
          fixture.count(
                  "SELECT SUM(length(recall_text)) FROM sound_spans WHERE publication_id IN (SELECT id FROM sound_publications WHERE document_id='keep')")
              > 0);
      assertEquals(
          0,
          fixture.count(
              "SELECT COUNT(*) FROM sound_trace_evidence WHERE publication_id IN (SELECT id FROM sound_publications WHERE document_id='target') AND (facts_json!='[]' OR payload_purged!=1)"));
      assertEquals(scope, fixture.count("SELECT COUNT(*) FROM sound_trace_documents"));
      assertEquals(
          identities,
          strings(
              directory,
              "SELECT id||':'||source_sha256||':'||manifest_sha256 FROM sound_publications ORDER BY id"));
      assertEquals(
          proof,
          strings(
              directory,
              "SELECT trace_id||':'||ordinal||':'||facts_sha256||':'||proof_sha256 FROM sound_trace_evidence ORDER BY ordinal"));
      assertThrows(RuntimeException.class, () -> answers.source(SoundTestFixture.OWNER, trace, 1));
      assertEquals(models, fixture.drafts.size() + fixture.verifies.size());
    }
    try (var fixture = new SoundTestFixture(directory);
        var answers = fixture.answers()) {
      assertThrows(RuntimeException.class, () -> answers.content(SoundTestFixture.OWNER, trace, 1));
      assertEquals(0, fixture.decodes);
      assertEquals(0, fixture.textEmbeds);
      assertEquals(
          identities,
          strings(
              directory,
              "SELECT id||':'||source_sha256||':'||manifest_sha256 FROM sound_publications ORDER BY id"));
    }
  }

  @Test
  void videoAvJointFactsAndOriginalAreClearedButExactEpochWindowsAndOtherSourceStay()
      throws Exception {
    String trace;
    try (var fixture = new VideoAvTestFixture(directory);
        var answers = fixture.answers()) {
      fixture.register("target", 2, 2, 32000, true);
      fixture.register("keep", 1, 1, 16000, true);
      fixture.search =
          (route, query) ->
              List.of(
                  new RetrievalProjection.Candidate(
                      route == VideoAvRoute.VISUAL
                          ? fixture.publications.getFirst().windows().getFirst().visualPhysicalId()
                          : fixture.publications.getFirst().windows().getFirst().audioPhysicalId(),
                      1));
      var result =
          answers.answer(
              VideoAvTestFixture.OWNER,
              new VideoAvAnswerCommand(
                  new AnswerCommand(
                      VideoAvTestFixture.QUESTION,
                      DocumentSelection.selected(List.of("target", "keep"))),
                  VideoAvMode.JOINT));
      assertEquals("answered", result.status());
      trace = result.answerId();
      var windows =
          strings(
              directory,
              "SELECT publication_id||':'||id||':'||start_tick||':'||end_tick||':'||clip_sha256||':'||pcm_sha256 FROM video_av_windows ORDER BY publication_id,ordinal");
      var proof =
          strings(
              directory,
              "SELECT trace_id||':'||ordinal||':'||facts_sha256||':'||proof_sha256 FROM video_av_trace_evidence ORDER BY ordinal");
      assertEquals(1, proof.size());
      cleanup(fixture.store, VideoAvTestFixture.OWNER, "target");
      assertEquals(
          0,
          fixture.count(
              "SELECT length(original_blob) FROM video_av_originals WHERE document_id='target'"));
      assertEquals(
          1,
          fixture.count(
              "SELECT payload_purged FROM video_av_originals WHERE document_id='target'"));
      assertEquals(
          0,
          fixture.count(
              "SELECT COUNT(*) FROM video_av_trace_evidence WHERE publication_id IN (SELECT id FROM video_av_publications WHERE document_id='target') AND (facts_json!='[]' OR payload_purged!=1)"));
      assertTrue(
          fixture.count(
                  "SELECT length(original_blob) FROM video_av_originals WHERE document_id='keep'")
              > 0);
      assertEquals(
          windows,
          strings(
              directory,
              "SELECT publication_id||':'||id||':'||start_tick||':'||end_tick||':'||clip_sha256||':'||pcm_sha256 FROM video_av_windows ORDER BY publication_id,ordinal"));
      assertEquals(
          proof,
          strings(
              directory,
              "SELECT trace_id||':'||ordinal||':'||facts_sha256||':'||proof_sha256 FROM video_av_trace_evidence ORDER BY ordinal"));
      assertEquals(2, fixture.count("SELECT COUNT(*) FROM video_av_trace_documents"));
      assertThrows(
          RuntimeException.class, () -> answers.source(VideoAvTestFixture.OWNER, trace, 1));
    }
    try (var fixture = new VideoAvTestFixture(directory);
        var answers = fixture.answers()) {
      assertThrows(
          RuntimeException.class, () -> answers.content(VideoAvTestFixture.OWNER, trace, 1));
      assertEquals(0, fixture.decodes);
      assertEquals(0, fixture.textEmbeds);
    }
  }

  private static void cleanup(SqliteAuthorityStore store, Actor actor, String document)
      throws Exception {
    DocumentWithdrawal.withdraw(store, actor, document);
    var repository = new DocumentCleanupRepository(store);
    CleanupClaim claim =
        store.transaction(
            () -> {
              repository.create(actor, document, "2026-10-03T10:00:00Z");
              return repository.claimNext("2026-10-03T10:00:00Z").orElseThrow();
            });
    var plan = store.transaction(() -> repository.sealPlan(claim));
    CleanupRestoreJournal.intent(store.libraryPath().getParent(), store.libraryIdentity(), plan);
    try (var lease = store.operationGate().tryMaintenance().orElseThrow()) {
      store.purge(claim, plan, lease);
      store.compact(claim, lease);
    }
  }

  private static List<String> strings(Path directory, String sql) throws Exception {
    var result = new ArrayList<String>();
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      while (rows.next()) {
        result.add(rows.getString(1));
      }
    }
    return List.copyOf(result);
  }
}
