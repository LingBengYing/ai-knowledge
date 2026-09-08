package com.evidence.rag.model.dto;

/** Only an answered receipt authorizes releasing a candidate answer. Contains no source text. */
public record TraceReceipt(String traceId, String outcome, String reasonCode) {}
