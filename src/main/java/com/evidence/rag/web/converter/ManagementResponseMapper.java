package com.evidence.rag.web.converter;

import com.evidence.rag.model.dto.DocumentActionResult;
import com.evidence.rag.model.dto.DocumentPageResult;
import com.evidence.rag.model.dto.DocumentResult;
import com.evidence.rag.model.dto.FolderListResult;
import com.evidence.rag.model.dto.FolderResult;
import com.evidence.rag.model.vo.DocumentActionReceipt;
import com.evidence.rag.model.vo.DocumentActionResponse;
import com.evidence.rag.model.vo.DocumentActionsResponse;
import com.evidence.rag.model.vo.DocumentPageResponse;
import com.evidence.rag.model.vo.DocumentResponse;
import com.evidence.rag.model.vo.MediaInfoResponse;
import com.evidence.rag.model.vo.TagListResponse;
import java.util.List;

/** Serializes the established HTTP contract without leaking persistence entities. */
public final class ManagementResponseMapper {
  private ManagementResponseMapper() {}

  public static DocumentPageResponse page(DocumentPageResult r) {
    return new DocumentPageResponse(
        r.items().stream().map(ManagementResponseMapper::document).toList(),
        r.total(),
        r.page(),
        r.pageSize(),
        r.totalPages());
  }

  public static DocumentResponse document(DocumentResult r) {
    return new DocumentResponse(
        r.documentId(),
        r.filename(),
        r.status(),
        r.activeRevisionId(),
        r.registeredRevisionId(),
        r.segmentCount(),
        r.updatedAt(),
        List.of(),
        new MediaInfoResponse(r.mimeType(), r.sizeBytes(), r.sha256()),
        r.displayName(),
        r.folderId(),
        r.folderName(),
        r.tags(),
        r.currentRole(),
        r.canEdit(),
        false,
        false,
        false,
        r.indexStatus(),
        r.latestIndexJob() == null ? null : TaskResponseMapper.from(r.latestIndexJob()),
        r.indexPublicationId(),
        r.canIndex(),
        r.latestJob() == null ? null : TaskResponseMapper.from(r.latestJob()),
        r.syntheticFixture(),
        r.documentType());
  }

  public static FolderListResult folders(List<FolderResult> r) {
    return new FolderListResult(r);
  }

  public static TagListResponse tags(List<String> r) {
    return new TagListResponse(r);
  }

  public static DocumentActionsResponse actions(List<DocumentActionResult> r) {
    return new DocumentActionsResponse(
        r.stream()
            .map(
                item ->
                    new DocumentActionResponse(
                        item.documentId(),
                        item.ok(),
                        item.ok() ? new DocumentActionReceipt(item.documentId(), "updated") : null,
                        item.errorCode(),
                        item.detail()))
            .toList());
  }
}
