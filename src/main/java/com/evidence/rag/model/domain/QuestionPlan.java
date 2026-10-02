package com.evidence.rag.model.domain;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;

/** Complete transient question plan; every fact belongs to the exact complete question. */
public record QuestionPlan(
    String question, String questionSha256, String plannerRevision, List<QuestionFact> facts) {
  public QuestionPlan {
    if (question == null
        || question.isBlank()
        || question.length() > 4096
        || question.getBytes(StandardCharsets.UTF_8).length > 4096
        || question
            .codePoints()
            .anyMatch(point -> point == 0 || (point >= 0xD800 && point <= 0xDFFF))
        || !ModelValues.sha256(question.getBytes(StandardCharsets.UTF_8)).equals(questionSha256)
        || facts == null
        || facts.isEmpty()
        || facts.size() > 8) {
      throw ModelValues.invalid();
    }
    ModelValues.identifier(plannerRevision, 200);
    var requirements = new HashSet<String>();
    for (int ordinal = 0; ordinal < facts.size(); ordinal++) {
      var fact = facts.get(ordinal);
      if (fact == null
          || fact.ordinal() != ordinal
          || !requirements.add(fact.requirement())
          || !fact.id().equals(factIdentity(questionSha256, ordinal, fact.requirement()))) {
        throw ModelValues.invalid();
      }
    }
    facts = List.copyOf(facts);
  }

  private static String factIdentity(String questionSha256, int ordinal, String requirement) {
    String requirementSha256 = ModelValues.sha256(requirement.getBytes(StandardCharsets.UTF_8));
    return ModelValues.sha256(
        ("video-fact-v1\n" + questionSha256 + "\n" + ordinal + "\n" + requirementSha256)
            .getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public String toString() {
    return "QuestionPlan[redacted]";
  }
}
