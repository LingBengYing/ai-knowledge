package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.SyntheticDocument;
import com.evidence.rag.model.query.DocumentQuery;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.ManagementService;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SoundRepositoryTest {
  @TempDir Path directory;
  private static final Actor OWNER = new Actor("sound-workspace", "owner");

  @Test
  void rawSoundIsGenuineManagedAudioWithoutSpeechTaskAndMissingIndexStopsScope() {
    try (var store = new SqliteAuthorityStore(directory)) {
      var management = new ManagementRepository(store);
      var repository = new SoundRepository(store);
      var original = original();
      store.transaction(
          () -> {
            management.insertDocument(
                OWNER,
                new SyntheticDocument(
                    original.documentId(),
                    original.filename(),
                    "audio",
                    original.mediaType(),
                    original.revisionId(),
                    original.sourceSha256(),
                    original.sizeBytes()),
                Instant.now().toString());
            management.insertGrant(original.documentId(), OWNER.principalId(), "owner");
            repository.insertOriginal(original, Instant.now().toString());
            assertArrayEquals(
                original.content(),
                management
                    .findDocumentOriginal(OWNER, original.documentId())
                    .orElseThrow()
                    .content());
            assertTrue(repository.managedEvidence(original.documentId()).isPresent());
            assertTrue(
                repository
                    .findOriginal(
                        new Actor(OWNER.workspaceId(), "private-reader"), original.documentId())
                    .isPresent());
            assertTrue(
                repository
                    .findOriginal(
                        new Actor("other-workspace", OWNER.principalId()), original.documentId())
                    .isEmpty());
            assertEquals(
                "sound_index_required",
                assertThrows(
                        ApplicationException.class,
                        () ->
                            repository.scope(
                                OWNER,
                                DocumentSelection.selected(List.of(original.documentId())),
                                new IndexTarget("embedding", "a".repeat(64), "embedding", 2),
                                "b".repeat(64)))
                    .code());
            return null;
          });
      var service =
          new ManagementService(
              store,
              management,
              new IngestionRepository(store),
              new IndexingRepository(store),
              new DocumentPermissionPolicy());
      var row =
          service
              .listDocuments(
                  OWNER, new DocumentQuery("", "audio", null, null, null, "updated_desc", 1, 20))
              .items()
              .getFirst();
      assertFalse(row.syntheticFixture());
      assertFalse(row.canIndex());
      assertEquals("ready", row.status());
      assertEquals("not_indexed", row.indexStatus());
    }
  }

  static DocumentOriginal original() {
    byte[] pcm = {1, 2, 3, 4};
    byte[] content = com.evidence.rag.tool.parser.AudioPcm.wav(pcm, 0, pcm.length);
    return new DocumentOriginal(
        "sound-document",
        "sound-source",
        "synthetic.wav",
        "audio",
        "audio/wav",
        ModelValues.sha256(content),
        content.length,
        content);
  }
}
