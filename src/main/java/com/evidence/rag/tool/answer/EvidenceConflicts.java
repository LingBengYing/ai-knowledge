package com.evidence.rag.tool.answer;

import com.evidence.rag.model.domain.GroundingText;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Authorized complete context may veto a quote, but never supplies unquoted supporting evidence.
 */
final class EvidenceConflicts {
  private static final Pattern NUMERIC_SEQUENCE =
      Pattern.compile("(?<!\\p{Nd})\\p{Nd}++(?:[ \\t]*+[,，][ \\t]*+\\p{Nd}++)++");
  private static final Pattern GROUPED_INTEGER = Pattern.compile("[0-9]{1,3}(?:,[0-9]{3})++");

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
      String value = fact.value(field, context, fields, 0, context.length());
      if (value == null
          || (!(fact instanceof QuestionFacts.ProcedureFact)
              && TruthContext.unsafe(context, field, fields))) {
        continue;
      }
      // Only a whole legitimate grouping token is presentation. A mixed sequence such as
      // 7,3,921 must never become the different literal sequence 7,3921.
      values.add(
          NUMERIC_SEQUENCE
              .matcher(value)
              .replaceAll(
                  match -> {
                    String token = match.group();
                    return GROUPED_INTEGER.matcher(token).matches()
                        ? token.replace(",", "")
                        : token;
                  }));
    }
    return List.copyOf(values);
  }
}
