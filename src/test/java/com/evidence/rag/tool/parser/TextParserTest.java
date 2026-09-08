package com.evidence.rag.tool.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.ParsedText;
import com.evidence.rag.model.domain.TextPage;
import com.evidence.rag.model.domain.TextSegment;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class TextParserTest {
  final TextParser parser = new TextParser();

  @Test
  void preservesCodePointLocatorsAndNormalizesOnlyLineEndings() {
    var parsed = parser.parse("笔记.md", "text/markdown", bytes("\uFEFF  😀上海住宿650元。\r\n餐补120元。 \r"));
    assertEquals("  😀上海住宿650元。\n餐补120元。 \n", parsed.pages().getFirst().text());
    var segment = parsed.segments().getFirst();
    assertEquals(2, segment.start());
    assertEquals("😀上海住宿650元。\n餐补120元。", segment.text());
    assertEquals(
        segment.text().codePointCount(0, segment.text().length()), segment.end() - segment.start());
    assertLocators(parsed);
  }

  @Test
  void longChineseAndEmojiTextKeepsOverlapsAndCoverage() {
    String content = ("😀允许仅在审批后报销，不批准不得执行。\n").repeat(180);
    var parsed = parser.parse("notes.txt", "text/plain", bytes(content));
    assertTrue(parsed.segments().size() > 3);
    assertLocators(parsed);
    int previous = 0;
    for (var s : parsed.segments()) {
      assertTrue(s.start() <= previous + 1);
      assertTrue(s.end() - s.start() <= 1200);
      previous = s.end();
    }
    assertTrue(previous >= content.codePointCount(0, content.length()) - 1);
  }

  @Test
  void extractsOriginalFrozenPdfCorpusWithoutRewritingGroundTruth() throws Exception {
    var root = Path.of("src/test/resources/corpus");
    var travel =
        parser.parse(
            "星河制造差旅政策.pdf", "application/pdf", Files.readAllBytes(root.resolve("星河制造差旅政策.pdf")));
    assertTrue(travel.pages().stream().anyMatch(p -> p.text().contains("上海住宿标准为每晚650元")));
    assertTrue(travel.pages().stream().anyMatch(p -> p.text().contains("餐饮补贴为每人每天120元")));
    var atlas =
        parser.parse(
            "Atlas路由器运维手册.pdf",
            "application/octet-stream",
            Files.readAllBytes(root.resolve("Atlas路由器运维手册.pdf")));
    assertTrue(atlas.pages().stream().anyMatch(p -> p.text().contains("长按 RESET 键十秒")));
    var hostile =
        parser.parse(
            "不可信指令样例.pdf", "application/pdf", Files.readAllBytes(root.resolve("不可信指令样例.pdf")));
    assertTrue(
        hostile.pages().stream().anyMatch(p -> p.text().contains("123456")),
        "Untrusted instructions remain data, not executed or silently rewritten");
    var other =
        parser.parse(
            "另一组织薪酬资料.pdf", "application/pdf", Files.readAllBytes(root.resolve("另一组织薪酬资料.pdf")));
    assertTrue(other.pages().stream().anyMatch(p -> p.text().contains("980")));
    for (var parsed : List.of(travel, atlas, hostile, other)) {
      assertLocators(parsed);
    }
  }

  @Test
  void whitespaceOverlapHasMonotonicLocatorsAndPreservesEveryNonSpaceCodePoint() {
    for (String gap : List.of(" ", "\u00A0", "\u3000")) {
      for (String token : List.of("x", "批准", "😀")) {
        String content = gap.repeat(1100) + token + gap.repeat(200) + "禁止y🚫";
        var parsed = parser.parse("spacing.txt", "text/plain", bytes(content));
        assertLocators(parsed);
        int previousStart = -1;
        var covered = new java.util.BitSet();
        for (var segment : parsed.segments()) {
          assertTrue(
              segment.start() > previousStart, "Same-page locators must advance after trimming");
          assertTrue(segment.end() - segment.start() <= 1200);
          covered.set(segment.start(), segment.end());
          previousStart = segment.start();
        }
        int[] points = content.codePoints().toArray();
        for (int i = 0; i < points.length; i++) {
          if (!Character.isWhitespace(points[i]) && !Character.isSpaceChar(points[i])) {
            assertTrue(covered.get(i), "Every non-space code point must remain in evidence");
          }
        }
      }
    }
  }

  @Test
  void maintainsPageNumbersAndRejectsEncryptedOrEmptyPdfs() throws Exception {
    var parsed = parser.parse("policy.pdf", "application/pdf", pdf(false, true));
    assertEquals(2, parsed.pages().size());
    assertEquals(2, parsed.segments().getFirst().page());
    assertTrue(parsed.segments().getFirst().text().contains("Rate 650."));
    assertLocators(parsed);
    assertThrows(
        TextParser.Failure.class,
        () -> parser.parse("secure.pdf", "application/pdf", pdf(true, true)));
    assertThrows(
        TextParser.Failure.class,
        () -> parser.parse("empty.pdf", "application/pdf", pdf(false, false)));
  }

  @Test
  void rejectsUnsafeUnsupportedSpoofedOrInvalidTextInputs() {
    for (String name :
        List.of("../x.txt", "x/y.txt", "x\\y.txt", "x.html", ".txt", "x\u0000.txt")) {
      assertThrows(
          TextParser.Failure.class, () -> parser.parse(name, "text/plain", bytes("content")), name);
    }
    for (byte[] bad :
        List.of(
            new byte[0],
            bytes(" \n\t"),
            new byte[] {(byte) 0xC3, 0x28},
            bytes("secret\u0000binary"),
            bytes("%PDF broken"))) {
      assertThrows(TextParser.Failure.class, () -> parser.parse("x.txt", "text/plain", bad));
    }
    assertThrows(
        TextParser.Failure.class,
        () -> parser.parse("x.pdf", "application/pdf", bytes("fake PDF")));
    assertThrows(
        TextParser.Failure.class, () -> parser.parse("x.txt", "image/png", bytes("content")));
    assertThrows(
        TextParser.Failure.class,
        () -> parser.parse("x.txt", "text/plain", new byte[TextParser.MAX_BYTES + 1]));
    assertThrows(
        TextParser.Failure.class, () -> parser.parse(null, "text/plain", bytes("content")));
    assertThrows(TextParser.Failure.class, () -> parser.parse("x.txt", null, bytes("content")));
    assertThrows(TextParser.Failure.class, () -> parser.parse("x.txt", "text/plain", null));
  }

  @Test
  void boundsExtractedTextAndImmutableResults() {
    assertThrows(
        TextParser.Failure.class,
        () -> parser.parse("huge.txt", "text/plain", bytes("字".repeat(1_000_001))));
    var parsed = parser.parse("plain.TXT", "application/octet-stream", bytes("中文 policy 650."));
    assertThrows(UnsupportedOperationException.class, () -> parsed.pages().clear());
    assertThrows(UnsupportedOperationException.class, () -> parsed.segments().clear());
    assertEquals("java-text-parser-v2-monotonic-codepoints", TextParser.REVISION);
  }

  @Test
  void domainTextSnapshotsAreDefensiveAndNeverExposeSourceInDiagnostics() {
    var page = new TextPage(1, "private synthetic document");
    var segment = new TextSegment(0, 1, 0, 7, "private");
    var pages = new ArrayList<>(List.of(page));
    var segments = new ArrayList<>(List.of(segment));
    var parsed = new ParsedText(pages, segments);
    pages.clear();
    segments.clear();
    assertEquals(List.of(page), parsed.pages());
    assertEquals(List.of(segment), parsed.segments());
    assertThrows(UnsupportedOperationException.class, () -> parsed.pages().clear());
    assertThrows(UnsupportedOperationException.class, () -> parsed.segments().clear());
    assertEquals("TextPage[redacted]", page.toString());
    assertEquals("TextSegment[redacted]", segment.toString());
    assertEquals("ParsedText[redacted]", parsed.toString());
  }

  static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  static void assertLocators(ParsedText parsed) {
    for (var segment : parsed.segments()) {
      var page = parsed.pages().get(segment.page() - 1).text();
      assertEquals(
          segment.text(),
          page.substring(
              page.offsetByCodePoints(0, segment.start()),
              page.offsetByCodePoints(0, segment.end())));
    }
  }

  static byte[] pdf(boolean encrypted, boolean text) throws Exception {
    try (var doc = new PDDocument();
        var bytes = new ByteArrayOutputStream()) {
      doc.addPage(new PDPage());
      var page = new PDPage();
      doc.addPage(page);
      if (text) {
        try (var stream = new PDPageContentStream(doc, page)) {
          stream.beginText();
          stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
          stream.newLineAtOffset(20, 700);
          stream.showText("Rate 650.");
          stream.endText();
        }
      }
      if (encrypted) {
        doc.protect(new StandardProtectionPolicy("owner", "user", new AccessPermission()));
      }
      doc.save(bytes);
      return bytes.toByteArray();
    }
  }
}
