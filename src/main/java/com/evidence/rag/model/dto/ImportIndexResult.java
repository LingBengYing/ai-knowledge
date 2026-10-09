package com.evidence.rag.model.dto;

/** Public progress only, never actor identity or private runtime configuration. */
public record ImportIndexResult(String state, String errorCode, String taskId) {}
