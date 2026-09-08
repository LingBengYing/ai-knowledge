package com.evidence.rag.model.entity;

public record FolderEntity(
    String id, String workspaceId, String ownerId, String name, long documentCount) {}
