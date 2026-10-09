package com.evidence.rag.worker.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.evidence.rag.model.domain.ImageTextRegion;
import com.evidence.rag.model.domain.ParsedImage;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.tool.parser.ImageInput;
import com.evidence.rag.tool.parser.TextParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDFormContentStream;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;

/** Text-layer fast path and conservative mixed-page fallback, without native OCR or models. */
class PdfTextFirstCompilerTest {
  @Test
  void longTextPdfUsesAllOriginalPagesWithoutStartingOcr() throws Exception {
    var compiler =
        new PdfOcrCompiler(ocr(image -> fail("A complete text-only page must not start OCR")));
    var parsed = compiler.parse("text.pdf", "application/pdf", pdf("text", 77));
    assertEquals(77, parsed.pages().size());
    assertEquals("Original text page 1\n", parsed.pages().getFirst().text());
    assertEquals("Original text page 77\n", parsed.pages().getLast().text());
    assertEquals(77, parsed.segments().getLast().page());
  }

  @Test
  void textImageAndTextlessVectorPagesPreserveOrderAndUseOcrOnlyWhenNeeded() throws Exception {
    var calls = new AtomicInteger();
    var compiler =
        new PdfOcrCompiler(
            ocr(
                image ->
                    Optional.of(transcript(image, "Whole page OCR " + calls.incrementAndGet()))));
    var parsed = compiler.parse("mixed.pdf", "application/pdf", pdf("text,mixed,vector,text", 4));
    assertEquals(2, calls.get());
    assertEquals(
        List.of(
            "Original text page 1\n",
            "Whole page OCR 1",
            "Whole page OCR 2",
            "Original text page 4\n"),
        parsed.pages().stream().map(page -> page.text()).toList());
    assertTrue(parsed.segments().stream().anyMatch(segment -> segment.page() == 4));
  }

  @Test
  void imageDrawnInsideAFormIsNotMistakenForATextOnlyPage() throws Exception {
    var calls = new AtomicInteger();
    var parsed =
        new PdfOcrCompiler(
                ocr(
                    image -> {
                      calls.incrementAndGet();
                      return Optional.of(transcript(image, "Text and nested image evidence"));
                    }))
            .parse("form.pdf", "application/pdf", pdf("form", 1));
    assertEquals(1, calls.get());
    assertEquals("Text and nested image evidence", parsed.pages().getFirst().text());
  }

  private static ImageOcr ocr(Function<VisualImage, Optional<ParsedImage>> read) {
    return new ImageOcr() {
      @Override
      public String revision() {
        return "text-first-fixture";
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
    return new ParsedImage(
        text,
        dimensions,
        List.of(
            new ImageTextRegion(0, value.length(), 0, 0, dimensions.width(), dimensions.height())));
  }

  private static byte[] pdf(String kinds, int pages) throws Exception {
    String[] types = kinds.split(",");
    try (var document = new PDDocument();
        var output = new ByteArrayOutputStream()) {
      for (int i = 0; i < pages; i++) {
        var page = new PDPage();
        document.addPage(page);
        String type = types[i % types.length];
        try (var content = new PDPageContentStream(document, page)) {
          if (type.equals("text") || type.equals("mixed") || type.equals("form")) {
            content.beginText();
            content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            content.newLineAtOffset(30, 700);
            content.showText("Original text page " + (i + 1));
            content.endText();
          }
          if (type.equals("mixed")) {
            var image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
            content.drawImage(LosslessFactory.createFromImage(document, image), 40, 400, 10, 10);
            image.flush();
          }
          if (type.equals("form")) {
            var form = new PDFormXObject(document);
            form.setResources(new PDResources());
            form.setBBox(new PDRectangle(100, 100));
            var image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
            try (var inner = new PDFormContentStream(form)) {
              inner.drawImage(LosslessFactory.createFromImage(document, image), 0, 0, 10, 10);
            } finally {
              image.flush();
            }
            content.drawForm(form);
          }
          if (type.equals("vector")) {
            content.addRect(40, 400, 10, 10);
            content.stroke();
          }
        }
      }
      document.save(output);
      return output.toByteArray();
    }
  }
}
