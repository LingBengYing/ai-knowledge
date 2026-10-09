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
  private final SoundLibraryService sounds;
  private final VideoAvLibraryService videos;

  public ImportAutoIndexService(
      SqliteAuthorityStore store,
      Supplier<IndexingTaskProcessor> indexing,
      SoundLibraryService sounds,
      VideoAvLibraryService videos) {
    this.store = store;
    this.requests = new ImportIndexRepository(store);
    this.indexing = indexing;
    this.sounds = sounds;
    this.videos = videos;
    store.transaction(
        () -> {
          requests.recover();
          return null;
        });
  }

  public boolean processNext() {
    var lease = store.operationGate().tryOperation();
    if (lease.isEmpty()) {
      return false;
    }
    try (var operation = lease.orElseThrow()) {
      var next = store.transaction(requests::claim);
      if (next.isEmpty()) {
        return false;
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
      store.transaction(
          () -> {
            requests.finish(request.revisionId(), taskId, errorCode);
            return null;
          });
      return true;
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
    if ("sound".equals(request.pipeline()) && sounds != null) {
      var result =
          request.replacementId() == null
              ? sounds.build(request.actor(), request.documentId())
              : sounds.buildReplacement(
                  request.actor(), request.documentId(), request.replacementId());
      if (result.publication() == null
          || !result.original().revisionId().equals(request.revisionId())) {
        throw unavailable();
      }
      return null;
    }
    if ("video_av".equals(request.pipeline()) && videos != null) {
      var result =
          request.replacementId() == null
              ? videos.build(request.actor(), request.documentId())
              : videos.buildReplacement(
                  request.actor(), request.documentId(), request.replacementId());
      if (result.publication() == null
          || !result.original().revisionId().equals(request.revisionId())) {
        throw unavailable();
      }
      return null;
    }
    throw unavailable();
  }

  private static ApplicationException unavailable() {
    return new ApplicationException(FailureKind.UNAVAILABLE, "indexing_unavailable", "当前索引模型尚不可用。");
  }
}
