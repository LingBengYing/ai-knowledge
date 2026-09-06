package com.evidence.rag.shared;

public record Actor(String workspaceId, String principalId) {
  public static final String REQUEST_ATTRIBUTE = Actor.class.getName();

  public Actor {
    if (!valid(workspaceId) || !valid(principalId)) {
      throw new Problem(422, "invalid_identity", "身份字段无效。");
    }
  }

  private static boolean valid(String value) {
    return value != null
        && !value.isBlank()
        && value.codePointCount(0, value.length()) <= 200
        && value.codePoints().noneMatch(c -> c < 32 || c == 127);
  }
}
