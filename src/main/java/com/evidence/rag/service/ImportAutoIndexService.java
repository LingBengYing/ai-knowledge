package com.evidence.rag.service;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.entity.ImportIndexRequestEntity;
import com.evidence.rag.repository.ImportIndexRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import java.util.function.Supplier;

/** One durable import continuation; actual indexing stays in the existing media processors. */
public final class ImportAutoIndexService {
  private final SqliteAuthorityStore store;
  private final ImportIndexRepository requests;
  private final Supplier<IndexingTaskProcessor> indexing;

  public ImportAutoIndexService(
      SqliteAuthorityStore store, Supplier<IndexingTaskProcessor> indexing) {
    this.store = store;
    this.requests = new ImportIndexRepository(store);
    this.indexing = indexing;
    store.transaction(
        () -> {
          requests.recover();
          return null;
        });
  }

  /** Outcome of one dispatch attempt. */
  public enum Dispatch {
    DISPATCHED,
    IDLE
  }

  /** Dispatches the oldest pending request of any pipeline. */
  public boolean processNext() {
    return processNext(null) == Dispatch.DISPATCHED;
  }

  /** Corpus dispatch only enqueues an indexing task and returns at once. */
  public Dispatch processNextCorpus() {
    return processNext(false);
  }

  /**
   * Non-corpus requests: only legacy sound/video_av rows written before those libraries were
   * removed (2026-11-03). They can no longer be built, so each one fails as indexing_unavailable
   * instead of staying pending and holding its batch open.
   */
  public Dispatch processNextMedia() {
    return processNext(true);
  }

  private Dispatch processNext(Boolean media) {
    var lease = store.operationGate().tryOperation();
    if (lease.isEmpty()) {
      return Dispatch.IDLE;
    }
    try (var operation = lease.orElseThrow()) {
      var next = store.transaction(() -> requests.claim(media));
      if (next.isEmpty()) {
        return Dispatch.IDLE;
      }
      var request = next.orElseThrow();
      String task = null;
      String failure = null;
      try {
        task = dispatch(request);
      } catch (ApplicationException rejected) {
        failure = rejected.code();
      } catch (RuntimeException unavailable) {
        failure = "indexing_failed";
      }
      String taskId = task, errorCode = failure;
      // A failed dispatch fails only this file; a batch bulk retry dispatches it again.
      store.transaction(
          () -> {
            requests.finish(request.revisionId(), taskId, errorCode);
            return null;
          });
      return Dispatch.DISPATCHED;
    }
  }

  private String dispatch(ImportIndexRequestEntity request) {
    if ("corpus".equals(request.pipeline())) {
      String existing =
          store.transaction(
              () ->
                  requests.existingTask(
                      request.actor(), request.documentId(), request.revisionId()));
      if (existing != null) {
        return existing;
      }
      if (!"parsed".equals(request.parseState())) {
        throw new ApplicationException(FailureKind.CONFLICT, "parsing_incomplete", "解析未完成，未建立索引。");
      }
      var processor = indexing.get();
      if (processor == null) {
        throw unavailable();
      }
      return (request.replacementId() == null
              ? processor.createImported(
                  request.actor(), request.documentId(), request.revisionId())
              : processor.replace(
                  request.actor(),
                  request.documentId(),
                  request.revisionId(),
                  request.baseRevisionId()))
          .taskId();
    }
    throw unavailable();
  }

  private static ApplicationException unavailable() {
    return new ApplicationException(FailureKind.UNAVAILABLE, "indexing_unavailable", "当前索引模型尚不可用。");
  }
}
