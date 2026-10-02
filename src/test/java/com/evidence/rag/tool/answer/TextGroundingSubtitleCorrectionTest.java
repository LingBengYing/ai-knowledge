package com.evidence.rag.tool.answer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.GroundingQuote;
import com.evidence.rag.model.domain.GroundingText;
import com.evidence.rag.model.domain.ModelValues;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TextGroundingSubtitleCorrectionTest {
  @ParameterizedTest
  @ValueSource(strings = {"", "更正：", "更正: ", "记录："})
  void completeSameTrackNamedCorrectionVetoesOnlyTheQuotedEarlierCue(String label) {
    String quote = "星港项目的预算为47万元";
    String context = quote + "。\n" + label + "星港项目的预算不是47万元，而是53万元。";
    var result =
        new TextGrounding()
            .verifyText(
                "星港项目的预算是多少？",
                List.of(source(context, quote)),
                List.of(new GroundingQuote("cue", quote)));
    assertFalse(result.supported(), label);
    assertTrue(result.quotes().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "更正：", "更正: ", "记录："})
  void correctionOfAnExplicitlyDifferentSubjectDoesNotRevokeThisCue(String label) {
    String quote = "星港项目的预算为47万元";
    String context = quote + "。\n" + label + "海舟项目的预算不是47万元，而是53万元。";
    var result =
        new TextGrounding()
            .verifyText(
                "星港项目的预算是多少？",
                List.of(source(context, quote)),
                List.of(new GroundingQuote("cue", quote)));
    assertTrue(result.supported(), label);
  }

  @ParameterizedTest
  @ValueSource(strings = {"示例：", "例子：", "Example: "})
  void explicitlyIllustrativeCorrectionDoesNotRevokeTheAssertedCue(String label) {
    String quote = "星港项目的预算为47万元";
    String context = quote + "。\n" + label + "星港项目的预算不是47万元，而是53万元。";
    var result =
        new TextGrounding()
            .verifyText(
                "星港项目的预算是多少？",
                List.of(source(context, quote)),
                List.of(new GroundingQuote("cue", quote)));
    assertTrue(result.supported(), label);
  }

  private static GroundingText source(String context, String quote) {
    return new GroundingText(
        "cue",
        "subtitle-track",
        context,
        ModelValues.sha256(context.getBytes(StandardCharsets.UTF_8)),
        0,
        quote.codePointCount(0, quote.length()));
  }
}
