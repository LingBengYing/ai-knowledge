package com.evidence.rag.model.dto;

import com.evidence.rag.model.domain.FileSynopsis;

/** Authorized derived artifact; the Web adapter maps server-owned source links. */
public record SynopsisDocumentResult(String synopsisId, FileSynopsis synopsis) {}
