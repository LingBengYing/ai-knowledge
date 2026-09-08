package com.evidence.rag.tool.answer;

import com.evidence.rag.model.domain.PublishedEvidence;
import java.util.HashMap;
import java.util.List;

/** All hydrated candidates participate; selecting one quote cannot hide another factual value. */
final class EvidenceConflicts {
  private EvidenceConflicts() {}

  static boolean present(List<QuestionFacts.Fact> facts, List<PublishedEvidence> candidates) {
    var observations = new HashMap<QuestionFacts.Fact, String>();
    for (var source : candidates) {
      String page = source.page().text();
      int start = page.offsetByCodePoints(0, source.segment().start());
      int end = page.offsetByCodePoints(0, source.segment().end());
      var fields = SourceFields.split(page);
      var sentences = SourceFields.sentences(page);
      for (var fact : facts) {
        for (var field : fact.wholeSentence() ? sentences : fields) {
          if (field.start() < start
              || field.end() > end
              || TruthContext.unsafe(page, field, fields)) {
            continue;
          }
          String value = fact.value(field.text());
          if (value == null) {
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
}
