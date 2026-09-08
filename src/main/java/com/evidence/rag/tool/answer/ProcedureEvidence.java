package com.evidence.rag.tool.answer;

import com.evidence.rag.model.domain.GroundedQuote;
import com.evidence.rag.model.domain.PublishedEvidence;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;

/** Same-page procedure requirements and quote coverage; page context never supplies support. */
final class ProcedureEvidence {
  private static final Pattern ORDINAL =
      pattern("^(?:(?:步骤|step\\s*)?\\d{1,2}[.)、）:：]|[（(]\\d{1,2}[)）])\\s*");
  private static final Pattern ORDINAL_ONLY = pattern("^\\d{1,2}$");
  private static final Pattern TRANSITION =
      pattern("^(?:(?:然后|接着|随后|最后|再|先|首先|其次|之后)\\s*|(?:then|next|finally|first|afterwards)\\s+)");
  private static final Pattern PROHIBITION =
      pattern("^(?:(?:不要|不得|请勿|勿)\\s*|(?:do\\s+not|don't|never)\\s+)");
  private static final Pattern STEP_ACTION =
      pattern(
          "^(?:长按|按|点击|选择|进入|打开|关闭|输入|执行|连接|断开|等待|松开|重启|press\\b|click\\b|select\\b|enter\\b|open\\b|close\\b|type\\b|connect\\b|disconnect\\b|wait\\b|release\\b|restart\\b)");
  private static final Pattern COMPLETION =
      pattern(
          "^(?:直到|直至|待|当|只有|仅当|若|如果|等到|until\\b|once\\b|when\\b|only\\s+(?:when|after)\\b|if\\b|after\\b)");
  private static final Pattern NAMED_OPERATION =
      pattern(
          "^(?:清理|备份|校准|恢复|重启|关闭|打开|连接|断开|删除|导出|reset\\b|restart\\b|back\\s+up\\b|backup\\b|clear\\b|clean\\b|calibrate\\b|open\\b|close\\b)[^:：]{0,100}[:：]");
  private static final Pattern INDEPENDENT_FACT =
      pattern("^[\\p{L}\\p{N}][^:：=]{1,120}?(?:是|为|\\s+(?:is|are|was|were)\\s+).+");
  private static final Pattern REFERENCE_SUBJECT =
      pattern("^(?:这|该|此|上述|本操作|本流程|this\\b|that\\b|the\\s+(?:procedure|process|operation)\\b)");
  private static final Pattern CHINESE_PREREQUISITE =
      pattern("^.{1,120}?(?:是|为)(.{1,120}?)的(?:前提|必要条件|先决条件)$");
  private static final Pattern ENGLISH_PREREQUISITE =
      pattern("^.{1,120}?\\s+is\\s+(?:required|necessary)\\s+(?:to|for)\\s+(.{1,120})$");
  private static final Pattern PREREQUISITE_MARKER = pattern("\\b(?:required|necessary)\\b");

  private ProcedureEvidence() {}

  static List<Group> groups(
      QuestionFacts.ProcedureFact fact, String page, List<SourceFields.Field> sentences) {
    var result = new ArrayList<Group>();
    for (int index = 0; index < sentences.size(); index++) {
      interrupted();
      var first = sentences.get(index);
      String body = fact.body(first.text());
      if (body == null) {
        continue;
      }
      var steps = new ArrayList<SourceFields.Field>();
      boolean recognized = fact.value(first.text()) != null;
      int next = index + 1;
      // A numbered list may put the first period after "1", before the actual first action.
      if (!recognized
          && (body.isBlank() || ORDINAL_ONLY.matcher(body).matches())
          && next < sentences.size()
          && step(sentences.get(next).text())) {
        first = range(page, first.start(), sentences.get(next++).end());
        recognized = fact.value(first.text()) != null;
      }
      steps.add(first);
      while (recognized && next < sentences.size()) {
        interrupted();
        var following = sentences.get(next);
        String prerequisite = prerequisiteOperation(following.text());
        if (fact.body(following.text()) != null
            || (prerequisite != null
                ? !prerequisite.equals(normalizeOperation(fact.operation()))
                : independent(fact, following.text()))) {
          break;
        }
        if (ORDINAL_ONLY.matcher(following.text()).matches()
            && next + 1 < sentences.size()
            && step(sentences.get(next + 1).text())) {
          following = range(page, following.start(), sentences.get(++next).end());
        }
        steps.add(following);
        next++;
        if (!step(following.text()) && prerequisite == null) {
          recognized = false;
        }
      }
      result.add(
          new Group(
              List.copyOf(steps), range(page, first.start(), steps.getLast().end()), recognized));
      index = Math.max(index, next - 1);
    }
    return List.copyOf(result);
  }

  static boolean unsafe(String page, Group group, List<SourceFields.Field> fields) {
    var first = group.steps().getFirst();
    // Quoted continuation conditions belong to the requested procedure, not external permission.
    var external =
        fields.stream()
            .filter(field -> field.start() < first.end() || field.start() >= group.range().end())
            .toList();
    if (TruthContext.unsafe(page, first, external)
        || SourceInstructions.unsafe(page, group.range(), fields)) {
      return true;
    }
    // Each step retains its intrinsic truth markers without restarting page/fence scans.
    for (var step : group.steps()) {
      interrupted();
      if (TruthContext.selfRefuted(step.text())
          || (step.end() < page.length() && "？?".indexOf(page.charAt(step.end())) >= 0)) {
        return true;
      }
    }
    return false;
  }

  static Proof prove(QuestionFacts.ProcedureFact fact, List<QuoteRange> quotes) {
    var visited = new HashSet<PageIdentity>();
    for (var quote : quotes) {
      interrupted();
      var identity = identity(quote.source());
      if (!visited.add(identity)) {
        continue;
      }
      String page = quote.source().page().text();
      var samePage =
          quotes.stream().filter(value -> identity(value.source()).equals(identity)).toList();
      var fields = SourceFields.split(page);
      for (var group : groups(fact, page, SourceFields.sentences(page))) {
        interrupted();
        if (samePage.stream().noneMatch(value -> value.contains(group.steps().getFirst()))) {
          continue;
        }
        if (unsafe(page, group, fields)) {
          return new Proof("unsafe_evidence", List.of());
        }
        if (!group.recognized()) {
          return new Proof("incomplete_evidence", List.of());
        }
        var fragments = cover(fact, group, samePage);
        if (!fragments.isEmpty()) {
          return new Proof(null, fragments);
        }
      }
    }
    return new Proof("incomplete_evidence", List.of());
  }

  private static List<GroundedQuote> cover(
      QuestionFacts.ProcedureFact fact, Group group, List<QuoteRange> quotes) {
    var fragments = new ArrayList<GroundedQuote>();
    int index = 0;
    while (index < group.steps().size()) {
      interrupted();
      var first = group.steps().get(index);
      QuoteRange selected = null;
      int last = index;
      for (var quote : quotes) {
        if (!quote.contains(first)
            || !bounded(quote.source().page().text(), first.start(), first.end())) {
          continue;
        }
        int end = index;
        while (end + 1 < group.steps().size()
            && quote.contains(group.steps().get(end + 1))
            && bounded(
                quote.source().page().text(), first.start(), group.steps().get(end + 1).end())) {
          interrupted();
          end++;
        }
        if (selected == null || end > last) {
          selected = quote;
          last = end;
        }
      }
      if (selected == null) {
        return List.of();
      }
      String page = selected.source().page().text();
      int end = group.steps().get(last).end();
      fragments.add(
          new GroundedQuote(
              selected.source().physicalSegmentId(),
              page.codePointCount(0, first.start()),
              page.codePointCount(0, end),
              page.substring(first.start(), end),
              List.of(fact.sha256())));
      if (fragments.size() > 32) {
        return List.of();
      }
      index = last + 1;
    }
    return List.copyOf(fragments);
  }

  private static boolean step(String text) {
    String body = ORDINAL.matcher(text).replaceFirst("").stripLeading();
    body = TRANSITION.matcher(body).replaceFirst("");
    body = PROHIBITION.matcher(body).replaceFirst("");
    return STEP_ACTION.matcher(body).find() || COMPLETION.matcher(body).find();
  }

  private static boolean independent(QuestionFacts.ProcedureFact fact, String text) {
    if (NAMED_OPERATION.matcher(text).find()) {
      return true;
    }
    // Exact prerequisite targets are handled before this fallback. Unknown forms are not facts
    // that may be discarded: leave them in the group as an unrecognized required continuation.
    if (PREREQUISITE_MARKER.matcher(text).find()) {
      return false;
    }
    return !step(text)
        && !REFERENCE_SUBJECT.matcher(text).find()
        && !text.toLowerCase(Locale.ROOT).startsWith(fact.operation().toLowerCase(Locale.ROOT))
        && INDEPENDENT_FACT.matcher(text).matches();
  }

  private static String prerequisiteOperation(String text) {
    for (var pattern : List.of(CHINESE_PREREQUISITE, ENGLISH_PREREQUISITE)) {
      var match = pattern.matcher(text);
      if (match.matches()) {
        return normalizeOperation(match.group(1));
      }
    }
    return null;
  }

  private static String normalizeOperation(String text) {
    return text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }

  private static boolean bounded(String page, int start, int end) {
    return end - start <= 2400 && page.codePointCount(start, end) <= 1200;
  }

  private static SourceFields.Field range(String page, int start, int end) {
    return new SourceFields.Field(start, end, page.substring(start, end));
  }

  private static PageIdentity identity(PublishedEvidence source) {
    return new PageIdentity(source.publication().publicationId(), source.page().number());
  }

  private static Pattern pattern(String value) {
    return Pattern.compile(value, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  }

  private static void interrupted() {
    if (Thread.currentThread().isInterrupted()) {
      throw new CancellationException("Text grounding interrupted");
    }
  }

  record QuoteRange(PublishedEvidence source, int start, int end) {
    boolean contains(SourceFields.Field field) {
      return field.start() >= start && field.end() <= end;
    }

    @Override
    public String toString() {
      return "QuoteRange[redacted]";
    }
  }

  record Group(List<SourceFields.Field> steps, SourceFields.Field range, boolean recognized) {
    Group {
      steps = List.copyOf(steps);
    }

    @Override
    public String toString() {
      return "ProcedureGroup[redacted]";
    }
  }

  record Proof(String reason, List<GroundedQuote> quotes) {
    Proof {
      quotes = List.copyOf(quotes);
    }

    @Override
    public String toString() {
      return "ProcedureProof[redacted]";
    }
  }

  private record PageIdentity(String publicationId, int pageNumber) {}
}
