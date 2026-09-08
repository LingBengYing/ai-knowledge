package com.evidence.rag.exception;

/** Safe projection failure shared by data validation and remote protocol Adapters. */
public final class ProjectionException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final String code;

  public ProjectionException(String code) {
    super("检索投影调用或配置未通过安全校验。", null, false, true);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
