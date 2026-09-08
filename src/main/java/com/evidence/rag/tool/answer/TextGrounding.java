package com.evidence.rag.tool.answer;

import com.evidence.rag.model.domain.GroundedQuote;
import com.evidence.rag.model.domain.GroundingQuote;
import com.evidence.rag.model.domain.GroundingResult;
import com.evidence.rag.model.domain.PublishedEvidence;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;

/** Pure full-question proof and source-fragment validation; callers own current authorization. */
public final class TextGrounding {
  public static final String VERSION = "java-text-grounding-v3-page-conflicts";
  private static final int MAX_QUESTION_BYTES = 4096;
  private static final int MAX_CANDIDATES = 64;
  private static final int MAX_QUOTES = 32;
  private static final int MAX_PAGE_BYTES = 8 * 1024 * 1024;

  public GroundingResult verify(
      String question, List<PublishedEvidence> candidates, List<GroundingQuote> quotes) {
    if (question == null
        || question.length() > MAX_QUESTION_BYTES
        || question.getBytes(StandardCharsets.UTF_8).length > MAX_QUESTION_BYTES
        || !validUnicode(question)) {
      return refused("unsupported_question");
    }
    var facts = QuestionFacts.parse(question);
    if (facts.isEmpty()) {
      return refused("unsupported_question");
    }
    if (candidates == null
        || candidates.size() > MAX_CANDIDATES
        || quotes == null
        || quotes.isEmpty()
        || quotes.size() > MAX_QUOTES) {
      return refused("invalid_quote");
    }
    var byId = new HashMap<String, PublishedEvidence>();
    var pageHashes = new HashMap<PageIdentity, String>();
    long pageBytes = 0;
    for (var candidate : candidates) {
      if (candidate == null || byId.putIfAbsent(candidate.physicalSegmentId(), candidate) != null) {
        return refused("invalid_quote");
      }
      var pageIdentity =
          new PageIdentity(candidate.publication().publicationId(), candidate.page().number());
      String previousHash = pageHashes.putIfAbsent(pageIdentity, candidate.pageSha256());
      if (previousHash != null && !previousHash.equals(candidate.pageSha256())) {
        return refused("invalid_quote");
      }
      if (previousHash == null) {
        String page = candidate.page().text();
        if (page.length() > MAX_PAGE_BYTES || !validUnicode(page)) {
          return refused("invalid_quote");
        }
        pageBytes += page.getBytes(StandardCharsets.UTF_8).length;
        if (pageBytes > MAX_PAGE_BYTES) {
          return refused("invalid_quote");
        }
      }
    }
    if (EvidenceConflicts.present(facts, candidates)) {
      return refused("conflicting_evidence");
    }
    var verified = new LinkedHashSet<GroundedQuote>();
    var seenQuotes = new HashSet<GroundingQuote>();
    for (var quote : quotes) {
      if (quote == null
          || quote.quote() == null
          || quote.quote().length() > 2400
          || quote.quote().isBlank()
          || !validUnicode(quote.quote())
          || !seenQuotes.add(quote)) {
        return refused("invalid_quote");
      }
      var source = byId.get(quote.physicalId());
      if (source == null) {
        return refused("invalid_quote");
      }
      String chunk = source.segment().text();
      int occurrence = chunk.indexOf(quote.quote());
      if (occurrence < 0 || chunk.indexOf(quote.quote(), occurrence + 1) >= 0) {
        return refused("invalid_quote");
      }
      String page = source.page().text();
      int absoluteStart = page.offsetByCodePoints(0, source.segment().start()) + occurrence;
      int absoluteEnd = absoluteStart + quote.quote().length();
      var fields = SourceFields.split(page);
      var ranges = new ArrayList<ProofRange>();
      fields.forEach(field -> ranges.add(new ProofRange(field, false)));
      SourceFields.sentences(page).forEach(field -> ranges.add(new ProofRange(field, true)));
      for (var range : ranges) {
        var field = range.field();
        if (field.start() < absoluteStart || field.end() > absoluteEnd) {
          continue;
        }
        var supporting =
            facts.stream()
                .filter(
                    fact ->
                        fact.wholeSentence() == range.wholeSentence() && fact.matches(field.text()))
                .toList();
        if (supporting.isEmpty()) {
          continue;
        }
        if (TruthContext.unsafe(page, field, fields)) {
          return refused("unsafe_evidence");
        }
        verified.add(
            new GroundedQuote(
                quote.physicalId(),
                page.codePointCount(0, field.start()),
                page.codePointCount(0, field.end()),
                field.text(),
                supporting.stream().map(QuestionFacts.Fact::sha256).toList()));
      }
    }
    if (facts.stream()
        .anyMatch(
            fact ->
                verified.stream().noneMatch(value -> value.factHashes().contains(fact.sha256())))) {
      return refused("incomplete_evidence");
    }
    return new GroundingResult(true, "supported", List.copyOf(verified));
  }

  private static boolean validUnicode(String text) {
    return text.codePoints().noneMatch(point -> point == 0 || (point >= 0xD800 && point <= 0xDFFF));
  }

  private static GroundingResult refused(String reason) {
    return new GroundingResult(false, reason, List.of());
  }

  private record ProofRange(SourceFields.Field field, boolean wholeSentence) {}

  private record PageIdentity(String publicationId, int pageNumber) {}
}
