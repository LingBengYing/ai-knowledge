package com.evidence.rag.exception;

/** Stable failure categories. Transport-specific status mapping belongs to the web boundary. */
public enum FailureKind {
  INVALID_REQUEST,
  UNAUTHENTICATED,
  FORBIDDEN,
  NOT_FOUND,
  METHOD_NOT_ALLOWED,
  TIMEOUT,
  CONFLICT,
  PAYLOAD_TOO_LARGE,
  UNSUPPORTED_MEDIA,
  INVALID_INPUT,
  CAPACITY_EXCEEDED,
  INTERNAL,
  NOT_IMPLEMENTED,
  UNAVAILABLE
}
