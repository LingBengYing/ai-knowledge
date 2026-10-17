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

  /** Outcome of one dispatch attempt. */
  public enum Dispatch {
    DISPATCHED,
    IDLE,
    /** The library was at capacity; the request is pending again and the caller backs off. */
    BUSY
  }

  /** Dispatches the oldest pending request of any pipeline. */
  public boolean processNext() {
    return processNext(null) == Dispatch.DISPATCHED;
  }

  /**
   * Corpus dispatch only enqueues an indexing task and returns at once; it never waits behind a
   * sound or video_av build.
   */
  public Dispatch processNextCorpus() {
    return processNext(false);
  }

  /**
   * Sound and video_av builds run synchronously (decode, embed, publish) on their own bounded
   * workers. A build refused only because its library is at capacity goes back to pending and is
   * not a failure.
   */
  public Dispatch processNextMedia() {
    return processNext(true);
  }

  private static final java.util.Set<String> MEDIA_BUSY =
      java.util.Set.of("sound_index_busy", "video_av_index_busy");

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
        if (MEDIA_BUSY.contains(rejected.code())) {
          store.transaction(
              () -> {
                requests.release(request.revisionId());
                return null;
              });
          return Dispatch.BUSY;
        }
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
