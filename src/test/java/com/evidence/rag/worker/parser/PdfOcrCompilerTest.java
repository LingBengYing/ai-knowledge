package com.evidence.rag.worker.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.ImageDimensions;
import com.evidence.rag.model.domain.ImageTextRegion;
import com.evidence.rag.model.domain.ParsedImage;
import com.evidence.rag.model.domain.TextPage;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.tool.parser.ImageInput;
import com.evidence.rag.tool.parser.TextParser;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;

/** Real PDF rendering with an OCR protocol fixture; this does not certify recognition quality. */
class PdfOcrCompilerTest {
  @Test
  void everyPageIsRenderedAtTheFixedProfileAndKeepsBlankPageAndCodePointLocators()
      throws Exception {
    var calls = new AtomicInteger();
    var images = new ArrayList<VisualImage>();
    String longText = "😀预算650元。".repeat(190) + "\r\n";
    var parsed =
        new PdfOcrCompiler(
                ocr(
                    image -> {
                      images.add(image);
                      assertEquals("image/png", image.mediaType());
                      assertEquals(
                          new ImageDimensions(1224, 1584), ImageInput.inspect(image.content()));
                      int page = calls.incrementAndGet();
                      if (page == 1 || page == 3) {
                        return Optional.empty();
                      }
                      return Optional.of(transcript(image, page == 2 ? longText : "尾页470元。"));
                    }))
            .parse("synthetic.pdf", "application/pdf", pdf(4, false, null));
    assertEquals(4, calls.get());
    assertEquals(4, images.size());
    assertEquals(
        List.of(
            new TextPage(1, ""),
            new TextPage(2, longText.replace("\r\n", "\n")),
            new TextPage(3, ""),
            new TextPage(4, "尾页470元。")),
        parsed.pages());
    assertTrue(parsed.segments().stream().filter(segment -> segment.page() == 2).count() > 1);
    for (var segment : parsed.segments()) {
      var page = parsed.pages().get(segment.page() - 1).text();
      assertEquals(
          segment.text(),
          page.substring(
              page.offsetByCodePoints(0, segment.start()),
              page.offsetByCodePoints(0, segment.end())));
      assertEquals(
          segment.end() - segment.start(),
          segment.text().codePointCount(0, segment.text().length()));
    }
    assertEquals(2, parsed.segments().getFirst().page());
    assertEquals(4, parsed.segments().getLast().page());
    assertEquals("尾页470元。", parsed.segments().getLast().text());
  }

  @Test
  void allBlankPagesAndFailedTailCannotProducePartialParsedText() throws Exception {
    var calls = new AtomicInteger();
    assertThrows(
        TextParser.Failure.class,
        () ->
            new PdfOcrCompiler(
                    ocr(
                        image -> {
                          calls.incrementAndGet();
                          return Optional.empty();
                        }))
                .parse("blank.pdf", "application/pdf", pdf(3, false, null)));
    assertEquals(3, calls.get());
    calls.set(0);
    var failure =
        assertThrows(
            TextParser.Failure.class,
            () ->
                new PdfOcrCompiler(
                        ocr(
                            image -> {
                              if (calls.incrementAndGet() == 3) {
                                throw new TextParser.Failure("parser_failed");
                              }
                              return Optional.of(transcript(image, "content"));
                            }))
                    .parse("tail.pdf", "application/pdf", pdf(3, false, null)));
    assertEquals("parser_failed", failure.code());
    assertEquals(3, calls.get());
  }

  @Test
  void encryptedTooManyOrInvalidSizedPagesAreRejectedBeforeNativeOcr() throws Exception {
    var calls = new AtomicInteger();
    var parser =
        new PdfOcrCompiler(
            ocr(
                image -> {
                  calls.incrementAndGet();
                  return Optional.empty();
                }));
    for (byte[] bytes :
        List.of(
            pdf(1, true, null),
            pdf(501, false, null),
            pdf(1, false, new PDRectangle(10000, 10000)))) {
      assertThrows(
          TextParser.Failure.class, () -> parser.parse("bad.pdf", "application/pdf", bytes));
    }
    assertEquals(0, calls.get());
  }

  @Test
  void pageCompilationKeepsOldTextBehaviorAndRejectsInvalidOrIncompleteInputs() {
    var text =
        new TextParser()
            .parse("notes.txt", "text/plain", "😀text\r\n".getBytes(StandardCharsets.UTF_8));
    assertEquals(text, TextParser.compilePages(List.of(new TextPage(1, "😀text\r\n"))));
    for (var pages :
        List.of(
            List.<TextPage>of(),
            List.of(new TextPage(2, "x")),
            List.of(new TextPage(1, " ")),
            List.of(new TextPage(1, "x\u0000")),
            List.of(new TextPage(1, "字".repeat(1_000_001))))) {
      assertThrows(TextParser.Failure.class, () -> TextParser.compilePages(pages));
    }
  }

  private static ImageOcr ocr(Function<VisualImage, Optional<ParsedImage>> read) {
    return new ImageOcr() {
      @Override
      public String revision() {
        return "explicit-fixture";
      }

      @Override
      public Optional<ParsedImage> read(VisualImage image) {
        return read.apply(image);
      }
    };
  }

  private static ParsedImage transcript(VisualImage image, String value) {
    var text =
        new TextParser().parse("ocr.txt", "text/plain", value.getBytes(StandardCharsets.UTF_8));
    var dimensions = ImageInput.inspect(image.content());
    int[] points = text.pages().getFirst().text().codePoints().toArray();
    var regions = new ArrayList<ImageTextRegion>();
    int start = 0;
    while (start < points.length) {
      if (Character.isWhitespace(points[start]) || Character.isSpaceChar(points[start])) {
        start++;
        continue;
      }
      int end = start + 1;
      while (end < points.length
          && !Character.isWhitespace(points[end])
          && !Character.isSpaceChar(points[end])) {
        end++;
      }
      regions.add(new ImageTextRegion(start, end, 0, 0, dimensions.width(), dimensions.height()));
      start = end;
    }
    return new ParsedImage(text, dimensions, regions);
  }

  static byte[] pdf(int pages, boolean encrypted, PDRectangle box) throws Exception {
    try (var pdf = new PDDocument();
        var bytes = new ByteArrayOutputStream()) {
      for (int i = 0; i < pages; i++) {
        pdf.addPage(box == null ? new PDPage() : new PDPage(box));
      }
      if (encrypted) {
        pdf.protect(new StandardProtectionPolicy("owner", "user", new AccessPermission()));
      }
      pdf.save(bytes);
      return bytes.toByteArray();
    }
  }
}
