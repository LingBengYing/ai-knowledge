package com.evidence.rag.tool.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.ImageDimensions;
import com.evidence.rag.model.domain.ImageTextRegion;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ImageTsvOptionalTest {
  private static final ImageDimensions DIMENSIONS = new ImageDimensions(100, 80);
  private static final String HEADER =
      "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n";
  private static final String PAGE = "1\t1\t0\t0\t0\t0\t0\t0\t100\t80\t-1\t\n";

  @Test
  void acceptsOnlyACompleteValidatedWordlessPageAsEmpty() {
    assertTrue(ImageTsvParser.parseOptional(bytes(HEADER + PAGE), DIMENSIONS).isEmpty());
    assertTrue(
        ImageTsvParser.parseOptional(
                bytes(HEADER + PAGE + "2\t1\t1\t0\t0\t0\t1\t2\t90\t60\t-1\t\n"), DIMENSIONS)
            .isEmpty());
  }

  @Test
  void preservesTheSameCodePointsSegmentsAndWordBoxesForNonemptyOutput() {
    byte[] tsv =
        bytes(
            HEADER
                + PAGE
                + "5\t1\t1\t1\t1\t1\t2\t3\t20\t10\t99\t😀预算\n"
                + "5\t1\t1\t1\t1\t2\t30\t3\t45\t10\t0\t470万元。\n");
    var image = ImageTsvParser.parseOptional(tsv, DIMENSIONS).orElseThrow();
    assertEquals(ImageTsvParser.parse(tsv, DIMENSIONS), image);
    assertEquals("😀预算 470万元。\n", image.text().pages().getFirst().text());
    assertEquals(
        List.of(new ImageTextRegion(0, 3, 2, 3, 22, 13), new ImageTextRegion(4, 10, 30, 3, 75, 13)),
        image.regions());
  }

  @Test
  void keepsIndependentImageParsingStrictForWordlessInput() {
    assertInvalid(() -> ImageTsvParser.parse(bytes(HEADER + PAGE), DIMENSIONS));
  }

  @ParameterizedTest
  @MethodSource("invalidEmptyResults")
  void cannotTurnTruncationBadDimensionsOrMalformedTsvIntoAnEmptyResult(byte[] tsv) {
    assertInvalid(() -> ImageTsvParser.parseOptional(tsv, DIMENSIONS));
  }

  private static Stream<byte[]> invalidEmptyResults() {
    return Stream.of(
        bytes(""),
        bytes(HEADER),
        bytes(HEADER + PAGE.substring(0, PAGE.length() - 1)),
        bytes(HEADER + PAGE.replace("100\t80", "99\t80")),
        bytes(HEADER + PAGE + PAGE),
        bytes(HEADER + "2\t1\t1\t0\t0\t0\t0\t0\t100\t80\t-1\t\n"),
        bytes(HEADER + PAGE + "5\t1\t1\t1\t1\t1\t0\t0\t10\t10\t99\t\n"),
        bytes(HEADER + PAGE + "2\t1\t1\t0\t0\t0\t1\t2\t101\t60\t-1\t\n"),
        new byte[] {(byte) 0xc3, (byte) 0x28, '\n'});
  }

  private static void assertInvalid(org.junit.jupiter.api.function.Executable action) {
    assertEquals("parser_invalid_output", assertThrows(TextParser.Failure.class, action).code());
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
