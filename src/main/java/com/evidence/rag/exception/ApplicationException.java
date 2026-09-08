package com.evidence.rag.exception;

import java.util.Objects;

/** Safe application failure: contains no HTTP object or upstream exception cause. */
public final class ApplicationException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final FailureKind kind;
  private final String code;

  public ApplicationException(FailureKind kind, String code, String detail) {
    super(detail);
    this.kind = Objects.requireNonNull(kind);
    this.code = Objects.requireNonNull(code);
  }

  public FailureKind kind() {
    return kind;
  }

  public String code() {
    return code;
  }
}
