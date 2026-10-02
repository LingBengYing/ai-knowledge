package com.evidence.rag.model.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Complete group-local proof. Never publish without current authority and trace validation. */
public record VideoAssessment(
    String sourceSha256,
    String manifestSha256,
    VideoEvidenceGroup group,
    String questionSha256,
    Mode mode,
    List<String> factIds,
    List<VideoFactProof> proofs,
    String textModelRevision,
    String visionModelRevision,
    String policyRevision,
    String refusalReason) {
  public enum Mode {
    VISUAL,
    TRANSCRIPT,
    JOINT
  }

  private static final Set<String> REASONS =
      Set.of(
          "unsupported_question",
          "incomplete_evidence",
          "conflicting_evidence",
          "unsafe_evidence",
          "invalid_quote",
          "model_failure",
          "scope_changed",
          "configuration_changed",
          "processing_interrupted",
          "processing_timeout");

  public VideoAssessment {
    for (String sha : List.of(sourceSha256, manifestSha256, questionSha256)) {
      if (!sha.matches("[a-f0-9]{64}")) {
        throw ModelValues.invalid();
      }
    }
    ModelValues.identifier(textModelRevision, 200);
    ModelValues.identifier(visionModelRevision, 200);
    ModelValues.identifier(policyRevision, 200);
    if (group == null
        || mode == null
        || factIds == null
        || proofs == null
        || factIds.size() > 8
        || new HashSet<>(factIds).size() != factIds.size()) {
      throw ModelValues.invalid();
    }
    for (String fact : factIds) {
      if (fact == null || !fact.matches("[a-f0-9]{64}")) {
        throw ModelValues.invalid();
      }
    }
    if (refusalReason != null) {
      if (!REASONS.contains(refusalReason) || !proofs.isEmpty()) {
        throw ModelValues.invalid();
      }
    } else {
      if (factIds.isEmpty()
          || !proofs.stream().map(VideoFactProof::factId).toList().equals(factIds)) {
        throw ModelValues.invalid();
      }
      boolean visual = proofs.stream().anyMatch(p -> p.visualSupport() == 1);
      boolean transcript = proofs.stream().anyMatch(p -> p.transcriptSupport() == 1);
      if ((visual && group.frameId() == null)
          || (transcript && group.transcriptSpanId() == null)
          || proofs.stream()
              .flatMap(p -> p.transcriptQuotes().stream())
              .anyMatch(q -> !q.physicalId().equals(group.transcriptSpanId()))
          || (mode == Mode.JOINT && (!visual || !transcript))
          || (mode == Mode.VISUAL
              && (transcript || proofs.stream().anyMatch(p -> p.visualSupport() == 0)))
          || (mode == Mode.TRANSCRIPT
              && (visual || proofs.stream().anyMatch(p -> p.transcriptSupport() == 0)))) {
        throw ModelValues.invalid();
      }
    }
    factIds = List.copyOf(factIds);
    proofs = List.copyOf(proofs);
  }

  public boolean supported() {
    return refusalReason == null;
  }

  @Override
  public String toString() {
    return "VideoAssessment[redacted]";
  }
}
