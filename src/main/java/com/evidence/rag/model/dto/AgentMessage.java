package com.evidence.rag.model.dto;

import com.evidence.rag.model.domain.ModelValues;
import java.util.Set;

/** Plain chat messages from the private Agent protocol; no tool names or destinations. */
public record AgentMessage(String role, String content) {
  public AgentMessage {
    if (!Set.of("system", "user", "assistant").contains(role == null ? "" : role)
        || content == null
        || content.isBlank()
        || content.length() > 262144
        || content.indexOf(0) >= 0) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "AgentMessage[redacted]";
  }
}
