package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record FolderRemovalResult(@JsonProperty("folder_id") String folderId, String status) {}
