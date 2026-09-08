package com.evidence.rag.model.dto;

public record DocumentActionResult(
    String documentId, boolean ok, String errorCode, String detail) {}
