package com.evidence.rag.tool.answer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.GroundingText;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.QuestionFact;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class TranscriptFactConflictTest {
  private static final String QUESTION = "指示灯的颜色是什么？重启操作是什么？";
  private static final QuestionFact COLOR =
      QuestionPlanning.plan(QUESTION).orElseThrow().facts().getFirst();
  private static final List<String> RED = List.of("指示灯的颜色是红色");

  @Test
  void unquotedTranscriptOutsideSelectedSpanCanVetoButNeverSupplyProof() {
    var transcript = context("重启操作是按下复位键。\n指示灯的颜色是蓝色。", "重启操作是按下复位键");
    assertTrue(QuestionPlanning.conflictsWithTranscript(QUESTION, COLOR, RED, transcript));
    assertFalse(transcript.snippet().contains("颜色"));
  }

  @Test
  void agreesWithSameValueAndDoesNotInventConflictWhenNoTranscriptAssertionExists() {
    assertFalse(
        QuestionPlanning.conflictsWithTranscript(
            QUESTION, COLOR, RED, context("指示灯的颜色是红色。", "指示灯的颜色是红色")));
    assertFalse(
        QuestionPlanning.conflictsWithTranscript(
            QUESTION, COLOR, RED, context("重启操作是按下复位键。", "重启操作是按下复位键")));
    assertFalse(QuestionPlanning.conflictsWithTranscript(QUESTION, COLOR, RED, null));
  }

  @Test
  void examplesConditionalAndOtherSubjectStatementsAreNotContradictoryAssertions() {
    for (String context :
        List.of(
            "示例：指示灯的颜色是蓝色。重启操作是按下复位键。",
            "如果设备获批，指示灯的颜色是蓝色。重启操作是按下复位键。",
            "另一个设备的指示灯的颜色是蓝色。重启操作是按下复位键。",
            "指示灯的颜色是蓝色。这只是示例。重启操作是按下复位键。")) {
      assertFalse(
          QuestionPlanning.conflictsWithTranscript(
              QUESTION, COLOR, RED, context(context, "重启操作是按下复位键")),
          context);
    }
  }

  @Test
  void deniesAmbiguousOrUnparseableVisualValuesAndInvalidServerIdentity() {
    var red = context("指示灯的颜色是红色。", "指示灯的颜色是红色");
    assertTrue(
        QuestionPlanning.conflictsWithTranscript(
            QUESTION, COLOR, List.of("指示灯的颜色是红色", "指示灯的颜色是蓝色"), red));
    assertTrue(QuestionPlanning.conflictsWithTranscript(QUESTION, COLOR, List.of("有一盏红灯"), red));
    assertTrue(QuestionPlanning.conflictsWithTranscript(QUESTION, COLOR, List.of(), red));
    assertTrue(QuestionPlanning.conflictsWithTranscript(QUESTION, null, RED, red));
    assertTrue(
        QuestionPlanning.conflictsWithTranscript(
            QUESTION, new QuestionFact(0, "a".repeat(64), COLOR.requirement()), RED, red));
    assertTrue(
        QuestionPlanning.conflictsWithTranscript(
            QUESTION, COLOR, RED, context("指示灯的颜色是红色。指示灯的颜色是蓝色。", "指示灯的颜色是红色")));
  }

  @Test
  void retainsCompleteEnglishProcedureTailWhenComparingObservations() {
    String question = "How do I restart the device?";
    var fact = QuestionPlanning.plan(question).orElseThrow().facts().getFirst();
    var transcript =
        context(
            "restart the device: press reset. Then release reset.",
            "restart the device: press reset");
    assertFalse(
        QuestionPlanning.conflictsWithTranscript(
            question,
            fact,
            List.of("restart the device: press reset. Then release reset"),
            transcript));
    assertTrue(
        QuestionPlanning.conflictsWithTranscript(
            question, fact, List.of("restart the device: press reset"), transcript));
  }

  private static GroundingText context(String fullText, String snippet) {
    int start = fullText.codePointCount(0, fullText.indexOf(snippet));
    return new GroundingText(
        "selected-transcript-span",
        "full-video-transcript",
        fullText,
        ModelValues.sha256(fullText.getBytes(StandardCharsets.UTF_8)),
        start,
        start + snippet.codePointCount(0, snippet.length()));
  }
}
