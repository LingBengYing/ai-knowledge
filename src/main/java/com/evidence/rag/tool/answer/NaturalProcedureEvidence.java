package com.evidence.rag.tool.answer;

import com.evidence.rag.model.domain.GroundedQuote;
import com.evidence.rag.model.domain.GroundingText;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Numbered instructions with a product declaration in the same original source. */
final class NaturalProcedureEvidence {
  private static final Pattern PRODUCT = Pattern.compile("^适用产品\\s*[:：]\\s*(.+?)[。.]?$");
  private static final Pattern NUMBERED = Pattern.compile("^(?:步骤\\s*)?\\d{1,2}[.)、．。]?\\s+(.+)$");
  private static final Pattern ACTION =
      Pattern.compile("^(?:长按|短按|按|点击|选择|进入|打开|关闭|输入|连接|等待|松开|重启|执行).+");
  private static final Pattern CONFIRM = Pattern.compile("^(?:确认|检查|验证).{0,40}$");

  private NaturalProcedureEvidence() {}

  static ProcedureEvidence.Proof prove(
      QuestionFacts.NaturalProcedureFact fact,
      List<GroundingText> sources,
      List<ProcedureEvidence.QuoteRange> quotes) {
    var visited = new HashSet<String>();
    String observed = null;
    List<GroundedQuote> proof = List.of();
    for (var source : sources) {
      if (!visited.add(source.contextId())) {
        continue;
      }
      String page = source.contextText();
      var lines = lines(page);
      var binding = binding(fact.subject(), lines);
      if (binding == null) {
        continue;
      }
      var fields = SourceFields.split(page);
      if (unsafe(page, binding, fields)) {
        return new ProcedureEvidence.Proof("unsafe_evidence", List.of());
      }
      int firstHeading = -1;
      for (int index = 0; index < lines.size(); index++) {
        if (lines.get(index).start() > binding.start()
            && NUMBERED.matcher(lines.get(index).text()).matches()) {
          firstHeading = index;
          break;
        }
      }
      if (firstHeading < 0) {
        continue;
      }
      for (int index = firstHeading; index < lines.size(); index++) {
        var heading = NUMBERED.matcher(lines.get(index).text());
        if (!heading.matches() || !canonical(heading.group(1)).equals(canonical(fact.action()))) {
          continue;
        }
        int nextHeading = nextHeading(lines, index + 1);
        Line action = action(lines, index + 1, nextHeading);
        if (action == null || !canonical(action.text()).contains(canonical(fact.action()))) {
          continue;
        }
        if (negates(action.text(), fact.action())) {
          return new ProcedureEvidence.Proof("unsafe_evidence", List.of());
        }
        int last = action.end();
        if (nextHeading < lines.size()) {
          var next = NUMBERED.matcher(lines.get(nextHeading).text());
          if (next.matches() && CONFIRM.matcher(next.group(1)).matches()) {
            int followingHeading = nextHeading(lines, nextHeading + 1);
            Line confirmation = confirmation(lines, nextHeading + 1, followingHeading);
            if (confirmation == null) {
              return new ProcedureEvidence.Proof("incomplete_evidence", List.of());
            }
            last = confirmation.end();
          }
        }
        // The entire contiguous numbered sequence before the requested step is retained.
        int start = lines.get(firstHeading).start();
        if (last <= start || page.codePointCount(start, last) > 1200) {
          return new ProcedureEvidence.Proof("incomplete_evidence", List.of());
        }
        for (var line : lines) {
          if (line.start() >= start && line.end() <= last && unsafe(page, line, fields)) {
            return new ProcedureEvidence.Proof("unsafe_evidence", List.of());
          }
        }
        String procedure = page.substring(start, last);
        String previous = observed;
        observed = canonical(procedure);
        if (previous != null && !previous.equals(observed)) {
          return new ProcedureEvidence.Proof("conflicting_evidence", List.of());
        }
        for (var quote : quotes) {
          int actionEnd =
              action.text().endsWith("。") || action.text().endsWith(".")
                  ? action.end() - 1
                  : action.end();
          if (!quote.source().contextId().equals(source.contextId())
              || quote.start() > action.start()
              || quote.end() < actionEnd) {
            continue;
          }
          var candidate = quote.source();
          int candidateStart = page.offsetByCodePoints(0, candidate.startCodePoint());
          int candidateEnd = page.offsetByCodePoints(0, candidate.endCodePoint());
          if (candidateStart > binding.start() || candidateEnd < last) {
            continue;
          }
          String hash = fact.sha256();
          // Keep the declared product and all required steps inside one original citation.
          proof = List.of(fragment(candidate, page, binding.start(), last, hash));
          break;
        }
      }
    }
    return proof.isEmpty()
        ? new ProcedureEvidence.Proof("incomplete_evidence", List.of())
        : new ProcedureEvidence.Proof(null, proof);
  }

  private static GroundedQuote fragment(
      GroundingText candidate, String page, int start, int end, String factHash) {
    return new GroundedQuote(
        candidate.physicalId(),
        page.codePointCount(0, start),
        page.codePointCount(0, end),
        page.substring(start, end),
        List.of(factHash));
  }

  private static boolean unsafe(String page, Line line, List<SourceFields.Field> fields) {
    var field = new SourceFields.Field(line.start(), line.end(), line.text());
    return TruthContext.unsafe(page, field, fields)
        || SourceInstructions.unsafe(page, field, fields);
  }

  private static Line binding(String subject, List<Line> lines) {
    Line match = null;
    for (var line : lines) {
      var declaration = PRODUCT.matcher(line.text());
      if (!declaration.matches()) {
        continue;
      }
      String expected = canonical(subject);
      String actual = canonical(declaration.group(1));
      String suffix = actual.startsWith(expected) ? actual.substring(expected.length()) : "";
      boolean same =
          actual.equals(expected) || suffix.matches("[\\p{IsHan}]{1,16}(?:机|器|设备|装置|终端)");
      if (!same || match != null) {
        return null;
      }
      match = line;
    }
    return match;
  }

  private static Line action(List<Line> lines, int start, int end) {
    Line found = null;
    for (int index = start; index < end; index++) {
      if (!ACTION.matcher(lines.get(index).text()).matches() || found != null) {
        return null;
      }
      found = lines.get(index);
    }
    return found;
  }

  private static Line confirmation(List<Line> lines, int start, int end) {
    if (start >= end || !lines.get(start).text().contains("表示")) {
      return null;
    }
    for (int index = start + 1; index < end; index++) {
      // A trailing document note is context, not an extra step or an answer fact.
      if (!lines.get(index).text().startsWith("提示：")) {
        return null;
      }
    }
    return lines.get(start);
  }

  private static int nextHeading(List<Line> lines, int from) {
    for (int index = from; index < lines.size(); index++) {
      if (NUMBERED.matcher(lines.get(index).text()).matches()) {
        return index;
      }
    }
    return lines.size();
  }

  private static String canonical(String value) {
    return value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT).replaceFirst("[。.]$", "");
  }

  private static boolean negates(String statement, String requestedAction) {
    return Pattern.compile(
            "(?:不(?:能|会|要|可|得|是)?|没(?:有)?|无法|尚未|未|禁止|请勿|勿)"
                + Pattern.quote(canonical(requestedAction)))
        .matcher(canonical(statement))
        .find();
  }

  private static List<Line> lines(String page) {
    var result = new ArrayList<Line>();
    int start = 0;
    while (start < page.length()) {
      int end = page.indexOf('\n', start);
      if (end < 0) {
        end = page.length();
      }
      String raw = page.substring(start, end);
      int left = 0;
      while (left < raw.length() && Character.isWhitespace(raw.charAt(left))) {
        left++;
      }
      int right = raw.length();
      while (right > left && Character.isWhitespace(raw.charAt(right - 1))) {
        right--;
      }
      if (right > left) {
        result.add(new Line(start + left, start + right, raw.substring(left, right)));
      }
      start = end + 1;
    }
    return List.copyOf(result);
  }

  private record Line(int start, int end, String text) {}
}
