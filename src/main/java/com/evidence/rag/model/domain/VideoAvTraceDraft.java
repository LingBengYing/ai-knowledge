package com.evidence.rag.model.domain;

import java.util.List;

public record VideoAvTraceDraft(
    String questionSha256,
    String answerSha256,
    String status,
    String reasonCode,
    VideoAvMode mode,
    List<VideoAvProof> citations,
    String modelRevision,
    String policyRevision,
    VideoAvQueryTrace queryTrace) {
  public VideoAvTraceDraft(
      String questionSha256,
      String answerSha256,
      String status,
      String reasonCode,
      VideoAvMode mode,
      List<VideoAvProof> citations,
      String modelRevision,
      String policyRevision) {
    this(
        questionSha256,
        answerSha256,
        status,
        reasonCode,
        mode,
        citations,
        modelRevision,
        policyRevision,
        null);
  }

  public VideoAvTraceDraft {
    VideoAvProfile.hash(questionSha256);
    ModelValues.identifier(modelRevision, 200);
    if (mode == null
        || citations == null
        || citations.size() > 32
        || !"java-video-av-answer-v1".equals(policyRevision)
        || (!"answered".equals(status) && !"abstained".equals(status))) {
      throw ModelValues.invalid();
    }
    if ("answered".equals(status)) {
      VideoAvProfile.hash(answerSha256);
      if (reasonCode != null || citations.isEmpty()) {
        throw ModelValues.invalid();
      }
    } else if (answerSha256 != null
        || !citations.isEmpty()
        || reasonCode == null
        || !reasonCode.matches("[a-z][a-z0-9_]{0,99}")) {
      throw ModelValues.invalid();
    }
    for (var proof : citations) {
      if (proof.mode() != mode) {
        throw ModelValues.invalid();
      }
    }
    if (queryTrace != null
        && (queryTrace.mode() != mode
            || !queryTrace.questionSha256().equals(questionSha256)
            || "answered".equals(status)
                && !"prepared".equals(queryTrace.attachments().getFirst().status()))) {
      throw ModelValues.invalid();
    }
    citations = List.copyOf(citations);
  }

  @Override
  public String toString() {
    return "VideoAvTraceDraft[redacted]";
  }
}
