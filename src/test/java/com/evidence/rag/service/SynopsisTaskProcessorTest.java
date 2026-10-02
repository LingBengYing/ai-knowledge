package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.SynopsisModels;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.job.SynopsisJob;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.SynopsisDraft;
import com.evidence.rag.model.domain.SynopsisEvidence;
import com.evidence.rag.model.domain.SynopsisInput;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.repository.SynopsisMaterialRepository;
import com.evidence.rag.repository.SynopsisRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.SynopsisCorpusFixture;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SynopsisTaskProcessorTest {
  @TempDir Path directory;
  private static final Actor OWNER = new Actor("org-main", "owner");
  private static final IndexTarget TARGET =
      new IndexTarget("embedding", "b".repeat(64), "model-v1", 2);

  @Test
  void oneExplicitClaimGeneratesAndPersistsWithoutReplayingAfterCompletion() {
    try (var store = new SqliteAuthorityStore(directory)) {
      var models = new Models();
      var library = library(store, models);
      var processor =
          new SynopsisTaskProcessor(
              library, new SynopsisService(models, Duration.ofSeconds(10)), OWNER.workspaceId());
      var publication = new SynopsisCorpusFixture(store, OWNER, TARGET).text("设备维护记录。");
      var task = processor.create(OWNER, publication.documentId());
      assertEquals("queued", task.state());
      var claim = processor.claim().orElseThrow();
      assertTrue(processor.isCurrent(claim));
      processor.process(claim);
      assertEquals("available", library.task(OWNER, task.taskId()).state());
      assertEquals(3, library.get(OWNER, publication.documentId()).synopsis().entries().size());
      assertFalse(processor.isCurrent(claim));
      assertTrue(processor.claim().isEmpty());
      processor.process(claim);
      assertEquals(1, models.drafts.get());
      assertEquals(3, models.verifications.get());
      processor.failUnexpected(null);
    }
  }

  @Test
  void unexpectedAdapterFailureBecomesSafeUnavailableWithoutChangingPublication() {
    try (var store = new SqliteAuthorityStore(directory)) {
      var models = new Models();
      models.onDraft =
          () -> {
            throw new IllegalStateException("private source details");
          };
      var library = library(store, models);
      var processor =
          new SynopsisTaskProcessor(
              library, new SynopsisService(models, Duration.ofSeconds(10)), OWNER.workspaceId());
      var publication = new SynopsisCorpusFixture(store, OWNER, TARGET).text("设备维护记录。");
      var task = processor.create(OWNER, publication.documentId());
      processor.process(processor.claim().orElseThrow());
      var failed = library.task(OWNER, task.taskId());
      assertEquals("unavailable", failed.state());
      assertEquals("synopsis_failed", failed.errorCode());
      assertEquals(
          publication,
          store.transaction(
              () ->
                  new SynopsisMaterialRepository(store)
                      .publication(OWNER, publication.documentId())
                      .orElseThrow()));
      assertEquals(1, models.drafts.get());
    }
  }

  @Test
  void jobShutdownInterruptsModelAndLeavesLaterTasksQueued() throws Exception {
    try (var store = new SqliteAuthorityStore(directory)) {
      var models = new Models();
      var started = new CountDownLatch(1);
      models.onDraft =
          () -> {
            started.countDown();
            try {
              new CountDownLatch(1).await();
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new TextModels.Failure("model_interrupted");
            }
          };
      var library = library(store, models);
      var processor =
          new SynopsisTaskProcessor(
              library, new SynopsisService(models, Duration.ofSeconds(10)), OWNER.workspaceId());
      var publication = new SynopsisCorpusFixture(store, OWNER, TARGET).text("设备维护记录。");
      var task = processor.create(OWNER, publication.documentId());
      try (var job = new SynopsisJob(processor)) {
        assertTrue(started.await(5, TimeUnit.SECONDS));
        job.close();
        assertEquals("unavailable", library.task(OWNER, task.taskId()).state());
        var later = processor.create(OWNER, publication.documentId());
        Thread.sleep(350);
        assertEquals("queued", library.task(OWNER, later.taskId()).state());
        assertEquals(1, models.drafts.get());
      }
    }
  }

  private static SynopsisLibraryService library(SqliteAuthorityStore store, Models models) {
    return new SynopsisLibraryService(
        store,
        new SynopsisRepository(store),
        new SynopsisMaterialRepository(store),
        new ManagementRepository(store),
        new DocumentPermissionPolicy(),
        models::revision);
  }

  private static final class Models implements SynopsisModels {
    final AtomicInteger drafts = new AtomicInteger();
    final AtomicInteger verifications = new AtomicInteger();
    Runnable onDraft = () -> {};

    @Override
    public SynopsisDraft draft(SynopsisInput input) {
      drafts.incrementAndGet();
      onDraft.run();
      String id = input.evidence().getFirst().id();
      return new SynopsisDraft(
          false,
          List.of(
              new SynopsisDraft.Item(SynopsisDraft.Section.OVERVIEW, "设备维护记录。", List.of(id)),
              new SynopsisDraft.Item(SynopsisDraft.Section.TOPIC, "设备维护。", List.of(id)),
              new SynopsisDraft.Item(SynopsisDraft.Section.TERM, "设备", List.of(id))));
    }

    @Override
    public boolean verify(SynopsisDraft.Item item, List<SynopsisEvidence> evidence) {
      verifications.incrementAndGet();
      return true;
    }

    @Override
    public String revision() {
      return "synthetic-synopsis-v1";
    }
  }
}
