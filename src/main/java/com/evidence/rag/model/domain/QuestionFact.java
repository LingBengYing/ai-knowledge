package com.evidence.rag.model.domain;

import java.nio.charset.StandardCharsets;

/** One server-planned requirement; raw requirement text is transient and never an audit field. */
public record QuestionFact(int ordinal, String id, String requirement) {
  public QuestionFact {
    if (ordinal < 0
        || ordinal >= 8
        || id == null
        || !id.matches("[a-f0-9]{64}")
        || requirement == null
        || requirement.isBlank()
        || requirement.length() > 4096
        || requirement.getBytes(StandardCharsets.UTF_8).length > 8192
        || requirement
            .codePoints()
            .anyMatch(point -> point == 0 || (point >= 0xD800 && point <= 0xDFFF))) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "QuestionFact[redacted]";
  }
}
