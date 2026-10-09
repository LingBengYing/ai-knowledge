package com.evidence.rag.tool.parser;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.DocumentFormat;
import com.evidence.rag.support.DocumentFormatFixtures;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.poi.hwpf.HWPFDocument;
import org.junit.jupiter.api.Test;

class ExpandedDocumentParserTest {
  private final TextParser parser = new TextParser();

  @Test
  void syntheticDocFixtureContainsNoPersonalAuthorOrLocalPath() throws Exception {
    byte[] content = DocumentFormatFixtures.samples().get("sample.doc");
    try (var document = new HWPFDocument(new ByteArrayInputStream(content))) {
      var summary = document.getSummaryInformation();
      if (summary != null) {
        assertTrue(summary.getAuthor() == null || summary.getAuthor().isBlank());
        assertTrue(summary.getLastAuthor() == null || summary.getLastAuthor().isBlank());
      }
      for (var properties :
          new org.apache.poi.hpsf.PropertySet[] {
            summary, document.getDocumentSummaryInformation()
          }) {
        if (properties == null) continue;
        for (var section : properties.getSections()) {
          for (var property : section.getProperties()) {
            if (!(property.getValue() instanceof String value)) continue;
            assertFalse(value.contains("/Users/") || value.contains("/private/"));
          }
        }
      }
    }
  }

  @Test
  void everyRealSyntheticFormatPreservesTextAndLocators() throws Exception {
    var samples = DocumentFormatFixtures.samples();
    assertEquals(21, samples.size());
    for (var sample : samples.entrySet()) {
      var format = DocumentFormat.fromFilename(sample.getKey()).orElseThrow();
      var parsed =
          assertDoesNotThrow(
              () -> parser.parse(sample.getKey(), format.mediaType(), sample.getValue()),
              sample.getKey());
      String text =
          parsed.pages().stream()
              .map(page -> page.text())
              .collect(java.util.stream.Collectors.joining("\n"));
      assertTrue(text.contains("Cedar Beacon"), sample.getKey() + ": missing title");
      assertTrue(text.contains("48600"), sample.getKey() + ": missing body");
      assertEquals(
          1,
          parsed.pages().size(),
          sample.getKey() + ": extraction unit, not rendered Office pagination");
      TextParserTest.assertLocators(parsed);
      assertEquals(
          format.legacy() ? TextParser.REVISION : TextParser.DOCUMENT_REVISION,
          TextParser.revisionFor(sample.getKey()));
    }
  }

  @Test
  void emailAttachmentsAreNotPromotedIntoMessageBody() {
    String email =
        "From: synthetic@example.invalid\r\nSubject: Cedar Beacon\r\nMIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=synthetic-boundary\r\n\r\n--synthetic-boundary\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n<p>Original Cedar Beacon message budget 48600</p>\r\n--synthetic-boundary\r\nContent-Type: text/plain\r\nContent-Disposition: attachment; filename=other.txt\r\n\r\nATTACHMENT_NOT_ORIGINAL_BODY\r\n--synthetic-boundary--\r\n";
    String text =
        parser
            .parse("sample.eml", "message/rfc822", email.getBytes(StandardCharsets.UTF_8))
            .pages()
            .getFirst()
            .text();
    assertTrue(text.contains("48600"));
    assertFalse(text.contains("ATTACHMENT_NOT_ORIGINAL_BODY"));
  }

  @Test
  void fakeBinaryAndEmptyOrBrokenStructuredFilesFailSafely() {
    for (String extension :
        new String[] {"doc", "docx", "ppt", "pptx", "xls", "xlsx", "msg", "odt", "epub"}) {
      assertThrows(
          TextParser.Failure.class,
          () ->
              parser.parse(
                  "fake." + extension,
                  "application/octet-stream",
                  "This is not a binary document".getBytes(StandardCharsets.UTF_8)),
          extension);
    }
    assertThrows(
        TextParser.Failure.class,
        () ->
            parser.parse(
                "empty.html",
                "text/html",
                "<html><body></body></html>".getBytes(StandardCharsets.UTF_8)));
    assertThrows(
        TextParser.Failure.class,
        () ->
            parser.parse(
                "broken.xml",
                "application/xml",
                "<document><open>not closed".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void admitsEveryPromisedExtensionAndItsRealMime() {
    Map.ofEntries(
            Map.entry("pdf", "application/pdf"),
            Map.entry("properties", "text/x-java-properties"),
            Map.entry("html", "text/html"),
            Map.entry("vtt", "text/vtt"),
            Map.entry("csv", "text/csv"),
            Map.entry("msg", "application/vnd.ms-outlook"),
            Map.entry("markdown", "text/markdown"),
            Map.entry("eml", "message/rfc822"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry(
                "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("doc", "application/msword"),
            Map.entry("txt", "text/plain"),
            Map.entry(
                "pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("mdx", "text/markdown"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("odt", "application/vnd.oasis.opendocument.text"),
            Map.entry("md", "text/markdown"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("xml", "application/xml"),
            Map.entry("epub", "application/epub+zip"),
            Map.entry("htm", "text/html"))
        .forEach(
            (extension, mime) -> {
              assertDoesNotThrow(
                  () ->
                      TextParser.validateMetadata(
                          "样本." + extension.toUpperCase(java.util.Locale.ROOT), mime),
                  extension);
              assertDoesNotThrow(
                  () -> TextParser.validateMetadata("样本." + extension, "application/octet-stream"),
                  extension);
            });
  }

  @Test
  void extractsTextFormatsWithoutExecutingMdxOrChangingOldMarkdownRevision() {
    for (String extension : new String[] {"properties", "vtt", "csv", "markdown", "mdx"}) {
      var parsed =
          parser.parse(
              "sample." + extension,
              "application/octet-stream",
              "合成灯塔：原文\n<Widget />\n预算,48600".getBytes(StandardCharsets.UTF_8));
      assertTrue(parsed.pages().getFirst().text().contains("预算,48600"));
      TextParserTest.assertLocators(parsed);
    }
    assertEquals("java-text-parser-v2-monotonic-codepoints", TextParser.REVISION);
  }

  @Test
  void extractsReadableHtmlAndXmlWithoutScriptsOrExternalEntities() {
    var html =
        parser.parse(
            "sample.html",
            "text/html",
            "<html><head><script>NOT_DOCUMENT_BODY</script></head><body><h1>合成灯塔</h1><table><tr><td>预算</td><td>48600</td></tr></table></body></html>"
                .getBytes(StandardCharsets.UTF_8));
    assertTrue(html.pages().getFirst().text().contains("合成灯塔"));
    assertTrue(html.pages().getFirst().text().contains("48600"));
    assertFalse(html.pages().getFirst().text().contains("NOT_DOCUMENT_BODY"));
    var xml =
        parser.parse(
            "sample.xml",
            "application/xml",
            "<document><title>合成灯塔</title><budget>48600</budget></document>"
                .getBytes(StandardCharsets.UTF_8));
    assertTrue(xml.pages().getFirst().text().contains("合成灯塔"));
    assertTrue(xml.pages().getFirst().text().contains("48600"));
    String config =
        parser
            .parse(
                "resources.xml",
                "application/xml",
                "<resource cpu=\"4\" memory=\"8\"/>".getBytes(StandardCharsets.UTF_8))
            .pages()
            .getFirst()
            .text();
    assertTrue(config.contains("resource"));
    assertTrue(config.contains("cpu=\"4\""));
    assertTrue(config.contains("memory=\"8\""));
    assertThrows(
        TextParser.Failure.class,
        () ->
            parser.parse(
                "sample.xml",
                "application/xml",
                "<!DOCTYPE x [<!ENTITY external SYSTEM 'file:///etc/passwd'>]><x>&external;</x>"
                    .getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void propertiesUnicodeIsSearchableWithoutDroppingCommentsOrLiteralEscapes() {
    String raw = "# Configuration notes\nproject=\\u706f\\u5854\nliteral=\\\\u706f\n";
    String parsed =
        parser
            .parse(
                "sample.properties", "text/x-java-properties", raw.getBytes(StandardCharsets.UTF_8))
            .pages()
            .getFirst()
            .text();
    assertTrue(parsed.contains("project=灯塔"));
    assertTrue(parsed.contains("# Configuration notes"));
    assertTrue(parsed.contains("literal=\\\\u706f"));
  }
}
