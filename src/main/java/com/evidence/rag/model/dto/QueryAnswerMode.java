package com.evidence.rag.model.dto;

/** Explicit library-evidence route, independent of the query attachment's media type. */
public enum QueryAnswerMode {
  TEXT,
  IMAGE,
  AUDIO,
  VIDEO_VISUAL,
  VIDEO_TRANSCRIPT,
  VIDEO_JOINT,
  VIDEO_OCR,
  VIDEO_SUBTITLE
}
