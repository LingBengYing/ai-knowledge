package com.evidence.rag.tool.answer;

import com.evidence.rag.model.domain.GroundingText;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * Authorized complete context may veto a quote, but never supplies unquoted supporting evidence.
 */
final class EvidenceConflicts {
  private EvidenceConflicts() {}

  static boolean present(List<QuestionFacts.Fact> facts, List<GroundingText> candidates) {
    var observations = new HashMap<QuestionFacts.Fact, String>();
    var visited = new HashSet<String>();
    for (var source : candidates) {
      if (!visited.add(source.contextId())) {
        continue;
      }
      String page = source.contextText();
      var fields = SourceFields.split(page);
      var sentences = SourceFields.sentences(page);
      for (var fact : facts) {
        for (String normalized : observations(fact, page, fields, sentences)) {
          String previous = observations.putIfAbsent(fact, normalized);
          if (previous != null && !previous.equals(normalized)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  static List<String> observations(QuestionFacts.Fact fact, String context) {
    return observations(
        fact, context, SourceFields.split(context), SourceFields.sentences(context));
  }

  private static List<String> observations(
      QuestionFacts.Fact fact,
      String context,
      List<SourceFields.Field> fields,
      List<SourceFields.Field> sentences) {
    var possible =
        fact instanceof QuestionFacts.ProcedureFact procedure
            ? ProcedureEvidence.groups(procedure, context, sentences).stream()
                .filter(
                    group ->
                        group.recognized() && !ProcedureEvidence.unsafe(context, group, fields))
                .map(ProcedureEvidence.Group::range)
                .toList()
            : fields;
    var values = new ArrayList<String>();
    for (var field : possible) {
      String value = fact.value(field.text());
      if (value == null
          || (!(fact instanceof QuestionFacts.ProcedureFact)
              && TruthContext.unsafe(context, field, fields))) {
        continue;
      }
      // Only numeric grouping punctuation is presentation. Signs, decimals, units and words remain.
      values.add(value.replaceAll("(?<=\\d),(?=\\d{3}(?:\\D|$))", ""));
    }
    return List.copyOf(values);
  }
}
