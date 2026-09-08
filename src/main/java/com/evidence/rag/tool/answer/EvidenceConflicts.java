package com.evidence.rag.tool.answer;

import com.evidence.rag.model.domain.PublishedEvidence;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/** Authorized page context may veto a quote, but never supplies unquoted supporting evidence. */
final class EvidenceConflicts {
  private EvidenceConflicts() {}

  static boolean present(List<QuestionFacts.Fact> facts, List<PublishedEvidence> candidates) {
    var observations = new HashMap<QuestionFacts.Fact, String>();
    var visited = new HashSet<PageIdentity>();
    for (var source : candidates) {
      if (!visited.add(
          new PageIdentity(source.publication().publicationId(), source.page().number()))) {
        continue;
      }
      String page = source.page().text();
      var fields = SourceFields.split(page);
      var sentences = SourceFields.sentences(page);
      for (var fact : facts) {
        for (var field : fact.wholeSentence() ? sentences : fields) {
          String value = fact.value(field.text());
          if (value == null || TruthContext.unsafe(page, field, fields)) {
            continue;
          }
          // Only numeric grouping punctuation is presentation. Signs, decimals, units and words
          // remain.
          String normalized = value.replaceAll("(?<=\\d),(?=\\d{3}(?:\\D|$))", "");
          String previous = observations.putIfAbsent(fact, normalized);
          if (previous != null && !previous.equals(normalized)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private record PageIdentity(String publicationId, int number) {}
}
