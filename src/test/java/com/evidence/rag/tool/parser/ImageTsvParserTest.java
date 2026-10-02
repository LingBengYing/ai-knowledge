package com.evidence.rag.tool.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.model.domain.ImageDimensions;
import com.evidence.rag.model.domain.ImageTextRegion;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ImageTsvParserTest {
  @Test
  void preservesUnicodeRepeatedWordsAndLowConfidenceTailAcrossLines() {
    String tsv =
        """
        level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext
        1\t1\t0\t0\t0\t0\t0\t0\t100\t80\t-1\t
        2\t1\t1\t0\t0\t0\t1\t2\t96\t60\t-1\t
        3\t1\t1\t1\t0\t0\t1\t2\t96\t60\t-1\t
        4\t1\t1\t1\t1\t0\t1\t2\t80\t10\t-1\t
        5\t1\t1\t1\t1\t1\t1\t2\t20\t10\t93.5\t😀预算
        5\t1\t1\t1\t1\t2\t30\t2\t35\t10\t89\t470万元。
        4\t1\t1\t1\t2\t0\t1\t20\t96\t10\t-1\t
        5\t1\t1\t1\t2\t1\t1\t20\t15\t10\t91\t预算
        5\t1\t1\t1\t2\t2\t20\t20\t35\t10\t92\t470万元。
        5\t1\t1\t1\t2\t3\t60\t20\t37\t10\t0\t仅限试运行。
        """;

    var parsed =
        ImageTsvParser.parse(tsv.getBytes(StandardCharsets.UTF_8), new ImageDimensions(100, 80));

    assertEquals(new ImageDimensions(100, 80), parsed.dimensions());
    assertEquals(1, parsed.text().pages().size());
    assertEquals("😀预算 470万元。\n预算 470万元。 仅限试运行。\n", parsed.text().pages().getFirst().text());
    assertEquals(
        List.of(
            new ImageTextRegion(0, 3, 1, 2, 21, 12),
            new ImageTextRegion(4, 10, 30, 2, 65, 12),
            new ImageTextRegion(11, 13, 1, 20, 16, 30),
            new ImageTextRegion(14, 20, 20, 20, 55, 30),
            new ImageTextRegion(21, 27, 60, 20, 97, 30)),
        parsed.regions());
    assertEquals("😀预算 470万元。\n预算 470万元。 仅限试运行。", parsed.text().segments().getFirst().text());
  }

  @ParameterizedTest
  @MethodSource("malformedOutput")
  void rejectsOutOfBoundsBoxTruncatedOutputAndRepeatedWordNumber(String tsv) {
    var failure =
        assertThrows(
            TextParser.Failure.class,
            () ->
                ImageTsvParser.parse(
                    tsv.getBytes(StandardCharsets.UTF_8), new ImageDimensions(100, 80)));
    assertEquals("parser_invalid_output", failure.code());
  }

  @Test
  void rejectsInitialWordBomRatherThanReturningShiftedCodePointRegions() {
    String tsv =
        "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n"
            + "1\t1\t0\t0\t0\t0\t0\t0\t100\t80\t-1\t\n"
            + "2\t1\t1\t0\t0\t0\t0\t0\t100\t80\t-1\t\n"
            + "3\t1\t1\t1\t0\t0\t0\t0\t100\t80\t-1\t\n"
            + "4\t1\t1\t1\t1\t0\t0\t0\t100\t80\t-1\t\n"
            + "5\t1\t1\t1\t1\t1\t2\t3\t20\t10\t99\t\uFEFF预算\n";
    var failure =
        assertThrows(
            TextParser.Failure.class,
            () ->
                ImageTsvParser.parse(
                    tsv.getBytes(StandardCharsets.UTF_8), new ImageDimensions(100, 80)));
    assertEquals("parser_invalid_output", failure.code());
  }

  private static Stream<String> malformedOutput() {
    String header =
        "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n"
            + "1\t1\t0\t0\t0\t0\t0\t0\t100\t80\t-1\t\n"
            + "2\t1\t1\t0\t0\t0\t0\t0\t100\t80\t-1\t\n"
            + "3\t1\t1\t1\t0\t0\t0\t0\t100\t80\t-1\t\n"
            + "4\t1\t1\t1\t1\t0\t0\t0\t100\t80\t-1\t\n";
    String word = "5\t1\t1\t1\t1\t1\t2\t3\t20\t10\t99\t预算\n";
    return Stream.of(
        header + "5\t1\t1\t1\t1\t1\t90\t3\t20\t10\t99\t预算\n",
        header + word.substring(0, word.length() - 1),
        header + word + word);
  }
}
