package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ProjectionAttempt;
import com.evidence.rag.model.domain.QualifiedProjectionTarget;
import com.evidence.rag.support.PublishedCorpusFixture;
import com.evidence.rag.tool.parser.TextParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextModelTargetRepositoryTest {
  private static final Actor OWNER = new Actor("org", "owner");
  @TempDir Path directory;

  @Test
  void queuedAndFailedJobsWithoutPublicationStillRequireTheExactExistingTarget() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var authority = fixture.authority;
      authority
          .ingestion()
          .uploadDocument(OWNER, "one.txt", "text/plain", "合成正文".getBytes(StandardCharsets.UTF_8));
      var parsed = authority.ingestion().claimIngestion("org").orElseThrow();
      assertTrue(
          authority
              .ingestion()
              .completeIngestion(
                  parsed, new TextParser().parse("one.txt", "text/plain", parsed.content())));
      authority
          .indexing()
          .createIndexing(OWNER, parsed.documentId(), PublishedCorpusFixture.TARGET);
      var repository = new TextModelTargetRepository(authority.store());
      var changed = new IndexTarget("other", "c".repeat(64), "model-v2", 3);
      assertEquals(
          "model_rebuild_required",
          assertThrows(
                  ApplicationException.class,
                  () ->
                      authority
                          .store()
                          .transaction(
                              () -> {
                                repository.requireCompatible("org", changed);
                                return null;
                              }))
              .code());
      var claim = authority.indexing().claimIndexing("org").orElseThrow();
      authority.indexing().failIndexing(claim, "indexing_failed");
      assertThrows(
          ApplicationException.class,
          () ->
              authority
                  .store()
                  .transaction(
                      () -> {
                        repository.requireCompatible("org", changed);
                        return null;
                      }));
      assertDoesNotThrow(
          () ->
              authority
                  .store()
                  .transaction(
                      () -> {
                        repository.requireCompatible("org", PublishedCorpusFixture.TARGET);
                        return null;
                      }));
    }
  }

  @Test
  void legacyAttemptMismatchBlocksButIndependentImageProjectionIsNotComparedToText() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var claim = fixture.publish(OWNER, "兼容检查合成正文。");
      var store = fixture.authority.store();
      var registry = new DocumentCleanupRepository(store);
      var target =
          new QualifiedProjectionTarget(
              "https://synthetic.invalid",
              "default",
              "java_image_probe",
              "org",
              "image-embedding",
              4,
              "d".repeat(64));
      store.transaction(
          () -> {
            registry.registerProjectionAttempt(
                new ProjectionAttempt(
                    claim.documentId(),
                    "org",
                    claim.revisionId(),
                    claim.sourceSha256(),
                    UUID.randomUUID().toString(),
                    "image",
                    target,
                    false));
            new TextModelTargetRepository(store)
                .requireCompatible("org", PublishedCorpusFixture.TARGET);
            return null;
          });
      store.transaction(
          () -> {
            registry.registerProjectionAttempt(
                new ProjectionAttempt(
                    claim.documentId(),
                    "org",
                    claim.revisionId(),
                    claim.sourceSha256(),
                    UUID.randomUUID().toString(),
                    "legacy",
                    target,
                    false));
            return null;
          });
      assertThrows(
          ApplicationException.class,
          () ->
              store.transaction(
                  () -> {
                    new TextModelTargetRepository(store)
                        .requireCompatible("org", PublishedCorpusFixture.TARGET);
                    return null;
                  }));
    }
  }
}
