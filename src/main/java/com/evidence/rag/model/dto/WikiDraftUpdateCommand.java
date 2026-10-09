package com.evidence.rag.model.dto;

public record WikiDraftUpdateCommand(long version, String title, String body) {}
