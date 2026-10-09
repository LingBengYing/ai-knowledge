package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.config.ImportAutoIndexConfiguration;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ParsedText;
import com.evidence.rag.model.domain.TextPage;
import com.evidence.rag.model.domain.TextSegment;
import com.evidence.rag.repository.ImportIndexRepository;
import com.evidence.rag.support.AuthorityTestContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class ImportAutoIndexConfigurationTest {
  @TempDir Path directory;

  @Test
  void disabledSchedulerLeavesPendingAndRealSpringJobResumesWithoutBrowser() throws Exception {
    var actor = new Actor("org", "owner");
    try (var authority = new AuthorityTestContext(directory)) {
      var task =
          authority
              .ingestion()
              .uploadDocument(
                  actor, "auto.txt", "text/plain", "Synthetic".getBytes(StandardCharsets.UTF_8));
      var claim = authority.ingestion().claimIngestion("org").orElseThrow();
      assertTrue(
          authority
              .ingestion()
              .completeIngestion(
                  claim,
                  new ParsedText(
                      List.of(new TextPage(1, "Synthetic")),
                      List.of(new TextSegment(0, 1, 0, 9, "Synthetic")))));
      try (var disabled = context(authority, false)) {
        assertFalse(disabled.containsBean("importAutoIndexJob"));
        assertEquals(
            "pending",
            authority
                .store()
                .transaction(
                    () -> new ImportIndexRepository(authority.store()).latest(task.documentId()))
                .state());
      }
      try (var enabled = context(authority, true)) {
        assertNotNull(enabled.getBean("importAutoIndexJob"));
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline
            && !"submitted"
                .equals(
                    authority
                        .store()
                        .transaction(
                            () ->
                                new ImportIndexRepository(authority.store())
                                    .latest(task.documentId()))
                        .state())) {
          Thread.sleep(20);
        }
        assertEquals(
            "submitted",
            authority
                .store()
                .transaction(
                    () -> new ImportIndexRepository(authority.store()).latest(task.documentId()))
                .state());
        assertEquals(
            task.revisionId(),
            authority.indexing().claimIndexing("org").orElseThrow().revisionId());
        assertTrue(authority.indexing().claimIndexing("org").isEmpty());
      }
    }
  }

  private AnnotationConfigApplicationContext context(
      AuthorityTestContext authority, boolean enabled) {
    var context = new AnnotationConfigApplicationContext();
    context
        .getEnvironment()
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "test",
                Map.of("rag.import-auto-index.enabled", enabled, "rag.indexing.enabled", true)));
    context.getBeanFactory().registerSingleton("store", authority.store());
    context
        .getBeanFactory()
        .registerSingleton(
            "indexing",
            new IndexingTaskProcessor(
                authority.indexing(),
                "org",
                new IndexTarget("embedding", "b".repeat(64), "model", 2),
                Duration.ofSeconds(1),
                deadline -> {
                  throw new AssertionError("queueing must not invoke model");
                }));
    context.register(ImportAutoIndexConfiguration.class);
    context.refresh();
    return context;
  }
}
