package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.CleanupClaim;
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
  void soundRecallAndOriginalAreErasedWithoutRemovingPublicationIdentities() throws Exception {
    List<String> identities;
    try (var fixture = new SoundTestFixture(directory)) {
      fixture.register("target", SoundTestFixture.pcm(32000, 7), true);
      fixture.register("keep", SoundTestFixture.pcm(32000, 8), true);
      identities =
          strings(
              directory,
              "SELECT id||':'||source_sha256||':'||manifest_sha256 FROM sound_publications ORDER BY id");
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
          identities,
          strings(
              directory,
              "SELECT id||':'||source_sha256||':'||manifest_sha256 FROM sound_publications ORDER BY id"));
    }
    try (var fixture = new SoundTestFixture(directory)) {
      assertEquals(0, fixture.decodes);
    }
  }

  @Test
  void videoAvOriginalIsClearedButExactEpochWindowsAndOtherSourceStay() throws Exception {
    try (var fixture = new VideoAvTestFixture(directory)) {
      fixture.register("target", 2, 2, 32000, true);
      fixture.register("keep", 1, 1, 16000, true);
      var windows =
          strings(
              directory,
              "SELECT publication_id||':'||id||':'||start_tick||':'||end_tick||':'||clip_sha256||':'||pcm_sha256 FROM video_av_windows ORDER BY publication_id,ordinal");
      cleanup(fixture.store, VideoAvTestFixture.OWNER, "target");
      assertEquals(
          0,
          fixture.count(
              "SELECT length(original_blob) FROM video_av_originals WHERE document_id='target'"));
      assertEquals(
          1,
          fixture.count(
              "SELECT payload_purged FROM video_av_originals WHERE document_id='target'"));
      assertTrue(
          fixture.count(
                  "SELECT length(original_blob) FROM video_av_originals WHERE document_id='keep'")
              > 0);
      assertEquals(
          windows,
          strings(
              directory,
              "SELECT publication_id||':'||id||':'||start_tick||':'||end_tick||':'||clip_sha256||':'||pcm_sha256 FROM video_av_windows ORDER BY publication_id,ordinal"));
    }
    try (var fixture = new VideoAvTestFixture(directory)) {
      assertEquals(0, fixture.decodes);
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
