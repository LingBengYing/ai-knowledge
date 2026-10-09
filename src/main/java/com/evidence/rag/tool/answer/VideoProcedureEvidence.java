package com.evidence.rag.tool.answer;

import com.evidence.rag.model.domain.GroundedQuote;
import com.evidence.rag.model.domain.GroundingResult;
import com.evidence.rag.model.domain.KnowledgeEvidence;
import com.evidence.rag.model.domain.KnowledgeGroundingResult;
import com.evidence.rag.model.domain.ProductHelpEvidence;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Product title and complete spoken instructions remain separate, same-video original quotes. */
final class VideoProcedureEvidence {
  private static final Pattern ACTION =
      Pattern.compile("^(?:长按|短按|按|点击|选择|进入|打开|关闭|输入|连接|等待|松开|重启|执行).+");

  private VideoProcedureEvidence() {}

  static KnowledgeGroundingResult prove(
      QuestionFacts.NaturalProcedureFact fact,
      List<KnowledgeEvidence> sources,
      List<ProcedureEvidence.QuoteRange> quotes) {
    var proved = new LinkedHashSet<GroundedQuote>();
    var dependencies = new ArrayList<KnowledgeGroundingResult.Dependency>();
    String observed = null;
    for (var spoken : sources) {
      if (spoken.source().kind() != ProductHelpEvidence.Kind.VIDEO_TRANSCRIPT) {
        continue;
      }
      String transcript = spoken.context().contextText();
      // This normal path proves one complete short spoken procedure, never a clipped chunk.
      if (!spoken.context().snippet().equals(transcript)
          || transcript.codePointCount(0, transcript.length()) > 1200) {
        continue;
      }
      var titles = new ArrayList<Title>();
      for (var image : sources) {
        if (image.source().kind() == ProductHelpEvidence.Kind.VIDEO_FRAME_OCR
            && related(image, spoken)) {
          var title = title(fact.subject(), image);
          if (title != null) {
            titles.add(title);
          }
        }
      }
      if (titles.isEmpty()) {
        continue;
      }
      String action = canonical(fact.action());
      if (!canonical(transcript).contains(action)) {
        continue;
      }
      if (unsafe(transcript, 0, transcript.length()) || negates(transcript, fact.action())) {
        return refused("unsafe_evidence");
      }
      boolean foundAction = false;
      for (String clause : transcript.split("[，,。.!！;；\\r\\n]+")) {
        if (clause.isBlank()) {
          continue;
        }
        String text = clause.strip();
        if (ACTION.matcher(text).matches()) {
          foundAction |= canonical(text).contains(action);
        } else if (!text.matches(".{1,80}表示.{1,40}")) {
          return refused("incomplete_evidence");
        }
      }
      if (!foundAction) {
        continue;
      }
      String normalized = canonical(transcript).replaceAll("[，,。.!！;；]", "");
      if (observed != null && !observed.equals(normalized)) {
        return refused("conflicting_evidence");
      }
      observed = normalized;
      if (!quoted(spoken, 0, transcript.length(), quotes)) {
        continue;
      }
      for (var title : titles) {
        if (unsafe(title.source().context().contextText(), title.start(), title.end())) {
          return refused("unsafe_evidence");
        }
        if (!quoted(title.source(), title.start(), title.end(), quotes)) {
          continue;
        }
        var identity = fragment(title.source(), title.start(), title.end(), fact.sha256());
        var operation = fragment(spoken, 0, transcript.length(), fact.sha256());
        proved.add(identity);
        proved.add(operation);
        dependencies.add(new KnowledgeGroundingResult.Dependency(operation, identity));
        break;
      }
    }
    return proved.isEmpty()
        ? refused("incomplete_evidence")
        : new KnowledgeGroundingResult(
            new GroundingResult(true, "supported", List.copyOf(proved)), dependencies);
  }

  private static boolean related(KnowledgeEvidence image, KnowledgeEvidence spoken) {
    var left = image.source();
    var right = spoken.source();
    return left.publication().equals(right.publication())
        && left.startUs() < right.endUs()
        && right.startUs() < left.endUs();
  }

  private static Title title(String product, KnowledgeEvidence source) {
    String page = source.context().contextText();
    var expected =
        Pattern.compile(
            "^(?:适用产品[:：])?"
                + Pattern.quote(canonical(product))
                + "(?:[·./|—-]?[\\p{IsHan}]{1,16}(?:机|器|设备|装置|终端))?[。.]?$");
    Title match = null;
    int start = 0;
    for (String line : page.split("\\n", -1)) {
      int left = 0;
      while (left < line.length() && Character.isWhitespace(line.charAt(left))) {
        left++;
      }
      int end = line.length();
      while (end > left && Character.isWhitespace(line.charAt(end - 1))) {
        end--;
      }
      String heading = line.substring(left, end).replaceFirst("^[一—–-]\\s+", "");
      if (expected.matcher(canonical(heading)).matches()) {
        if (match != null) {
          return null;
        }
        match = new Title(source, start + left, start + end);
      }
      start += line.length() + 1;
    }
    return match;
  }

  private static boolean quoted(
      KnowledgeEvidence source, int start, int end, List<ProcedureEvidence.QuoteRange> quotes) {
    String text = source.context().contextText();
    int requiredEnd = end;
    if (end > start && "。.".indexOf(text.charAt(end - 1)) >= 0) {
      requiredEnd--;
    }
    int candidateStart = text.offsetByCodePoints(0, source.context().startCodePoint());
    int candidateEnd = text.offsetByCodePoints(0, source.context().endCodePoint());
    if (start < candidateStart || end > candidateEnd) {
      return false;
    }
    for (var quote : quotes) {
      if (quote.source().physicalId().equals(source.context().physicalId())
          && quote.start() <= start
          && quote.end() >= requiredEnd) {
        return true;
      }
    }
    return false;
  }

  private static boolean unsafe(String page, int start, int end) {
    var fields = SourceFields.split(page);
    var original = new SourceFields.Field(start, end, page.substring(start, end));
    return TruthContext.unsafe(page, original, fields)
        || SourceInstructions.unsafe(page, original, fields);
  }

  private static boolean negates(String text, String action) {
    return Pattern.compile(
            "(?:不(?:能|会|要|可|得|是)?|没(?:有)?|无法|尚未|未|禁止|请勿|勿)" + Pattern.quote(canonical(action)))
        .matcher(canonical(text))
        .find();
  }

  private static String canonical(String text) {
    return text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
  }

  private static GroundedQuote fragment(KnowledgeEvidence source, int start, int end, String fact) {
    String text = source.context().contextText();
    return new GroundedQuote(
        source.context().physicalId(),
        text.codePointCount(0, start),
        text.codePointCount(0, end),
        text.substring(start, end),
        List.of(fact));
  }

  private static KnowledgeGroundingResult refused(String reason) {
    return new KnowledgeGroundingResult(new GroundingResult(false, reason, List.of()), List.of());
  }

  private record Title(KnowledgeEvidence source, int start, int end) {}
}
