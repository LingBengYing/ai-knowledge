package com.evidence.rag.model.domain;

/** Current lifecycle of a derived page, independent of immutable content versions. */
public record WikiPageLifecycle(String state, long version) {}
