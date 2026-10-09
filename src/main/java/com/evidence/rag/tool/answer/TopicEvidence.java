package com.evidence.rag.tool.answer;

import com.evidence.rag.model.domain.GroundedQuote;
import com.evidence.rag.model.domain.GroundingQuote;
import com.evidence.rag.model.domain.GroundingResult;
import com.evidence.rag.model.domain.GroundingText;
import com.evidence.rag.model.domain.ModelValues;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Original-source admission for bare topics; synthesis and verification still own factual claims.
 */
public final class TopicEvidence {
  public static final String VERSION = "java-topic-evidence-v1";
  private static final int MAX_TOPIC_CODE_POINTS = 80;
  private static final int MAX_CANDIDATES = 64;
  private static final int MAX_QUOTES = 32;
  private static final Pattern BARE_TOPIC =
      Pattern.compile("[\\p{L}\\p{N}]+(?:[ _-]+[\\p{L}\\p{N}]+)*");
  private static final Pattern FACT_OR_QUESTION =
      Pattern.compile(
          "\\b(?:what|who|whose|which|where|when|why|how|whether|is|are|was|were|do|does|did|can|could|may|might|will|would|should|must|please|tell|explain|describe|list|show|find|and|or|versus|vs|if|unless|only|provided|before|after|not|no|never|without|than|more|less|count|number|many|much|all|each|every|both|budget|amount|cost|price|date|time|deadline|name|title|status|code|identifier|permission|permissions|access|authorized|authorization|steps|step|procedure|enable|disable|export|usd|cny|rmb)\\b"
              + "|什么|谁|哪|何时|为何|为什么|如何|怎么|是否|能否|可否|多少|几[个次份年月日]|请|介绍|说明|列出|告诉|分别|以及|还有|并且|同时|然后|和|或|与|如果|若|仅|只|除非|否则|之前|之后|不|未|无|非|禁止|大于|小于|比较|对比|区别|更|最|预算|金额|费用|成本|价格|日期|时间|期限|名称|名字|状态|识别码|编号|权限|授权|步骤|操作|使用|开启|关闭|导出|元",
          Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

  /** Selection happens once before grounding, never as a fallback for a failed factual question. */
  public static boolean accepts(String question) {
    if (question == null
        || question.length() > MAX_TOPIC_CODE_POINTS * 2
        || !validUnicode(question)) {
      return false;
    }
    String topic = question.strip();
    return !topic.isEmpty()
        && topic.codePointCount(0, topic.length()) <= MAX_TOPIC_CODE_POINTS
        && topic.split(" +").length <= 8
        && topic.codePoints().anyMatch(Character::isLetter)
        && BARE_TOPIC.matcher(topic).matches()
        && !FACT_OR_QUESTION.matcher(topic).find();
  }

  /**
   * Admits exact complete original excerpts relevant to a topic, not arbitrary model assertions.
   * Callers must still synthesize and independently verify against the full authorized context.
   */
  public GroundingResult locate(
      String question, List<GroundingText> candidates, List<GroundingQuote> quotes) {
    if (!accepts(question)) {
      return refused("unsupported_question");
    }
    if (candidates == null
        || candidates.isEmpty()
        || candidates.size() > MAX_CANDIDATES
        || quotes == null
        || quotes.isEmpty()
        || quotes.size() > MAX_QUOTES) {
      return refused("invalid_quote");
    }
    var byId = new HashMap<String, GroundingText>();
    var contextHashes = new HashMap<String, String>();
    long contextBytes = 0;
    for (var candidate : candidates) {
      if (candidate == null || byId.putIfAbsent(candidate.physicalId(), candidate) != null) {
        return refused("invalid_quote");
      }
      String previous = contextHashes.putIfAbsent(candidate.contextId(), candidate.contextSha256());
      if (previous != null && !previous.equals(candidate.contextSha256())) {
        return refused("invalid_quote");
      }
      if (previous == null) {
        contextBytes += candidate.contextText().getBytes(StandardCharsets.UTF_8).length;
        if (contextBytes > GroundingText.MAX_CONTEXT_BYTES) {
          return refused("invalid_quote");
        }
      }
    }
    String topic = question.strip().replaceAll(" +", " ");
    String topicHash =
        ModelValues.sha256(
            (VERSION + "\n" + topic.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
    Pattern anchor = anchor(topic);
    var seenQuotes = new HashSet<GroundingQuote>();
    var seenRanges = new HashSet<OriginalRange>();
    var admitted = new ArrayList<GroundedQuote>();
    for (var quote : quotes) {
      if (quote == null
          || quote.quote() == null
          || quote.quote().isBlank()
          || quote.quote().length() > 2400
          || quote.quote().codePointCount(0, quote.quote().length()) > 1200
          || !validUnicode(quote.quote())
          || !seenQuotes.add(quote)) {
        return refused("invalid_quote");
      }
      var source = byId.get(quote.physicalId());
      if (source == null) {
        return refused("invalid_quote");
      }
      String snippet = source.snippet();
      int occurrence = snippet.indexOf(quote.quote());
      if (occurrence < 0 || snippet.indexOf(quote.quote(), occurrence + 1) >= 0) {
        return refused("invalid_quote");
      }
      String page = source.contextText();
      int start = page.offsetByCodePoints(0, source.startCodePoint()) + occurrence;
      int end = start + quote.quote().length();
      if (!seenRanges.add(new OriginalRange(source.contextId(), start, end))
          || !completeSentences(page, start, end)) {
        return refused("invalid_quote");
      }
      if (!anchor.matcher(quote.quote()).find()) {
        return refused("incomplete_evidence");
      }
      var fields = SourceFields.split(page);
      boolean containsField = false;
      for (var field : fields) {
        if (field.end() <= start || field.start() >= end) {
          continue;
        }
        // Layout label/value fields may span lines; a label alone is not the complete field.
        if (field.start() < start || field.end() > end) {
          return refused("invalid_quote");
        }
        containsField = true;
        if (TruthContext.unsafe(page, field, fields)) {
          return refused("unsafe_evidence");
        }
      }
      if (!containsField) {
        return refused("invalid_quote");
      }
      admitted.add(
          new GroundedQuote(
              source.physicalId(),
              page.codePointCount(0, start),
              page.codePointCount(0, end),
              quote.quote(),
              List.of(topicHash)));
    }
    return new GroundingResult(true, "supported", admitted);
  }

  private static boolean completeSentences(String page, int start, int end) {
    boolean covered = false;
    for (var sentence : SourceFields.sentences(page)) {
      if (sentence.end() <= start || sentence.start() >= end) {
        continue;
      }
      // A comma is not a boundary here: a quoted assertion cannot discard its trailing condition.
      if (sentence.start() < start || sentence.end() > end) {
        return false;
      }
      covered = true;
    }
    return covered;
  }

  private static Pattern anchor(String topic) {
    String phrase =
        String.join(
            "[ \\t]+", java.util.Arrays.stream(topic.split(" ")).map(Pattern::quote).toList());
    String tokenCharacter = "[\\p{IsLatin}\\p{N}_-]";
    String before = tokenEdge(topic.codePointAt(0)) ? "(?<!" + tokenCharacter + ")" : "";
    String after =
        tokenEdge(topic.codePointBefore(topic.length())) ? "(?!" + tokenCharacter + ")" : "";
    return Pattern.compile(
        before + phrase + after, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  }

  private static boolean tokenEdge(int point) {
    return Character.UnicodeScript.of(point) == Character.UnicodeScript.LATIN
        || Character.isDigit(point);
  }

  private static boolean validUnicode(String text) {
    return text.codePoints().noneMatch(point -> point == 0 || (point >= 0xD800 && point <= 0xDFFF));
  }

  private static GroundingResult refused(String reason) {
    return new GroundingResult(false, reason, List.of());
  }

  private record OriginalRange(String contextId, int start, int end) {}
}
