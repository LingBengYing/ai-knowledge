package com.evidence.rag.model.domain;

import java.util.HashSet;
import java.util.List;

/** Complete question fact coverage tied to one actual published group. */
public record VideoTraceProof(
    String publicationId,
    String groupId,
    VideoAssessment.Mode mode,
    List<VideoTraceFact> facts,
    String textModelRevision,
    String visionModelRevision,
    String policyRevision) {
  public VideoTraceProof {
    ModelValues.identifier(publicationId, 128);
    ModelValues.identifier(textModelRevision, 200);
    ModelValues.identifier(visionModelRevision, 200);
    ModelValues.identifier(policyRevision, 200);
    if (groupId == null
        || !groupId.matches("video-group-[a-f0-9]{64}")
        || mode == null
        || facts == null
        || facts.isEmpty()
        || facts.size() > 8) {
      throw ModelValues.invalid();
    }
    var identities = new HashSet<String>();
    boolean visual = false;
    boolean transcript = false;
    for (int ordinal = 0; ordinal < facts.size(); ordinal++) {
      var fact = facts.get(ordinal);
      if (fact == null
          || fact.ordinal() != ordinal
          || !identities.add(fact.factSha256())
          || (mode == VideoAssessment.Mode.VISUAL
              && (fact.visualSupport() != 1 || fact.transcriptSupport() != 0))
          || (mode == VideoAssessment.Mode.TRANSCRIPT
              && (fact.visualSupport() != 0 || fact.transcriptSupport() != 1))) {
        throw ModelValues.invalid();
      }
      visual |= fact.visualSupport() == 1;
      transcript |= fact.transcriptSupport() == 1;
    }
    if (mode == VideoAssessment.Mode.JOINT && (!visual || !transcript)) {
      throw ModelValues.invalid();
    }
    facts = List.copyOf(facts);
  }

  @Override
  public String toString() {
    return "VideoTraceProof[redacted]";
  }
}
