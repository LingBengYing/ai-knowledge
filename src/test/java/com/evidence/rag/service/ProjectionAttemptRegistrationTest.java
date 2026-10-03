package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.MilvusProjectionCleanup;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.repository.DocumentCleanupRepository;
import com.evidence.rag.repository.DocumentLifecycleRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.tool.parser.TextParser;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectionAttemptRegistrationTest {
  @TempDir Path directory;
  private final Actor owner = new Actor("org-main", "owner");

  @Test
  void eachActualLegacyAttemptPinsTheExactTargetBeforeWriteAndFailedRetryIsRetained() {
    try (var authority = new AuthorityTestContext(directory)) {
      byte[] bytes = "registration synthetic source".getBytes(StandardCharsets.UTF_8);
      var task = authority.ingestion().uploadDocument(owner, "source.txt", "text/plain", bytes);
      var parsed = authority.ingestion().claimIngestion(owner.workspaceId()).orElseThrow();
      assertTrue(
          authority
              .ingestion()
              .completeIngestion(
                  parsed, new TextParser().parse("source.txt", "text/plain", bytes)));
      var settings = settings("java_registration_test");
      var target =
          new IndexTarget(settings.embeddingIdentity(), settings.identity(), "fixture-model-v1", 2);
      var index = authority.indexing().createIndexing(owner, task.documentId(), target);
      var first = authority.indexing().claimIndexing(owner.workspaceId()).orElseThrow();
      assertTrue(authority.indexing().registerProjectionWrite(first, settings));
      assertTrue(authority.indexing().registerProjectionWrite(first, settings));
      assertTrue(authority.indexing().failIndexing(first, "indexing_failed"));
      authority.indexing().retryIndexing(owner, index.taskId(), target);
      var second = authority.indexing().claimIndexing(owner.workspaceId()).orElseThrow();
      assertTrue(authority.indexing().registerProjectionWrite(second, settings));
      assertTrue(authority.indexing().failIndexing(second, "indexing_failed"));
      assertFalse(authority.indexing().registerProjectionWrite(first, settings));

      var repository = new DocumentCleanupRepository(authority.store());
      var cleanup =
          new DocumentCleanupService(
              authority.store(),
              repository,
              new DocumentLifecycleRepository(authority.store()),
              new ManagementRepository(authority.store()),
              new DocumentPermissionPolicy(),
              attempts -> {
                throw new AssertionError("No model or remote operation is part of registration");
              });
      cleanup.request(owner, task.documentId());
      var claim =
          authority
              .store()
              .transaction(() -> repository.claimNext(Instant.now().toString()).orElseThrow());
      var inventory = authority.store().transaction(() -> repository.projectionInventory(claim));
      assertTrue(inventory.known());
      assertEquals(2, inventory.attempts().size());
      assertEquals(
          Set.of(first.projectionGenerationId(), second.projectionGenerationId()),
          Set.copyOf(
              inventory.attempts().stream().map(attempt -> attempt.generationId()).toList()));
      assertTrue(
          inventory.attempts().stream()
              .allMatch(
                  attempt ->
                      attempt.writeIssued()
                          && attempt.target().equals(MilvusProjectionCleanup.qualified(settings))
                          && attempt.sourceSha256().equals(first.sourceSha256())));
    }
  }

  @Test
  void wrongConfiguredTargetCannotRegisterOrRunAnAttempt() {
    try (var authority = new AuthorityTestContext(directory)) {
      byte[] bytes = "target mismatch synthetic source".getBytes(StandardCharsets.UTF_8);
      var task = authority.ingestion().uploadDocument(owner, "source.txt", "text/plain", bytes);
      var parsed = authority.ingestion().claimIngestion(owner.workspaceId()).orElseThrow();
      assertTrue(
          authority
              .ingestion()
              .completeIngestion(
                  parsed, new TextParser().parse("source.txt", "text/plain", bytes)));
      var settings = settings("java_registration_test");
      var target =
          new IndexTarget(settings.embeddingIdentity(), settings.identity(), "fixture-model-v1", 2);
      authority.indexing().createIndexing(owner, task.documentId(), target);
      var claim = authority.indexing().claimIndexing(owner.workspaceId()).orElseThrow();
      assertThrows(
          ApplicationException.class,
          () -> authority.indexing().registerProjectionWrite(claim, settings("java_wrong_target")));
      assertTrue(authority.indexing().registerProjectionWrite(claim, settings));
    }
  }

  private static MilvusRestProjection.Settings settings(String collection) {
    return new MilvusRestProjection.Settings(
        URI.create("http://127.0.0.1:1"),
        "",
        "default",
        collection,
        "org-main",
        "fixture-embed-v1",
        2,
        Duration.ofSeconds(1),
        1048576,
        true);
  }
}
