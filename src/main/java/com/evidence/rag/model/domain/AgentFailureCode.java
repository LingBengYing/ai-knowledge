package com.evidence.rag.model.domain;

import java.util.Locale;

/** Enumerated diagnostics only; never retain provider, tool, or exception text. */
public enum AgentFailureCode {
  AGENT_UNAVAILABLE,
  AGENT_FAILED,
  AGENT_INVALID_RESPONSE,
  AGENT_TIMEOUT,
  AGENT_LIMIT_EXCEEDED,
  AGENT_CALLBACK_FAILED,
  AGENT_CALLBACK_INVALID,
  AGENT_MODEL_INVALID,
  AGENT_MODEL_TOOL_REQUIRED,
  AGENT_MODEL_UNAVAILABLE,
  AGENT_MODEL_TIMEOUT,
  AGENT_INVALID_ACTION,
  AGENT_INVALID_TOOL_INPUT,
  AGENT_TOOL_FAILED,
  AGENT_INVALID_RESULT,
  AGENT_STEP_LIMIT,
  AGENT_EXECUTION_FAILED,
  AGENT_CANCELLED,
  AGENT_BUSY,
  SCOPE_CHANGED,
  CONFIGURATION_CHANGED,
  EVIDENCE_CHANGED;

  public String code() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static String safe(String code, AgentFailureCode fallback) {
    for (var value : values()) {
      if (value.code().equals(code)) return value.code();
    }
    return fallback.code();
  }
}
