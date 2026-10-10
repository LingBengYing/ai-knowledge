package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.*;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.support.PublishedCorpusFixture;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelRebuildCleanupAdmissionTest {
  @TempDir Path directory;

  @Test
  void runningCleanupBlocksRebuildButStoppedCleanupOfDeletedSourceDoesNot() throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var owner = new Actor("org", "owner");
      var removed = fixture.publish(owner, "合成已删除资料。");
      fixture.publish(owner, "合成仍在使用的资料。");
      var store = fixture.authority.store();
      var claim = DocumentCleanupRepositoryTest.claim(store, removed.documentId());
      var rebuild = new ModelRebuildRepository(store);
      assertFalse(store.transaction(() -> rebuild.canStart("org")));
      store.transaction(
          () -> {
            var cleanup = new DocumentCleanupRepository(store);
            cleanup.setResource(
                claim,
                "remote_inventory",
                "blocked",
                "cleanup_inventory_unknown",
                "2026-10-10T03:00:00Z");
            assertEquals("blocked", cleanup.finish(claim, "2026-10-10T03:00:00Z").cleanupStatus());
            return null;
          });
      assertTrue(store.transaction(() -> rebuild.canStart("org")));
      assertEquals(1, store.transaction(() -> rebuild.snapshot("org").size()));
      assertEquals(
          "blocked",
          store.transaction(
              () ->
                  new DocumentCleanupRepository(store)
                      .find(owner, removed.documentId())
                      .orElseThrow()
                      .cleanupStatus()));
    }
  }
}
