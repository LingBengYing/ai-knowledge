package com.evidence.rag.tool.answer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.GroundingQuote;
import com.evidence.rag.model.domain.IndexSegment;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.PublishedEvidence;
import com.evidence.rag.model.domain.TextPage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TextGroundingTest {
  private final TextGrounding grounding = new TextGrounding();

  @Test
  void provesExactNamedFactAndReturnsAuthoritativePageCodePointLocator() {
    var evidence =
        evidence("doc-one", "😀\n星港计划的设备识别码是ZX-714。负责人是林溪。", "星港计划的设备识别码是ZX-714。负责人是林溪。");
    var result =
        grounding.verify(
            "星港计划的设备识别码是什么？",
            List.of(evidence),
            List.of(new GroundingQuote(evidence.physicalSegmentId(), "星港计划的设备识别码是ZX-714")));

    assertTrue(result.supported());
    assertEquals("supported", result.reason());
    assertEquals(1, result.quotes().size());
    var quote = result.quotes().getFirst();
    assertEquals(evidence.physicalSegmentId(), quote.physicalId());
    assertEquals("星港计划的设备识别码是ZX-714", quote.quote());
    assertEquals(2, quote.start());
    assertEquals(19, quote.end());
    assertEquals(1, quote.factHashes().size());
    assertTrue(quote.factHashes().getFirst().matches("[a-f0-9]{64}"));
  }

  @ParameterizedTest
  @MethodSource("completeQuestions")
  void provesEveryRequestedFactAndRefusesMissingTail(
      String question, String firstFact, String lastFact) {
    var first = evidence("doc-one", firstFact + "。", firstFact + "。");
    var last = evidence("doc-two", lastFact + "。", lastFact + "。");
    var all = List.of(first, last);
    var firstQuote = new GroundingQuote(first.physicalSegmentId(), firstFact);
    var lastQuote = new GroundingQuote(last.physicalSegmentId(), lastFact);
    var result = grounding.verify(question, all, List.of(firstQuote, lastQuote));

    assertTrue(result.supported(), question);
    assertEquals(2, result.quotes().size());
    assertEquals(
        2,
        result.quotes().stream().flatMap(quote -> quote.factHashes().stream()).distinct().count());
    var incomplete = grounding.verify(question, all, List.of(firstQuote));
    assertFalse(incomplete.supported(), "The last fact may not silently disappear");
    assertTrue(incomplete.quotes().isEmpty());
  }

  static Stream<Arguments> completeQuestions() {
    return Stream.of(
        Arguments.of("星港计划的设备识别码是什么？星港计划的巡检窗口是什么？", "星港计划的设备识别码是ZX-714", "星港计划的巡检窗口是周三夜间"),
        Arguments.of("星港计划的设备识别码和巡检窗口分别是什么？", "星港计划的设备识别码是ZX-714", "星港计划的巡检窗口是周三夜间"),
        Arguments.of("请说明星港计划的设备识别码以及星港计划的巡检窗口", "星港计划的设备识别码是ZX-714", "星港计划的巡检窗口是周三夜间"),
        Arguments.of("A区和B区分别是什么颜色？", "A区是红色", "B区是蓝色"),
        Arguments.of(
            "What is Project A's status? What is Project A's budget?",
            "Project A's status is operational",
            "Project A's budget is 270 USD"));
  }

  @Test
  void doesNotSplitConnectorInsideAProperName() {
    String fact = "河和园的预算为180万元";
    var source = evidence("doc-one", fact, fact);
    assertTrue(
        grounding
            .verify(
                "河和园的预算是多少？",
                List.of(source),
                List.of(new GroundingQuote(source.physicalSegmentId(), fact)))
            .supported());
  }

  @Test
  void factLimitCannotTurnAWholeQuestionIntoAPartialAnswer() {
    var candidates = new java.util.ArrayList<PublishedEvidence>();
    var quotes = new java.util.ArrayList<GroundingQuote>();
    var question = new StringBuilder();
    for (int number = 1; number <= 9; number++) {
      String relation = "项目" + number + "的编号";
      String fact = relation + "是CODE-" + number;
      var source = evidence("doc-" + number, fact, fact);
      candidates.add(source);
      quotes.add(new GroundingQuote(source.physicalSegmentId(), fact));
      question.append(relation).append("是什么？");
    }
    var result = grounding.verify(question.toString(), candidates, quotes);
    assertFalse(result.supported());
    assertEquals("unsupported_question", result.reason());
    assertTrue(result.quotes().isEmpty());
  }

  static PublishedEvidence evidence(String documentId, String pageText, String segmentText) {
    int utf16Start = pageText.indexOf(segmentText);
    int start = pageText.codePointCount(0, utf16Start);
    var segment =
        new IndexSegment(
            "segment-" + documentId,
            0,
            1,
            start,
            start + segmentText.codePointCount(0, segmentText.length()),
            segmentText,
            sha256(segmentText));
    var publication =
        new PublicationVersion(
            documentId,
            "publication-" + documentId,
            "source-" + documentId,
            "generation-" + documentId,
            sha256(pageText),
            "parser-test-v1",
            new IndexTarget("embedding-test", "projection-test", "model-test-v1", 2),
            "a".repeat(64),
            1);
    return new PublishedEvidence(
        publication,
        "physical-" + documentId,
        "b".repeat(64),
        segment,
        new TextPage(1, pageText),
        sha256(pageText),
        "fixture.txt");
  }

  private static String sha256(String text) {
    return ModelValues.sha256(text.getBytes(StandardCharsets.UTF_8));
  }
}
