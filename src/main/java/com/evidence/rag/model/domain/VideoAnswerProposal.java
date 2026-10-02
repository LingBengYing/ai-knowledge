package com.evidence.rag.model.domain;

import java.util.List;

/** Transient proposed answer; only a successful authority trace receipt permits its release. */
public record VideoAnswerProposal(
    String answer, List<VideoSourceEvidence> sources, TraceDraft trace) {
  public VideoAnswerProposal {
    if (answer == null || sources == null || trace == null) {
      throw ModelValues.invalid();
    }
    sources = List.copyOf(sources);
  }

  @Override
  public String toString() {
    return "VideoAnswerProposal[redacted]";
  }
}
