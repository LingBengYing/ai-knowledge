package com.evidence.rag.web.converter;

import com.evidence.rag.model.domain.CleanupBatch;
import com.evidence.rag.model.domain.CleanupPage;
import com.evidence.rag.model.domain.DocumentCleanupState;
import com.evidence.rag.model.dto.DocumentCleanupBatchItemResult;
import com.evidence.rag.model.dto.DocumentCleanupBatchResult;
import com.evidence.rag.model.dto.DocumentCleanupPageResult;
import com.evidence.rag.model.dto.DocumentCleanupResourceResult;
import com.evidence.rag.model.dto.DocumentCleanupResult;

/** Exposes only the fixed public lifecycle vocabulary. */
public final class DocumentCleanupResponseMapper {
  private DocumentCleanupResponseMapper() {}

  public static DocumentCleanupResult state(DocumentCleanupState value) {
    return new DocumentCleanupResult(
        value.documentId(),
        value.cleanupId(),
        value.status(),
        value.cleanupStatus(),
        value.requestedAt(),
        value.updatedAt(),
        value.completedAt(),
        value.errorCode(),
        value.resources().stream()
            .map(resource -> new DocumentCleanupResourceResult(resource.kind(), resource.status()))
            .toList());
  }

  public static DocumentCleanupPageResult page(CleanupPage value) {
    return new DocumentCleanupPageResult(
        value.items().stream().map(DocumentCleanupResponseMapper::state).toList(),
        value.total(),
        value.page(),
        value.pageSize());
  }

  public static DocumentCleanupBatchResult batch(CleanupBatch value) {
    return new DocumentCleanupBatchResult(
        value.items().stream()
            .map(
                item ->
                    new DocumentCleanupBatchItemResult(
                        item.documentId(),
                        item.status(),
                        item.cleanup() == null ? null : state(item.cleanup()),
                        item.errorCode()))
            .toList(),
        value.total());
  }
}
