package com.evidence.rag.tool.answer;

import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;

/** Exact source ranges are retained separately from any question-matching normalization. */
final class SourceFields {
  private static final Pattern LAYOUT_LABEL =
      Pattern.compile(
          "^(?:项目名称|项目名|名称|计划预算|预算|计划启动日期|启动日期|project name|name|planned budget|budget|planned launch date|launch date)$",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern PROJECT_NAME =
      Pattern.compile("^(?:项目名称|项目名)\\s*(?:是|为|[:：=])\\s*(.+)$");
  private static final Pattern EXPLICIT_PROJECT_FIELD =
      Pattern.compile("^(.+?项目)(?:的|[:：])?(?:名称|计划预算|预算|计划启动日期|启动日期)(?:是|为|[:：=]).+$");
  private static final Pattern PROJECT_HEADING =
      Pattern.compile("^(?:[\\p{L}\\p{N} _-]{1,24}[:：])?([^:：\\r\\n]{1,100}项目)$");
  private static final Pattern BOUNDARY =
      Pattern.compile("[。！？!?；;\\r\\n]+|\\.(?=\\s+[A-Z\\p{IsHan}]|\\s*$)|[,，]");
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
    var original = ranges(text, true);
    var fields = new ArrayList<Field>();
    for (int index = 0; index < original.size(); index++) {
      checkInterrupted();
      var label = original.get(index);
      if (LAYOUT_LABEL.matcher(label.text()).matches() && index + 1 < original.size()) {
        var value = original.get(index + 1);
        String between = text.substring(label.end(), value.start());
        if (between.matches("[ \t]*\r?\n[ \t]*")
            && !LAYOUT_LABEL.matcher(value.text()).matches()
            && !ASSIGNMENT.matcher(value.text()).find()
            && value.text().codePointCount(0, value.text().length()) <= 512) {
          fields.add(new Field(label.start(), value.end(), text.substring(label.start(), value.end())));
          index++;
          continue;
        }
      }
      fields.add(label);
    }
    return List.copyOf(fields);
  }

  /** A layout field keeps its two original lines and their exact source range. */
  static String layoutValue(String field, String key) {
    int newline = field.indexOf('\n');
    if (newline < 0 || !field.substring(0, newline).strip().equalsIgnoreCase(key)) {
      return null;
    }
    String label = field.substring(0, newline).strip();
    String value = field.substring(newline + 1).strip();
    return LAYOUT_LABEL.matcher(label).matches() && !value.isEmpty() ? value : null;
  }

  /** One explicit, safe project identity must occur inside the same retrieved source fragment. */
  static Field projectBinding(
      String page, List<Field> fields, String subject, int fragmentStart, int fragmentEnd) {
    String requested = projectIdentity(subject);
    Field binding = null;
    for (var field : fields) {
      checkInterrupted();
      String statement = statement(field.text());
      String declared = layoutValue(statement, "项目名称");
      if (declared == null) {
        declared = layoutValue(statement, "项目名");
      }
      var assignment = PROJECT_NAME.matcher(statement);
      if (declared == null && assignment.matches()) {
        declared = assignment.group(1);
      }
      if (declared != null) {
        if (!projectIdentity(declared).equals(requested)
            || TruthContext.unsafe(page, field, fields)) {
          return null;
        }
        if (field.start() >= fragmentStart && field.end() <= fragmentEnd && binding == null) {
          binding = field;
        }
      }
      // Check the original label before neutral-label stripping can hide another owner.
      var explicit = EXPLICIT_PROJECT_FIELD.matcher(field.text());
      var heading = PROJECT_HEADING.matcher(field.text());
      if ((explicit.matches() && !projectIdentity(explicit.group(1)).equals(requested))
          || (heading.matches() && !projectIdentity(heading.group(1)).equals(requested))) {
        return null;
      }
    }
    return binding;
  }

  private static String projectIdentity(String value) {
    String result = value.strip();
    if (result.length() >= 2
        && ((result.startsWith("‘") && result.endsWith("’"))
            || (result.startsWith("“") && result.endsWith("”"))
            || (result.startsWith("\"") && result.endsWith("\""))
            || (result.startsWith("'") && result.endsWith("'")))) {
      result = result.substring(1, result.length() - 1).strip();
    }
    return result.replaceFirst("项目$", "").toLowerCase(Locale.ROOT);
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
      if (clauses && numericComma(text, boundaries.start(), boundaries.end())) {
        continue;
      }
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

  private static boolean numericComma(String text, int start, int end) {
    if (end != start + 1 || ",，".indexOf(text.charAt(start)) < 0) {
      return false;
    }
    int left = start - 1;
    int right = end;
    while (left >= 0 && (text.charAt(left) == ' ' || text.charAt(left) == '\t')) {
      checkInterrupted();
      left--;
    }
    while (right < text.length() && (text.charAt(right) == ' ' || text.charAt(right) == '\t')) {
      checkInterrupted();
      right++;
    }
    // Numeric punctuation and horizontal space remain in the original range. Newlines and a
    // following named assignment are still boundaries; no source text or number is rewritten.
    return left >= 0
        && right < text.length()
        && Character.isDigit(text.codePointBefore(left + 1))
        && Character.isDigit(text.codePointAt(right));
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
