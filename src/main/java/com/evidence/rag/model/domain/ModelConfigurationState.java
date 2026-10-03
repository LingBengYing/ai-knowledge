package com.evidence.rag.model.domain;

public record ModelConfigurationState(
    long version,
    TextModelConfiguration draft,
    Long activeVersion,
    TextModelConfiguration active,
    TextIndexAnchor indexAnchor) {
  public static final long MAX_VERSION = 9_007_199_254_740_991L;

  public ModelConfigurationState {
    if (version < 0
        || version > MAX_VERSION
        || (version == 0) != (draft == null)
        || (activeVersion == null) != (active == null)
        || activeVersion != null && (activeVersion < 1 || activeVersion > version)
        || activeVersion != null && activeVersion == version && !active.equals(draft)
        || indexAnchor != null
            && (activeVersion == null
                || indexAnchor.originatingVersion() > activeVersion
                || !indexAnchor.matchesEmbedding(active)
                || indexAnchor.originatingVersion() == activeVersion
                    && !indexAnchor.matchesOriginalRoles(active))) {
      throw ModelValues.invalid();
    }
  }

  public ModelConfigurationState(
      long version,
      TextModelConfiguration draft,
      Long activeVersion,
      TextModelConfiguration active) {
    this(version, draft, activeVersion, active, null);
  }

  public static ModelConfigurationState empty() {
    return new ModelConfigurationState(0, null, null, null);
  }

  @Override
  public String toString() {
    return "ModelConfigurationState[redacted]";
  }
}
