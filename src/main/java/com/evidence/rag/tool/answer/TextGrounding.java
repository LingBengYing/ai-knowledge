package com.evidence.rag.tool.answer;

import com.evidence.rag.model.domain.GroundedQuote;
import com.evidence.rag.model.domain.GroundingQuote;
import com.evidence.rag.model.domain.GroundingResult;
import com.evidence.rag.model.domain.GroundingText;
import com.evidence.rag.model.domain.PublishedEvidence;
import com.evidence.rag.model.domain.QuestionFact;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.function.Function;

/** Pure full-question proof and source-fragment validation; callers own current authorization. */
public final class TextGrounding {
  public static final String VERSION = "java-text-grounding-v8-launch-dates";
  private static final int MAX_QUESTION_BYTES = 4096;
  private static final int MAX_CANDIDATES = 64;
  private static final int MAX_QUOTES = 32;

  public GroundingResult verifyFact(
      String question,
      QuestionFact fact,
      List<GroundingText> candidates,
      List<GroundingQuote> quotes) {
    var plan = QuestionPlanning.plan(question);
    if (fact == null
        || plan.isEmpty()
        || fact.ordinal() >= plan.get().facts().size()
        || !plan.get().facts().get(fact.ordinal()).equals(fact)) {
      return refused("unsupported_question");
    }
    var result = verifyMapped(question, candidates, quotes, Function.identity(), fact.ordinal());
    if (!result.supported()) {
      return result;
    }
    return new GroundingResult(
        true,
        "supported",
        result.quotes().stream()
            .map(
                quote ->
                    new GroundedQuote(
                        quote.physicalId(),
                        quote.start(),
                        quote.end(),
                        quote.quote(),
                        List.of(fact.id())))
            .toList());
  }

  public GroundingResult verifyText(
      String question, List<GroundingText> candidates, List<GroundingQuote> quotes) {
    return verifyMapped(question, candidates, quotes, Function.identity());
  }

  public GroundingResult verify(
      String question, List<PublishedEvidence> candidates, List<GroundingQuote> quotes) {
    return verifyMapped(
        question,
        candidates,
        quotes,
        source ->
            new GroundingText(
                source.physicalSegmentId(),
                source.publication().publicationId() + "/page/" + source.page().number(),
                source.page().text(),
                source.pageSha256(),
                source.segment().start(),
                source.segment().end()));
  }

  private <T> GroundingResult verifyMapped(
      String question,
      List<T> candidates,
      List<GroundingQuote> quotes,
      Function<T, GroundingText> contextMapping) {
    return verifyMapped(question, candidates, quotes, contextMapping, null);
  }

  private <T> GroundingResult verifyMapped(
      String question,
      List<T> candidates,
      List<GroundingQuote> quotes,
      Function<T, GroundingText> contextMapping,
      Integer factOrdinal) {
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
    var requirements = factOrdinal == null ? facts : List.of(facts.get(factOrdinal));
    if (candidates == null
        || candidates.size() > MAX_CANDIDATES
        || quotes == null
        || quotes.isEmpty()
        || quotes.size() > MAX_QUOTES) {
      return refused("invalid_quote");
    }
    var byId = new HashMap<String, GroundingText>();
    var contextHashes = new HashMap<String, String>();
    var sources = new ArrayList<GroundingText>(candidates.size());
    long contextBytes = 0;
    for (var input : candidates) {
      if (input == null) {
        return refused("invalid_quote");
      }
      GroundingText candidate;
      try {
        candidate = contextMapping.apply(input);
      } catch (RuntimeException invalidContext) {
        return refused("invalid_quote");
      }
      if (byId.putIfAbsent(candidate.physicalId(), candidate) != null) {
        return refused("invalid_quote");
      }
      String previousHash =
          contextHashes.putIfAbsent(candidate.contextId(), candidate.contextSha256());
      if (previousHash != null && !previousHash.equals(candidate.contextSha256())) {
        return refused("invalid_quote");
      }
      if (previousHash == null) {
        contextBytes += candidate.contextText().getBytes(StandardCharsets.UTF_8).length;
        if (contextBytes > GroundingText.MAX_CONTEXT_BYTES) {
          return refused("invalid_quote");
        }
      }
      sources.add(candidate);
    }
    if (EvidenceConflicts.present(facts, sources)) {
      return refused("conflicting_evidence");
    }
    var verified = new LinkedHashSet<GroundedQuote>();
    var dependencies = new LinkedHashSet<GroundedQuote>();
    var seenQuotes = new HashSet<GroundingQuote>();
    var quotedRanges = new ArrayList<ProcedureEvidence.QuoteRange>();
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
      String chunk = source.snippet();
      int occurrence = chunk.indexOf(quote.quote());
      if (occurrence < 0 || chunk.indexOf(quote.quote(), occurrence + 1) >= 0) {
        return refused("invalid_quote");
      }
      String page = source.contextText();
      int absoluteStart = page.offsetByCodePoints(0, source.startCodePoint()) + occurrence;
      int absoluteEnd = absoluteStart + quote.quote().length();
      quotedRanges.add(new ProcedureEvidence.QuoteRange(source, absoluteStart, absoluteEnd));
      var fields = SourceFields.split(page);
      int fragmentStart = page.offsetByCodePoints(0, source.startCodePoint());
      int fragmentEnd = page.offsetByCodePoints(0, source.endCodePoint());
      for (var field : fields) {
        if (field.start() < absoluteStart || field.end() > absoluteEnd) {
          continue;
        }
        var supporting =
            requirements.stream()
                .filter(fact -> !fact.wholeSentence()
                    && fact.matches(field, page, fields, fragmentStart, fragmentEnd))
                .toList();
        if (supporting.isEmpty()) {
          continue;
        }
        if (TruthContext.unsafe(page, field, fields)) {
          return refused("unsafe_evidence");
        }
        if (page.codePointCount(field.start(), field.end()) > 1200) {
          return refused("invalid_quote");
        }
        for (var fact : supporting) {
          var binding = fact.subjectBinding(field, page, fields, fragmentStart, fragmentEnd);
          if (binding != null) {
            if (page.codePointCount(binding.start(), binding.end()) > 1200) {
              return refused("invalid_quote");
            }
            dependencies.add(new GroundedQuote(
                quote.physicalId(), page.codePointCount(0, binding.start()),
                page.codePointCount(0, binding.end()), binding.text(), List.of(fact.sha256())));
          }
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
    for (var fact : requirements) {
      if (fact instanceof QuestionFacts.ProcedureFact procedure) {
        var proof = ProcedureEvidence.prove(procedure, quotedRanges);
        if (proof.reason() != null) {
          return refused(proof.reason());
        }
        verified.addAll(proof.quotes());
      }
    }
    if (verified.size() > MAX_QUOTES) {
      return refused("invalid_quote");
    }
    if (requirements.stream()
        .anyMatch(
            fact ->
                verified.stream().noneMatch(value -> value.factHashes().contains(fact.sha256())))) {
      return refused("incomplete_evidence");
    }
    // Subject identity is a visible original-source dependency, not an unquoted answer value.
    var complete = new LinkedHashMap<String, GroundedQuote>();
    for (var quote : verified) {
      merge(complete, quote);
    }
    for (var quote : dependencies) {
      merge(complete, quote);
    }
    if (complete.size() > MAX_QUOTES) {
      return refused("invalid_quote");
    }
    return new GroundingResult(true, "supported", List.copyOf(complete.values()));
  }

  private static void merge(Map<String, GroundedQuote> quotes, GroundedQuote next) {
    String identity = next.physicalId() + ":" + next.start() + ":" + next.end();
    var previous = quotes.get(identity);
    if (previous == null) {
      quotes.put(identity, next);
      return;
    }
    var hashes = new LinkedHashSet<>(previous.factHashes());
    hashes.addAll(next.factHashes());
    quotes.put(identity, new GroundedQuote(
        previous.physicalId(), previous.start(), previous.end(), previous.quote(), List.copyOf(hashes)));
  }

  private static boolean validUnicode(String text) {
    return text.codePoints().noneMatch(point -> point == 0 || (point >= 0xD800 && point <= 0xDFFF));
  }

  private static GroundingResult refused(String reason) {
    return new GroundingResult(false, reason, List.of());
  }
}
