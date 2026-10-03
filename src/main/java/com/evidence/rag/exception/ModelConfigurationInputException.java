package com.evidence.rag.exception;

import java.util.Set;

/** Contains a fixed field identifier, never submitted values or upstream error text. */
public final class ModelConfigurationInputException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private static final Set<String> FIELDS =
      Set.of(
          "request",
          "base_version",
          "version",
          "role",
          "embedding.model",
          "embedding.dimensions",
          "embedding.revision",
          "embedding.api_key",
          "rerank.model",
          "rerank.api_key",
          "generation.model",
          "generation.api_key");
  private final String field;

  public ModelConfigurationInputException(String field) {
    super("模型配置字段、类型或取值无效。", null, false, true);
    this.field = field != null && FIELDS.contains(field) ? field : "request";
  }

  public String field() {
    return field;
  }
}
