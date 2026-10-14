package com.evidence.rag.worker.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.ocr.PageOcr;
import com.evidence.rag.model.domain.TextPage;
import com.evidence.rag.tool.parser.ImageInput;
import com.evidence.rag.tool.parser.TextParser;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

/** Scanned pages are recognized concurrently; page order and whole-document failure hold. */
class PdfParallelOcrCompilerTest {
  @Test
  void scannedPagesAreRecognizedInParallelAndKeepPageOrder() throws Exception {
    var inFlight = new AtomicInteger();
    var peak = new AtomicInteger();
    PageOcr ocr =
        image -> {
          peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
          try {
            int page = page(image.content());
            // Odd pages answer slower, so completion order differs from page order.
            Thread.sleep(page % 2 == 1 ? 300 : 30);
            return "第" + page + "页";
          } catch (InterruptedException stopped) {
            throw new IllegalStateException(stopped);
          } finally {
            inFlight.decrementAndGet();
          }
        };
    var parsed = new PdfOcrCompiler(ocr, 4).parse("scan.pdf", "application/pdf", pdf(7));
    assertEquals(
        IntStream.rangeClosed(1, 7).mapToObj(i -> new TextPage(i, "第" + i + "页")).toList(),
        parsed.pages());
    assertTrue(peak.get() > 1 && peak.get() <= 4, "peak " + peak.get());
  }

  @Test
  void transientPageFailuresAreRetriedButPermanentOnesFailTheDocument() throws Exception {
    var calls = new AtomicInteger();
    var parsed =
        new PdfOcrCompiler(
                image -> {
                  if (calls.incrementAndGet() == 1) {
                    throw new PageOcr.Failure(true);
                  }
                  return "识别正文";
                },
                4)
            .parse("retry.pdf", "application/pdf", pdf(1));
    assertEquals(List.of(new TextPage(1, "识别正文")), parsed.pages());
    assertEquals(2, calls.get());

    calls.set(0);
    var permanent =
        assertThrows(
            TextParser.Failure.class,
            () ->
                new PdfOcrCompiler(
                        image -> {
                          calls.incrementAndGet();
                          throw new PageOcr.Failure();
                        },
                        4)
                    .parse("permanent.pdf", "application/pdf", pdf(1)));
    assertEquals("parser_failed", permanent.code());
    assertEquals(1, calls.get());

    calls.set(0);
    var exhausted =
        assertThrows(
            TextParser.Failure.class,
            () ->
                new PdfOcrCompiler(
                        image -> {
                          calls.incrementAndGet();
                          throw new PageOcr.Failure(true);
                        },
                        1)
                    .parse("exhausted.pdf", "application/pdf", pdf(1)));
    assertEquals("parser_failed", exhausted.code());
    assertEquals(PdfOcrCompiler.ATTEMPTS, calls.get());
  }

  @Test
  void oneFailedPageAmongParallelPagesFailsTheWholeDocument() throws Exception {
    var failure =
        assertThrows(
            TextParser.Failure.class,
            () ->
                new PdfOcrCompiler(
                        image -> {
                          if (page(image.content()) == 3) {
                            throw new PageOcr.Failure();
                          }
                          return "正文";
                        },
                        4)
                    .parse("tail.pdf", "application/pdf", pdf(5)));
    assertEquals("parser_failed", failure.code());
    assertThrows(IllegalArgumentException.class, () -> new PdfOcrCompiler(image -> "", 0));
    assertThrows(IllegalArgumentException.class, () -> new PdfOcrCompiler(image -> "", 9));
  }

  /** Each blank page has a distinct width, so its rendered image identifies the page number. */
  private static int page(byte[] png) {
    return (ImageInput.inspect(png).width() - 200) / 20 + 1;
  }

  private static byte[] pdf(int pages) throws Exception {
    try (var pdf = new PDDocument();
        var bytes = new ByteArrayOutputStream()) {
      for (int i = 0; i < pages; i++) {
        pdf.addPage(new PDPage(new PDRectangle(100 + 10 * i, 200)));
      }
      pdf.save(bytes);
      return bytes.toByteArray();
    }
  }
}
