package com.evidence.rag.tool.answer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.GroundingQuote;
import com.evidence.rag.model.domain.GroundingText;
import com.evidence.rag.model.domain.ModelValues;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class GroundingTextTest {
  private final TextGrounding grounding = new TextGrounding();

  @Test
  void transcriptFactUsesItsTrueContextCodePointsWithoutAnyPage() {
    String fact = "星港计划的设备识别码是ZX-714";
    var source = source("audio-fact", "transcript-v1", "😀\n" + fact + "。负责人是林溪。", fact);
    var result =
        grounding.verifyText(
            "星港计划的设备识别码是什么？",
            List.of(source),
            List.of(new GroundingQuote(source.physicalId(), fact)));

    assertTrue(result.supported(), result.reason());
    assertEquals(fact, source.snippet());
    assertEquals(2, result.quotes().getFirst().start());
    assertEquals(19, result.quotes().getFirst().end());
    assertEquals(fact, result.quotes().getFirst().quote());
    assertEquals("GroundingText[redacted]", source.toString());
  }

  @Test
  void completeAudioCandidateCanReach4096CodePointsWhileItsProofStaysBounded() {
    String fact = "项目预算为470万元";
    String context = "x".repeat(4096 - fact.length() - 1) + "\n" + fact;
    var source = source("audio-long", "transcript-long", context, context);
    var result =
        grounding.verifyText(
            "项目预算是多少？", List.of(source), List.of(new GroundingQuote(source.physicalId(), fact)));

    assertEquals(4096, source.snippet().codePointCount(0, source.snippet().length()));
    assertTrue(result.supported(), result.reason());
    assertEquals(fact, result.quotes().getFirst().quote());
    assertEquals(4096, result.quotes().getFirst().end());
  }

  @ParameterizedTest
  @MethodSource("unquotedContext")
  void unquotedTranscriptContextRetainsTheSameProofAsRealTextPages(
      String context, boolean supported, String reason) {
    String fact = "项目预算为470万元";
    String question = "项目预算是多少？";
    var transcript = source("physical-parity", "transcript-parity", context, fact);
    var page = TextGroundingTest.evidence("parity", context, fact);
    var quotes = List.of(new GroundingQuote(transcript.physicalId(), fact));
    var original = grounding.verify(question, List.of(page), quotes);
    var audio = grounding.verifyText(question, List.of(transcript), quotes);

    assertEquals(supported, original.supported(), context);
    assertEquals(reason, original.reason(), context);
    assertEquals(original, audio, context);
  }

  static Stream<Arguments> unquotedContext() {
    return Stream.of(
        Arguments.of("😀\n项目预算为470万元。", true, "supported"),
        Arguments.of("仅适用于试运行。\n项目预算为470万元", false, "unsafe_evidence"),
        Arguments.of("项目预算为470万元，仅适用于试运行", false, "unsafe_evidence"),
        Arguments.of("项目预算为470万元。上述说法是错误的。", false, "unsafe_evidence"),
        Arguments.of("并非项目预算为470万元。", false, "incomplete_evidence"),
        Arguments.of("项目预算为470万元。项目预算为480万元。", false, "conflicting_evidence"));
  }

  @Test
  void aProcedureMustQuoteAllStepsFromTheCompleteTranscriptContext() {
    String first = "清理日志：进入设置";
    String last = "然后点击清理按钮";
    String context = first + "。" + last + "。";
    var beginning = source("audio-first", "transcript-procedure", context, first);
    var ending = source("audio-last", "transcript-procedure", context, last);
    var firstQuote = new GroundingQuote(beginning.physicalId(), first);
    var lastQuote = new GroundingQuote(ending.physicalId(), last);

    var complete =
        grounding.verifyText("如何清理日志？", List.of(beginning, ending), List.of(firstQuote, lastQuote));
    assertTrue(complete.supported(), complete.reason());
    assertEquals(2, complete.quotes().size());
    var partial = grounding.verifyText("如何清理日志？", List.of(beginning), List.of(firstQuote));
    assertFalse(partial.supported());
    assertEquals("incomplete_evidence", partial.reason());
    assertTrue(partial.quotes().isEmpty());
  }

  @Test
  void oneContextIdentityCannotDescribeDifferentWholeTranscripts() {
    String fact = "项目预算为470万元";
    var original = source("audio-one", "transcript-shared", fact, fact);
    var inconsistent = source("audio-two", "transcript-shared", "备注。" + fact, fact);
    var result =
        grounding.verifyText(
            "项目预算是多少？",
            List.of(original, inconsistent),
            List.of(new GroundingQuote(original.physicalId(), fact)));

    assertFalse(result.supported());
    assertEquals("invalid_quote", result.reason());
    assertTrue(result.quotes().isEmpty());
  }

  @Test
  void questionValidationStillPrecedesMalformedEvidence() {
    assertEquals("unsupported_question", grounding.verifyText(null, null, null).reason());
    assertEquals("unsupported_question", grounding.verify(null, null, null).reason());
    assertEquals("invalid_quote", grounding.verifyText("项目预算是多少？", null, null).reason());
    assertEquals("invalid_quote", grounding.verify("项目预算是多少？", null, null).reason());
  }

  @Test
  void sourceRejectsFalseHashesMalformedUnicodeAndNonexistentCodePointRanges() {
    String text = "😀\n项目预算为470万元";
    int length = text.codePointCount(0, text.length());
    assertThrows(
        ApplicationException.class,
        () -> new GroundingText("id", "context", text, "a".repeat(64), 0, length));
    for (String invalid : List.of("\u0000", "\uD800", "\uDC00")) {
      String malformed = invalid + text;
      assertThrows(
          ApplicationException.class,
          () -> new GroundingText("id", "context", malformed, sha(malformed), 1, length));
    }
    for (int[] range : List.of(new int[] {-1, 1}, new int[] {1, 1}, new int[] {0, length + 1})) {
      assertThrows(
          ApplicationException.class,
          () -> new GroundingText("id", "context", text, sha(text), range[0], range[1]));
    }
    assertThrows(
        ApplicationException.class,
        () -> new GroundingText("id", "context", "字".repeat(4097), sha("字".repeat(4097)), 0, 4097));
  }

  private static GroundingText source(
      String physicalId, String contextId, String context, String candidate) {
    int start = context.codePointCount(0, context.indexOf(candidate));
    return new GroundingText(
        physicalId,
        contextId,
        context,
        sha(context),
        start,
        start + candidate.codePointCount(0, candidate.length()));
  }

  private static String sha(String text) {
    return ModelValues.sha256(text.getBytes(StandardCharsets.UTF_8));
  }
}
