package com.evidence.rag.service;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.IndexTarget;

/** Independently configured legacy media must never execute against a different text target. */
public final class LegacyTextProfileGuard {
  private final ManagedTextRuntime runtime;
  private final IndexTarget legacy;
  private final boolean visualConfigured;

  public LegacyTextProfileGuard(ManagedTextRuntime runtime, IndexTarget legacy) {
    this(runtime, legacy, false);
  }

  public LegacyTextProfileGuard(ManagedTextRuntime runtime, IndexTarget legacy, boolean visualConfigured) {
    this.runtime = runtime;
    this.legacy = legacy;
    this.visualConfigured = visualConfigured;
  }

  public boolean visualConfigured() {
    return visualConfigured;
  }

  public boolean compatible() {
    var current = runtime.currentTarget();
    return current != null
        && (runtime.mediaRebound()
            || (current.equals(legacy) && legacy.modelRevision().equals(runtime.currentModelsRevision())));
  }

  public void requireCompatible() {
    if (runtime.currentTarget() == null) {
      throw new ApplicationException(
          FailureKind.UNAVAILABLE, "text_configuration_required", "请先完成并启用文字模型配置。");
    }
    if (!compatible()) {
      throw new ApplicationException(
          FailureKind.UNAVAILABLE, "media_text_configuration_mismatch", "此媒体功能的模型配置需要由管理员核对后再使用。");
    }
  }

  @Override
  public String toString() {
    return "LegacyTextProfileGuard[redacted]";
  }
}
