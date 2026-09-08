package com.evidence.rag.web.converter;

import com.evidence.rag.model.dto.TaskResult;
import com.evidence.rag.model.vo.TaskResponse;

/**
 * Explicit public-field mapping; variant differences are preserved rather than inferred by JSON.
 */
public final class TaskResponseMapper {
  private TaskResponseMapper() {}

  public static TaskResponse from(TaskResult result) {
    if (result == null) {
      return null;
    }
    if (result.indexing()) {
      return new TaskResponse.Indexing(
          result.taskId(),
          result.documentId(),
          result.revisionId(),
          result.filename(),
          result.state(),
          result.state(),
          result.attempt(),
          result.errorCode(),
          result.createdAt(),
          result.updatedAt(),
          result.canRetry(),
          result.canCancel(),
          result.indexPublicationId());
    }
    return new TaskResponse.Ingestion(
        result.taskId(),
        result.documentId(),
        result.revisionId(),
        result.filename(),
        result.state(),
        result.state(),
        result.attempt(),
        result.errorCode(),
        result.createdAt(),
        result.updatedAt(),
        result.canRetry(),
        result.canCancel());
  }
}
