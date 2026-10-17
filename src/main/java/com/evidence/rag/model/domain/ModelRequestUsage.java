package com.evidence.rag.model.domain;

/** Counts include dispatched failures; incomplete audit never means zero-cost completion. */
public record ModelRequestUsage(int started, int finished, boolean complete) {}
