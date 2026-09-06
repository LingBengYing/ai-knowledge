package com.evidence.rag.shared;

public final class Problem extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final int status;
  private final String code;

  public Problem(int status, String code, String detail) {
    super(detail);
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }
}
