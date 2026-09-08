package com.evidence.rag.model.domain;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;

public record Actor(String workspaceId, String principalId) {
  public Actor {
    if (!valid(workspaceId) || !valid(principalId)) {
      throw new ApplicationException(FailureKind.INVALID_INPUT, "invalid_identity", "身份字段无效。");
    }
  }

  private static boolean valid(String value) {
    return value != null
        && !value.isBlank()
        && value.codePointCount(0, value.length()) <= 200
        && value.codePoints().noneMatch(c -> c < 32 || c == 127);
  }
}
