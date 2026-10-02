package com.evidence.rag.model.domain;

/** A page-one code point interval and its original-image pixel box, not model-supplied geometry. */
public record ImageTextRegion(int start, int end, int left, int top, int right, int bottom) {}
