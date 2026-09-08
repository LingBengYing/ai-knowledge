package com.evidence.rag.model.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record DocumentResponse(
    @JsonProperty("document_id") String documentId,
    String filename,
    String status,
    @JsonProperty("active_revision_id") String activeRevisionId,
    @JsonProperty("registered_revision_id") String registeredRevisionId,
    @JsonProperty("segment_count") int segmentCount,
    @JsonProperty("updated_at") String updatedAt,
    List<String> modalities,
    @JsonProperty("media_info") MediaInfoResponse mediaInfo,
    @JsonProperty("display_name") String displayName,
    @JsonProperty("folder_id") String folderId,
    @JsonProperty("folder_name") String folderName,
    List<String> tags,
    @JsonProperty("current_role") String currentRole,
    @JsonProperty("can_edit") boolean canEdit,
    @JsonProperty("can_delete") boolean canDelete,
    @JsonProperty("can_reindex") boolean canReindex,
    @JsonProperty("can_answer") boolean canAnswer,
    @JsonProperty("index_status") String indexStatus,
    @JsonProperty("latest_index_job") TaskResponse latestIndexJob,
    @JsonProperty("index_publication_id") String indexPublicationId,
    @JsonProperty("can_index") boolean canIndex,
    @JsonProperty("latest_job") TaskResponse latestJob,
    @JsonProperty("synthetic_fixture") boolean syntheticFixture,
    @JsonProperty("document_type") String documentType) {
  public DocumentResponse {
    modalities = List.copyOf(modalities);
    tags = List.copyOf(tags);
  }
}
