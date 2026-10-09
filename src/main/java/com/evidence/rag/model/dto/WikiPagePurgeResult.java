package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Public receipt only; the deleted page body and internal audit are never returned. */
public record WikiPagePurgeResult(@JsonProperty("page_id") String pageId, String state) {}
