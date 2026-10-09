package com.evidence.rag.worker.parser;

import com.evidence.rag.client.ocr.PageOcr;
import com.evidence.rag.model.domain.ParsedText;
import com.evidence.rag.model.domain.TextPage;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.tool.parser.ImageInput;
import com.evidence.rag.tool.parser.TextParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

/** Original page text is preferred; image-bearing and textless pages retain whole-page OCR. */
final class PdfOcrCompiler {
  private final PageOcr ocr;

  PdfOcrCompiler(ImageOcr ocr) {
    this(
        (PageOcr)
            image ->
                ocr.read(image).map(value -> value.text().pages().getFirst().text()).orElse(""));
  }

  PdfOcrCompiler(PageOcr ocr) {
    this.ocr = ocr;
  }

  ParsedText parse(String filename, String mime, byte[] content) {
    TextParser.validateEnvelope(filename, mime, content);
    if (!filename.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
      throw invalid();
    }
    try (var pdf = Loader.loadPDF(content)) {
      if (pdf.isEncrypted() || pdf.getNumberOfPages() < 1 || pdf.getNumberOfPages() > 500) {
        throw invalid();
      }
      var renderer = new PDFRenderer(pdf);
      renderer.setSubsamplingAllowed(true);
      var extractor = new PageTextExtractor();
      var pages = new ArrayList<TextPage>();
      long points = 0;
      for (int i = 0; i < pdf.getNumberOfPages(); i++) {
        interrupted();
        var page = pdf.getPage(i);
        var box = page.getCropBox();
        double width = box.getWidth(), height = box.getHeight();
        float scale = 2f * page.getUserUnit();
        if (!Double.isFinite(width)
            || !Double.isFinite(height)
            || width <= 0
            || height <= 0
            || !Float.isFinite(scale)
            || scale <= 0
            || page.getRotation() % 90 != 0
            || Math.floor(width * scale) < 1
            || Math.floor(height * scale) < 1
            || Math.ceil(width * scale) * Math.ceil(height * scale) > ImageInput.MAX_PIXELS) {
          throw invalid();
        }
        extractor.paintedImage = false;
        extractor.setStartPage(i + 1);
        extractor.setEndPage(i + 1);
        String text = extractor.getText(pdf);
        interrupted();
        if (extractor.paintedImage || !hasUsableText(text)) {
          text = readPage(renderer, i, scale);
        }
        points += text.codePointCount(0, text.length());
        if (points > 1_000_000) {
          throw invalid();
        }
        pages.add(new TextPage(i + 1, text));
      }
      interrupted();
      return TextParser.compilePages(pages);
    } catch (PageOcr.Failure failure) {
      throw new TextParser.Failure("parser_failed");
    } catch (IOException | IllegalArgumentException failure) {
      throw invalid();
    }
  }

  private String readPage(PDFRenderer renderer, int page, float scale) throws IOException {
    var image = renderer.renderImage(page, scale, ImageType.RGB);
    byte[] png;
    try {
      if ((long) image.getWidth() * image.getHeight() > ImageInput.MAX_PIXELS) {
        throw invalid();
      }
      var bytes = new ByteArrayOutputStream();
      var bounded =
          new OutputStream() {
            @Override
            public void write(int value) throws IOException {
              if (bytes.size() >= ImageInput.MAX_BYTES) {
                throw new IOException();
              }
              bytes.write(value);
            }

            @Override
            public void write(byte[] value, int offset, int length) throws IOException {
              if (length > ImageInput.MAX_BYTES - bytes.size()) {
                throw new IOException();
              }
              bytes.write(value, offset, length);
            }
          };
      if (!ImageIO.write(image, "png", bounded)) {
        throw invalid();
      }
      png = bytes.toByteArray();
    } finally {
      image.flush();
    }
    interrupted();
    return ocr.read(new VisualImage("image/png", png));
  }

  private static boolean hasUsableText(String text) {
    return text.codePoints()
        .anyMatch(
            point ->
                !Character.isWhitespace(point)
                    && !Character.isSpaceChar(point)
                    && !Character.isISOControl(point)
                    && point != 0xFFFD);
  }

  /** Tracks drawn images, including form/inline images, rather than unused page resources. */
  private static final class PageTextExtractor extends PDFTextStripper {
    private boolean paintedImage;

    @Override
    protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
      interrupted();
      if ("BI".equals(operator.getName())
          || "Do".equals(operator.getName())
              && !operands.isEmpty()
              && operands.getFirst() instanceof COSName name
              && getResources() != null
              && getResources().getXObject(name) instanceof PDImageXObject) {
        paintedImage = true;
      }
      super.processOperator(operator, operands);
    }
  }

  private static void interrupted() {
    if (Thread.currentThread().isInterrupted()) {
      throw new TextParser.Failure("parser_interrupted");
    }
  }

  private static TextParser.Failure invalid() {
    return new TextParser.Failure("unsupported_document");
  }
}
