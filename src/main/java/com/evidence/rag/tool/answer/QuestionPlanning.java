package com.evidence.rag.tool.answer;

import com.evidence.rag.model.domain.GroundingText;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.QuestionFact;
import com.evidence.rag.model.domain.QuestionPlan;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Complete bounded question planning; unsupported grammar has no partial successful plan. */
public final class QuestionPlanning {
  public static final String VERSION = "java-question-planning-v3-natural-procedures";
  private static final Pattern UNSUPPORTED_CONTEXT =
      Pattern.compile(
          "[,，:：]|如果|假如|除非|仅当|仅在|只有|期间|(?:开机|关机|运行|启动|复位|重启|审批|批准)(?:时|前|后)"
              + "|(?:^|[？?。！!；;\\r\\n])\\s*若|\\b(?:if|when|unless|during|before|after|while|provided|subject\\s+to)\\b",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern SHARED_COLOR = Pattern.compile("^(.+?)(?:分别|各自)(?:是|为)?什么颜色$");

  private QuestionPlanning() {}

  public static Optional<QuestionPlan> plan(String question) {
    if (question == null
        || question.isBlank()
        || question.length() > 4096
        || question.getBytes(StandardCharsets.UTF_8).length > 4096
        || !validUnicode(question)) {
      return Optional.empty();
    }
    var requirements = QuestionFacts.parse(question);
    if (requirements.isEmpty() || !preservesContext(question, requirements.size())) {
      return Optional.empty();
    }
    String questionSha256 = sha(question);
    var facts = new ArrayList<QuestionFact>();
    try {
      for (var requirement : requirements) {
        int ordinal = facts.size();
        String canonical = requirement.requirement();
        String id =
            sha("video-fact-v1\n" + questionSha256 + "\n" + ordinal + "\n" + sha(canonical));
        facts.add(new QuestionFact(ordinal, id, canonical));
      }
      return Optional.of(new QuestionPlan(question, questionSha256, VERSION, facts));
    } catch (RuntimeException unsupportedRepresentation) {
      return Optional.empty();
    }
  }

  public static boolean agree(
      String question,
      QuestionFact fact,
      List<String> visualClaims,
      List<String> transcriptQuotes) {
    var planned = plan(question);
    if (fact == null
        || planned.isEmpty()
        || fact.ordinal() >= planned.get().facts().size()
        || !planned.get().facts().get(fact.ordinal()).equals(fact)) {
      return false;
    }
    var requirement = QuestionFacts.parse(question).get(fact.ordinal());
    Set<String> visual = values(requirement, visualClaims, 8);
    Set<String> transcript = values(requirement, transcriptQuotes, 32);
    return visual.size() == 1 && visual.equals(transcript);
  }

  /** Complete transcript context can veto verified visual claims, never prove or authorize them. */
  public static boolean conflictsWithTranscript(
      String question,
      QuestionFact fact,
      List<String> verifiedVisualClaims,
      GroundingText transcriptContext) {
    var planned = plan(question);
    if (fact == null
        || planned.isEmpty()
        || fact.ordinal() >= planned.get().facts().size()
        || !planned.get().facts().get(fact.ordinal()).equals(fact)) {
      return true;
    }
    if (transcriptContext == null) {
      return false;
    }
    var requirement = QuestionFacts.parse(question).get(fact.ordinal());
    var transcript =
        new HashSet<>(EvidenceConflicts.observations(requirement, transcriptContext.contextText()));
    if (transcript.isEmpty()) {
      return false;
    }
    var visual = values(requirement, verifiedVisualClaims, 8);
    return visual.size() != 1 || transcript.size() != 1 || !visual.equals(transcript);
  }

  private static boolean preservesContext(String question, int factCount) {
    // The old parser is not a discourse planner. New joint proofs must not lose a condition
    // shared across requirements, even when each shortened requirement happens to be answerable.
    if (factCount > 1 && UNSUPPORTED_CONTEXT.matcher(question).find()) {
      return false;
    }
    for (String clause : question.split("[？?。！!；;\\r\\n]+")) {
      String stem = clause.strip().replaceFirst("^(?:请问|请说明|请告诉我|告诉我)", "").strip();
      var color = SHARED_COLOR.matcher(stem);
      if (color.matches()) {
        String[] subjects = color.group(1).split("以及|[和与及、]", -1);
        // Unlike scalar facts, the legacy color grammar cannot inherit an explicit owner.
        if (subjects.length > 1) {
          for (String subject : subjects) {
            if (subject.contains("的")) {
              return false;
            }
          }
        }
      }
    }
    return true;
  }

  private static Set<String> values(QuestionFacts.Fact fact, List<String> statements, int maximum) {
    if (statements == null || statements.isEmpty() || statements.size() > maximum) {
      return Set.of();
    }
    var values = new HashSet<String>();
    for (String statement : statements) {
      if (statement == null
          || statement.isBlank()
          || statement.length() > 4096
          || !validUnicode(statement)) {
        return Set.of();
      }
      if (fact.wholeSentence()) {
        String value = fact.value(statement);
        if (value != null) {
          values.add(value.replaceAll("(?<=\\d),(?=\\d{3}(?:\\D|$))", ""));
        }
      } else {
        for (var field : SourceFields.split(statement)) {
          String value = fact.value(field.text());
          if (value != null) {
            values.add(value.replaceAll("(?<=\\d),(?=\\d{3}(?:\\D|$))", ""));
          }
        }
      }
    }
    return values;
  }

  private static boolean validUnicode(String text) {
    return text.codePoints().noneMatch(point -> point == 0 || (point >= 0xD800 && point <= 0xDFFF));
  }

  private static String sha(String text) {
    return ModelValues.sha256(text.getBytes(StandardCharsets.UTF_8));
  }
}
