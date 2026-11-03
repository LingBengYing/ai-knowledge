package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.MilvusProjectionCleanup;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.SyntheticDocument;
import com.evidence.rag.model.dto.DocumentResult;
import com.evidence.rag.model.query.DocumentQuery;
import com.evidence.rag.repository.DocumentCleanupRepository;
import com.evidence.rag.repository.DocumentLifecycleRepository;
import com.evidence.rag.repository.DocumentUpdateRepository;
import com.evidence.rag.repository.ImportIndexRepository;
import com.evidence.rag.repository.LegacyMediaRows;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.tool.parser.AudioPcm;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Documents left by the removed sound and video audiovisual libraries (2026-11-03): their rows are
 * kept, so listing, detail, original download, replacement, automatic indexing and cleanup/purge
 * must all stay well-defined without any of that library code.
 */
class DocumentCleanupMediaPayloadTest {
  private static final Actor OWNER = new Actor("org", "owner");
  private static final String NOW = "2026-10-03T10:00:00Z";

  @TempDir Path directory;

  @Test
  void legacySoundAndVideoAvDocumentsStayReadableButCannotBeReplacedOrRebuilt() {
    try (var authority = new AuthorityTestContext(directory)) {
      var store = authority.store();
      var sound = sound("sound-legacy", 7);
      var video = videoAv("video-legacy");
      String publication =
          store.transaction(
              () -> {
                register(store, sound);
                LegacyMediaRows.soundOriginal(store, sound, NOW);
                register(store, video);
                LegacyMediaRows.videoAvOriginal(store, video, NOW);
                return LegacyMediaRows.soundPublication(
                    store, OWNER.workspaceId(), sound, "legacy recall", NOW);
              });

      var listed = list(authority.management(), null);
      assertEquals(2, listed.size());
      var soundResult = listed.get("sound-legacy");
      assertEquals("parsed", soundResult.status());
      assertEquals("indexed", soundResult.indexStatus());
      assertEquals(publication, soundResult.indexPublicationId());
      assertEquals(1, soundResult.segmentCount());
      assertEquals("audio", soundResult.documentType());
      assertFalse(soundResult.canIndex());
      var videoResult = listed.get("video-legacy");
      assertEquals("ready", videoResult.status());
      assertEquals("not_indexed", videoResult.indexStatus());
      assertNull(videoResult.indexPublicationId());
      assertEquals("video", videoResult.documentType());
      assertEquals(
          List.of("sound-legacy"), List.copyOf(list(authority.management(), "parsed").keySet()));
      assertEquals(
          List.of("video-legacy"), List.copyOf(list(authority.management(), "ready").keySet()));

      assertEquals(
          "clip.mp4", authority.management().documentOriginal(OWNER, "video-legacy").filename());
      assertArrayEquals(
          sound.content(),
          authority
              .management()
              .documentContent(OWNER, "sound-legacy", sound.revisionId())
              .content());

      var replacements =
          new DocumentReplacementService(
              store,
              new DocumentUpdateRepository(store),
              new ManagementRepository(store),
              new DocumentPermissionPolicy(),
              authority.ingestion(),
              () -> null,
              true);
      for (var original : List.of(sound, video)) {
        var state = replacements.get(OWNER, original.documentId());
        assertEquals("none", state.state());
        assertFalse(state.canUpload());
        assertFalse(state.canIndex());
        var refused =
            assertThrows(
                ApplicationException.class,
                () ->
                    replacements.prepareUpload(
                        OWNER,
                        original.documentId(),
                        original.revisionId(),
                        original.filename(),
                        original.mediaType()));
        assertEquals("replacement_state_conflict", refused.code());
        refused =
            assertThrows(
                ApplicationException.class,
                () ->
                    replacements.upload(
                        OWNER,
                        original.documentId(),
                        original.revisionId(),
                        original.filename(),
                        original.mediaType(),
                        original.content()));
        assertEquals("replacement_state_conflict", refused.code());
      }
      assertEquals("sound", replacements.get(OWNER, "sound-legacy").pipeline());
      assertEquals(publication, replacements.get(OWNER, "sound-legacy").basePublicationId());
      assertEquals("video_av", replacements.get(OWNER, "video-legacy").pipeline());

      // A legacy request still pending from before the removal fails instead of staying pending.
      store.transaction(
          () -> {
            new ImportIndexRepository(store)
                .insert(OWNER, "video-legacy", video.revisionId(), "video_av", null, null);
            return null;
          });
      var automatic = new ImportAutoIndexService(store, () -> null);
      assertEquals(ImportAutoIndexService.Dispatch.IDLE, automatic.processNextCorpus());
      assertEquals(ImportAutoIndexService.Dispatch.DISPATCHED, automatic.processNextMedia());
      var progress = list(authority.management(), null).get("video-legacy").autoIndex();
      assertEquals("failed", progress.state());
      assertEquals("indexing_unavailable", progress.errorCode());
    }
  }

  @Test
  void legacySoundRecallAndOriginalArePurgedWithoutRemovingPublicationIdentities()
      throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      var store = authority.store();
      var target = sound("target", 7);
      var keep = sound("keep", 8);
      store.transaction(
          () -> {
            for (var original : List.of(target, keep)) {
              register(store, original);
              LegacyMediaRows.soundOriginal(store, original, NOW);
              LegacyMediaRows.soundPublication(
                  store, OWNER.workspaceId(), original, "same recall description", NOW);
            }
            return null;
          });
      var identities =
          strings(
              "SELECT id||':'||source_sha256||':'||manifest_sha256 FROM sound_publications ORDER BY id");

      var state = cleanup(store, "target");
      assertEquals("completed", resource(state, "database_payload"));
      // The old remote write is known but has no configured target: reported, never guessed.
      assertEquals("completed", resource(state, "remote_inventory"));
      assertEquals("blocked", resource(state, "remote_logical_rows"));
      assertEquals("cleanup_target_unknown", state.errorCode());
      assertEquals(
          0, count("SELECT length(original_blob) FROM sound_originals WHERE document_id='target'"));
      assertEquals(
          1, count("SELECT payload_purged FROM sound_originals WHERE document_id='target'"));
      assertEquals(
          0,
          count(
              "SELECT SUM(length(recall_text)) FROM sound_spans WHERE publication_id IN (SELECT id FROM sound_publications WHERE document_id='target')"));
      assertTrue(
          count(
                  "SELECT SUM(length(recall_text)) FROM sound_spans WHERE publication_id IN (SELECT id FROM sound_publications WHERE document_id='keep')")
              > 0);
      assertTrue(
          count("SELECT length(original_blob) FROM sound_originals WHERE document_id='keep'") > 0);
      assertEquals(
          identities,
          strings(
              "SELECT id||':'||source_sha256||':'||manifest_sha256 FROM sound_publications ORDER BY id"));
      assertEquals(List.of("keep"), List.copyOf(list(authority.management(), null).keySet()));
    }
  }

  @Test
  void legacyVideoAvOriginalIsPurgedAndOtherSourceStays() throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      var store = authority.store();
      var target = videoAv("target");
      var keep = videoAv("keep");
      store.transaction(
          () -> {
            for (var original : List.of(target, keep)) {
              register(store, original);
              LegacyMediaRows.videoAvOriginal(store, original, NOW);
            }
            return null;
          });

      var state = cleanup(store, "target");
      assertEquals("completed", resource(state, "database_payload"));
      assertEquals(
          0,
          count("SELECT length(original_blob) FROM video_av_originals WHERE document_id='target'"));
      assertEquals(
          1, count("SELECT payload_purged FROM video_av_originals WHERE document_id='target'"));
      assertTrue(
          count("SELECT length(original_blob) FROM video_av_originals WHERE document_id='keep'")
              > 0);
      assertEquals(List.of("keep"), List.copyOf(list(authority.management(), null).keySet()));
    }
  }

  private static com.evidence.rag.model.domain.DocumentCleanupState cleanup(
      SqliteAuthorityStore store, String document) {
    // Legacy projections have no configured cleanup target any more.
    var service =
        new DocumentCleanupService(
            store,
            new DocumentCleanupRepository(store),
            new DocumentLifecycleRepository(store),
            new ManagementRepository(store),
            new DocumentPermissionPolicy(),
            new MilvusProjectionCleanup(List.of()));
    assertEquals("pending", service.request(OWNER, document).cleanupStatus());
    assertTrue(service.runOnce());
    return service.status(OWNER, document);
  }

  private static String resource(
      com.evidence.rag.model.domain.DocumentCleanupState state, String kind) {
    return state.resources().stream()
        .filter(resource -> resource.kind().equals(kind))
        .findFirst()
        .orElseThrow()
        .status();
  }

  private static Map<String, DocumentResult> list(ManagementService management, String status) {
    return management
        .listDocuments(OWNER, new DocumentQuery("", null, status, null, null, "name_desc", 1, 100))
        .items()
        .stream()
        .collect(
            Collectors.toMap(
                DocumentResult::documentId,
                Function.identity(),
                (left, right) -> left,
                java.util.LinkedHashMap::new));
  }

  private static DocumentOriginal sound(String id, int seed) {
    byte[] pcm = new byte[64];
    java.util.Arrays.fill(pcm, (byte) seed);
    byte[] bytes = AudioPcm.wav(pcm, 0, pcm.length);
    return new DocumentOriginal(
        id,
        "source-" + id,
        id + ".wav",
        "audio",
        "audio/wav",
        ModelValues.sha256(bytes),
        bytes.length,
        bytes);
  }

  private static DocumentOriginal videoAv(String id) {
    byte[] bytes = new byte[16];
    System.arraycopy("ftyp".getBytes(StandardCharsets.US_ASCII), 0, bytes, 4, 4);
    bytes[15] = (byte) id.length();
    return new DocumentOriginal(
        id,
        "source-" + id,
        "clip.mp4",
        "video",
        "video/mp4",
        ModelValues.sha256(bytes),
        bytes.length,
        bytes);
  }

  private static void register(SqliteAuthorityStore store, DocumentOriginal original) {
    var management = new ManagementRepository(store);
    management.insertDocument(
        OWNER,
        new SyntheticDocument(
            original.documentId(),
            original.filename(),
            original.documentType(),
            original.mediaType(),
            original.revisionId(),
            original.sourceSha256(),
            original.sizeBytes()),
        Instant.now().toString());
    management.insertGrant(original.documentId(), OWNER.principalId(), "owner");
  }

  private long count(String sql) throws Exception {
    var values = strings(sql);
    return values.isEmpty() || values.getFirst() == null ? 0 : Long.parseLong(values.getFirst());
  }

  private List<String> strings(String sql) throws Exception {
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
