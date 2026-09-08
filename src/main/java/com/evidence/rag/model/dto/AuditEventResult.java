package com.evidence.rag.model.dto;

public record AuditEventResult(
    String id,
    String workspaceId,
    String actorId,
    String entityId,
    String action,
    String fieldsJson,
    String beforeSha256,
    String afterSha256,
    String createdAt) {}
