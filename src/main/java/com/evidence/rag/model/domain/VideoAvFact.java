package com.evidence.rag.model.domain;

public record VideoAvFact(
    String id,
    String text,
    VideoAvRequirement requirement,
    boolean visualContribution,
    boolean audioContribution) {
  public VideoAvFact {
    VideoAvProfile.hash(id);
    ModelValues.bounded(text, 1024);
    if (text.isBlank() || requirement == null) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "VideoAvFact[redacted]";
  }
}
