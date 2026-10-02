package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SynopsisMaterialRepository;
import com.evidence.rag.repository.SynopsisRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.SynopsisLibraryService;
import com.evidence.rag.support.PublishedCorpusFixture;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SynopsisRecoveryTest {
  @TempDir Path directory;

  @Test
  void persistenceRecoveryNeedsNeitherEnabledSynopsisNorModelCredentials() {
    var actor = new Actor("org-main", "owner");
    String taskId;
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var store = fixture.authority.store();
      var library =
          new SynopsisLibraryService(
              store,
              new SynopsisRepository(store),
              new SynopsisMaterialRepository(store),
              new ManagementRepository(store),
              new DocumentPermissionPolicy(),
              () -> "local-model");
      taskId = library.create(actor, fixture.publish(actor, "维护前关闭电源。").documentId()).taskId();
      assertTrue(library.claim(actor.workspaceId()).isPresent());
    }
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var store = fixture.authority.store();
      var properties =
          new RagProperties(
              "test",
              actor.workspaceId(),
              "development_headers",
              "",
              "issuer",
              "audience",
              directory);
      var repository = new PersistenceConfiguration().synopsisRepository(store, properties);
      var task = store.transaction(() -> repository.findTask(taskId).orElseThrow());
      assertEquals("unavailable", task.state());
      assertEquals("worker_interrupted", task.errorCode());
      assertTrue(store.transaction(() -> repository.queuedIds(actor.workspaceId())).isEmpty());
      assertTrue(store.transaction(() -> repository.findSynopsis(taskId)).isEmpty());
    }
  }
}
