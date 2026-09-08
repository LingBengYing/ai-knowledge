package com.evidence.rag.tool.answer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.GroundingQuote;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.PublishedEvidence;
import com.evidence.rag.model.domain.TextPage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class TextGroundingBoundaryTest {
  private static final String QUESTION = "项目预算是多少？";
  private static final String FACT = "项目预算为470万元";
  private final TextGrounding grounding = new TextGrounding();

  @Test
  void questionByteLimitAppliesBeforeWhitespaceOrFactDeduplication() {
    var source = TextGroundingTest.evidence("fact", FACT, FACT);
    var quotes = List.of(new GroundingQuote(source.physicalSegmentId(), FACT));
    String atLimit = " ".repeat(4096 - QUESTION.getBytes(StandardCharsets.UTF_8).length) + QUESTION;
    assertEquals(4096, atLimit.getBytes(StandardCharsets.UTF_8).length);
    assertTrue(grounding.verify(atLimit, List.of(source), quotes).supported());
    var overflow = grounding.verify(" " + atLimit, List.of(source), quotes);
    assertFalse(overflow.supported());
    assertEquals("unsupported_question", overflow.reason());
    assertTrue(overflow.quotes().isEmpty());
  }

  @Test
  void sixtyFifthCandidateCannotBeSilentlyIgnoredEvenWhenFirstCandidateProvesTheQuestion() {
    var candidates = candidates(64);
    var quotes = List.of(new GroundingQuote(candidates.getFirst().physicalSegmentId(), FACT));
    assertTrue(grounding.verify(QUESTION, candidates, quotes).supported());
    candidates.add(TextGroundingTest.evidence("overflow", "记录编号是R-65", "记录编号是R-65"));
    var overflow = grounding.verify(QUESTION, candidates, quotes);
    assertFalse(overflow.supported());
    assertEquals("invalid_quote", overflow.reason());
    assertTrue(overflow.quotes().isEmpty());
  }

  @Test
  void thirtyThirdOriginalQuoteCannotBeSilentlyIgnored() {
    var candidates = candidates(33);
    var quotes = new ArrayList<GroundingQuote>();
    for (var candidate : candidates) {
      quotes.add(new GroundingQuote(candidate.physicalSegmentId(), candidate.segment().text()));
    }
    assertTrue(grounding.verify(QUESTION, candidates, quotes.subList(0, 32)).supported());
    var overflow = grounding.verify(QUESTION, candidates, quotes);
    assertFalse(overflow.supported());
    assertEquals("invalid_quote", overflow.reason());
    assertTrue(overflow.quotes().isEmpty());
  }

  @Test
  void uniqueAuthorityPageBudgetAcceptsEightMiBAndRefusesOneAdditionalByte() {
    String page =
        "x".repeat(8 * 1024 * 1024 - FACT.getBytes(StandardCharsets.UTF_8).length - 1)
            + "\n"
            + FACT;
    assertEquals(8 * 1024 * 1024, page.getBytes(StandardCharsets.UTF_8).length);
    var source = TextGroundingTest.evidence("large", page, FACT);
    var quotes = List.of(new GroundingQuote(source.physicalSegmentId(), FACT));
    assertTrue(grounding.verify(QUESTION, List.of(source), quotes).supported());
    var overflow = TextGroundingTest.evidence("large", "x" + page, FACT);
    var result = grounding.verify(QUESTION, List.of(overflow), quotes);
    assertFalse(result.supported());
    assertEquals("invalid_quote", result.reason());
    assertTrue(result.quotes().isEmpty());
  }

  @Test
  void budgetCountsDistinctPublicationPagesButNotTwoCandidatesFromTheSamePage() {
    String page = "x".repeat(4 * 1024 * 1024) + "\n" + FACT;
    var first = TextGroundingTest.evidence("first", page, FACT);
    var samePage =
        new PublishedEvidence(
            first.publication(),
            "physical-alias",
            first.entrySha256(),
            first.segment(),
            first.page(),
            first.pageSha256(),
            first.filename());
    var quotes = List.of(new GroundingQuote(first.physicalSegmentId(), FACT));
    assertTrue(grounding.verify(QUESTION, List.of(first, samePage), quotes).supported());
    var otherPublication = TextGroundingTest.evidence("second", page, FACT);
    var overflow = grounding.verify(QUESTION, List.of(first, otherPublication), quotes);
    assertFalse(overflow.supported());
    assertEquals("invalid_quote", overflow.reason());
  }

  @Test
  void samePublicationPageIdentityCannotCarryTwoDifferentAuthorityBodies() {
    String firstPage = FACT + "。记录编号是A";
    String secondPage = FACT + "。记录编号是B";
    var first = TextGroundingTest.evidence("same", firstPage, FACT);
    var inconsistent =
        new PublishedEvidence(
            first.publication(),
            "physical-other",
            first.entrySha256(),
            first.segment(),
            new TextPage(1, secondPage),
            ModelValues.sha256(secondPage.getBytes(StandardCharsets.UTF_8)),
            first.filename());
    var result =
        grounding.verify(
            QUESTION,
            List.of(first, inconsistent),
            List.of(new GroundingQuote(first.physicalSegmentId(), FACT)));
    assertFalse(result.supported());
    assertEquals("invalid_quote", result.reason());
  }

  @ParameterizedTest
  @MethodSource("invalidUnicode")
  void malformedUnicodeOutsideTheQuotedSegmentCannotCorruptPageLocators(String invalid) {
    String page = invalid + "\n" + FACT;
    var source = TextGroundingTest.evidence("unicode", page, FACT);
    var result =
        grounding.verify(
            QUESTION,
            List.of(source),
            List.of(new GroundingQuote(source.physicalSegmentId(), FACT)));
    assertFalse(result.supported());
    assertEquals("invalid_quote", result.reason());
    assertTrue(result.quotes().isEmpty());
  }

  static Stream<String> invalidUnicode() {
    return Stream.of("\uD83D", "\uDE00", "\u0000");
  }

  @Test
  void neitherHalfOfAnEmojiIsAValidOriginalQuoteEvenBesideAValidProof() {
    String page = FACT + "。😀";
    var source = TextGroundingTest.evidence("emoji", page, page);
    for (String half : List.of("\uD83D", "\uDE00")) {
      var result =
          grounding.verify(
              QUESTION,
              List.of(source),
              List.of(
                  new GroundingQuote(source.physicalSegmentId(), FACT),
                  new GroundingQuote(source.physicalSegmentId(), half)));
      assertFalse(result.supported());
      assertEquals("invalid_quote", result.reason());
    }
  }

  @Test
  void codePointLocatorPreservesEmojiAndCombiningCharacterPrefixWithoutNormalization() {
    String fact = "星港计划的设备识别码是ZX-714";
    String page = "😀e\u0301\n" + fact;
    var source = TextGroundingTest.evidence("combining", page, fact);
    var result =
        grounding.verify(
            "星港计划的设备识别码是什么？",
            List.of(source),
            List.of(new GroundingQuote(source.physicalSegmentId(), fact)));
    assertTrue(result.supported());
    assertEquals(4, result.quotes().getFirst().start());
    assertEquals(21, result.quotes().getFirst().end());
    assertEquals(fact, result.quotes().getFirst().quote());
  }

  @Test
  void identicalModelQuoteIsRejectedButDistinctOverlappingOriginalQuotesAreDeduplicated() {
    String page = FACT + "。记录编号是A";
    var source = TextGroundingTest.evidence("duplicate", page, page);
    var factQuote = new GroundingQuote(source.physicalSegmentId(), FACT);
    var duplicate = grounding.verify(QUESTION, List.of(source), List.of(factQuote, factQuote));
    assertFalse(duplicate.supported());
    assertEquals("invalid_quote", duplicate.reason());
    var overlapping =
        grounding.verify(
            QUESTION,
            List.of(source),
            List.of(factQuote, new GroundingQuote(source.physicalSegmentId(), page)));
    assertTrue(overlapping.supported());
    assertEquals(1, overlapping.quotes().size());
    assertEquals(1, overlapping.quotes().getFirst().factHashes().size());
  }

  private static ArrayList<PublishedEvidence> candidates(int count) {
    var result = new ArrayList<PublishedEvidence>();
    result.add(TextGroundingTest.evidence("fact", FACT, FACT));
    for (int index = 1; index < count; index++) {
      String text = "记录编号是R-" + index;
      result.add(TextGroundingTest.evidence("record-" + index, text, text));
    }
    return result;
  }
}
