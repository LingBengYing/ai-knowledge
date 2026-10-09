package com.evidence.rag.model.dto;

import com.evidence.rag.model.domain.ModelValues;

/** Both current content and lifecycle must match the page the caller reviewed. */
public record WikiPageLifecycleCommand(long version, long lifecycleVersion) {
  public WikiPageLifecycleCommand {
    if (version < 1
        || version > 9_007_199_254_740_991L
        || lifecycleVersion < 0
        || lifecycleVersion >= 9_007_199_254_740_991L) {
      throw ModelValues.invalid();
    }
  }
}
