package com.evidence.rag.tool.answer;

import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
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
    checkInterrupted();
    var fields = new ArrayList<Field>();
    int start = 0;
    var boundaries = (clauses ? BOUNDARY : SENTENCE_BOUNDARY).matcher(text);
    while (boundaries.find()) {
      checkInterrupted();
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
    checkInterrupted();
    return List.copyOf(fields);
  }

  static boolean hasNestedAssignment(String field) {
    var assignments = ASSIGNMENT.matcher(statement(field));
    return assignments.find() && assignments.find();
  }

  static String statement(String field) {
    checkInterrupted();
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
    checkInterrupted();
    var connectors = CONNECTOR.matcher(text).region(start, end);
    var assignments = ASSIGNMENT.matcher(text).region(start, end);
    var nextAssignment = NEXT_ASSIGNMENT.matcher(text);
    boolean assignmentAvailable = assignments.find();
    int cursor = start;
    while (connectors.find()) {
      checkInterrupted();
      // An assignment already found in this clause never needs to be rescanned from the start.
      while (assignmentAvailable && assignments.start() < cursor) {
        checkInterrupted();
        assignmentAvailable = assignments.find();
      }
      boolean prefixHasAssignment =
          (assignmentAvailable && assignments.end() <= connectors.start())
              || hasBoundaryAssignment(text, cursor, connectors.start());
      if (prefixHasAssignment && nextAssignment.region(connectors.end(), end).find()) {
        add(fields, text, cursor, connectors.start());
        cursor = connectors.end();
      }
    }
    add(fields, text, cursor, end);
  }

  private static boolean hasBoundaryAssignment(String text, int start, int end) {
    // The old prefix substring made its edges word boundaries. A zero-copy view preserves those
    // edges, including their lookaround context, while inspecting only the longest token's span.
    var prefix = CharBuffer.wrap(text, start, end);
    var assignments = ASSIGNMENT.matcher(prefix);
    if (assignments.lookingAt()) {
      return true;
    }
    if (assignments.find(Math.max(0, prefix.length() - 3))) {
      do {
        if (assignments.end() == prefix.length()) {
          return true;
        }
      } while (assignments.find());
    }
    return false;
  }

  private static void add(List<Field> output, String source, int start, int end) {
    checkInterrupted();
    while (start < end && Character.isWhitespace(source.charAt(start))) {
      checkInterrupted();
      start++;
    }
    while (end > start && Character.isWhitespace(source.charAt(end - 1))) {
      checkInterrupted();
      end--;
    }
    if (start < end) {
      output.add(new Field(start, end, source.substring(start, end)));
    }
  }

  private static void checkInterrupted() {
    if (Thread.currentThread().isInterrupted()) {
      throw new CancellationException("Text grounding interrupted");
    }
  }

  record Field(int start, int end, String text) {}
}
