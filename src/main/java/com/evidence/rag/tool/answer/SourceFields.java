package com.evidence.rag.tool.answer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Exact source ranges are retained separately from any question-matching normalization. */
final class SourceFields {
  private static final Pattern BOUNDARY =
      Pattern.compile(
          "[。！？!?；;\\r\\n]+|\\.(?=\\s+[A-Z\\p{IsHan}]|\\s*$)|，|(?<!\\d),|(?<=\\d),(?!\\d{3}(?:\\D|$))");
  private static final Pattern SENTENCE_BOUNDARY =
      Pattern.compile("[。！？!?；;\\r\\n]+|\\.(?=\\s+[A-Z\\p{IsHan}]|\\s*$)");
  private static final Pattern CONNECTOR = Pattern.compile("并且|同时|以及|还有|然后");
  private static final Pattern ASSIGNMENT =
      Pattern.compile("是|为|=|(?<!\\d)[:：](?!\\d)|\\b(?:is|are)\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern NEXT_ASSIGNMENT =
      Pattern.compile(
          "^[^，,。！？!?;；\\r\\n]{1,40}?(?:是|为|[:：=]|\\bis\\b)\\s*\\S", Pattern.CASE_INSENSITIVE);
  private static final Pattern NON_NEUTRAL_LABEL =
      Pattern.compile(
          "不|未|无|非|仅|只|错误|误传|假设|草案|待|\\b(?:not|if|only|unless|draft|false)\\b",
          Pattern.CASE_INSENSITIVE);

  private SourceFields() {}

  static List<Field> split(String text) {
    return ranges(text, true);
  }

  static List<Field> sentences(String text) {
    return ranges(text, false);
  }

  private static List<Field> ranges(String text, boolean clauses) {
    var fields = new ArrayList<Field>();
    int start = 0;
    var boundaries = (clauses ? BOUNDARY : SENTENCE_BOUNDARY).matcher(text);
    while (boundaries.find()) {
      if (clauses) {
        splitAssignments(fields, text, start, boundaries.start());
      } else {
        add(fields, text, start, boundaries.start());
      }
      start = boundaries.end();
    }
    if (clauses) {
      splitAssignments(fields, text, start, text.length());
    } else {
      add(fields, text, start, text.length());
    }
    return List.copyOf(fields);
  }

  static boolean hasNestedAssignment(String field) {
    var assignments = ASSIGNMENT.matcher(statement(field));
    return assignments.find() && assignments.find();
  }

  static String statement(String field) {
    int colon = field.indexOf('：');
    if (colon < 0) {
      colon = field.indexOf(':');
    }
    if (colon > 0 && colon <= 24) {
      String label = field.substring(0, colon);
      String body = field.substring(colon + 1).strip();
      if (label.matches("[\\p{L}\\p{N} _-]+")
          && !ASSIGNMENT.matcher(label).find()
          && !NON_NEUTRAL_LABEL.matcher(label).find()
          && ASSIGNMENT.matcher(body).find()) {
        return body;
      }
    }
    return field;
  }

  private static void splitAssignments(List<Field> fields, String text, int start, int end) {
    String clause = text.substring(start, end);
    var connectors = CONNECTOR.matcher(clause);
    int cursor = 0;
    while (connectors.find()) {
      if (ASSIGNMENT.matcher(clause.substring(cursor, connectors.start())).find()
          && NEXT_ASSIGNMENT.matcher(clause.substring(connectors.end())).find()) {
        add(fields, text, start + cursor, start + connectors.start());
        cursor = connectors.end();
      }
    }
    add(fields, text, start + cursor, end);
  }

  private static void add(List<Field> output, String source, int start, int end) {
    while (start < end && Character.isWhitespace(source.charAt(start))) {
      start++;
    }
    while (end > start && Character.isWhitespace(source.charAt(end - 1))) {
      end--;
    }
    if (start < end) {
      output.add(new Field(start, end, source.substring(start, end)));
    }
  }

  record Field(int start, int end, String text) {}
}
