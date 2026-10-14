package com.evidence.rag.service;

import com.evidence.rag.model.dto.TaskResult;
import com.evidence.rag.model.entity.TaskEntity;
import java.util.Set;

/** Shared safe task projection; the caller supplies permissions from its current transaction. */
final class TaskResults {
  private TaskResults() {}

  static TaskResult from(TaskEntity task, boolean editable, boolean indexed, String publication) {
    return create(task, editable, editable, indexed, publication);
  }

  /** Indexing may also be retried by restarting an exhausted, never-published initial task. */
  static TaskResult indexing(
      TaskEntity task, boolean editable, String publication, boolean restartable) {
    var result = create(task, editable, editable, true, publication);
    if (result.canRetry()
        || !restartable
        || !editable
        || !Set.of("failed", "cancelled").contains(task.state())) {
      return result;
    }
    return new TaskResult(
        result.taskId(),
        result.documentId(),
        result.revisionId(),
        result.filename(),
        result.state(),
        result.attempt(),
        result.errorCode(),
        result.createdAt(),
        result.updatedAt(),
        true,
        result.canCancel(),
        result.indexing(),
        result.indexPublicationId());
  }

  static TaskResult ingestion(TaskEntity task, boolean editable, boolean creatorCanWrite) {
    return create(task, editable && creatorCanWrite, editable, false, null);
  }

  private static TaskResult create(
      TaskEntity task,
      boolean retryAuthorized,
      boolean cancelAuthorized,
      boolean indexed,
      String publication) {
    return new TaskResult(
        task.id(),
        task.documentId(),
        task.revisionId(),
        task.filename(),
        task.state(),
        task.attempt(),
        task.errorCode(),
        task.createdAt(),
        task.updatedAt(),
        retryAuthorized
            && task.attempt() < 3
            && Set.of("failed", "cancelled").contains(task.state()),
        cancelAuthorized && Set.of("queued", "processing").contains(task.state()),
        indexed,
        publication);
  }
}
