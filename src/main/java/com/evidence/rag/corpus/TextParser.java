package com.evidence.rag.corpus;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Untrusted text extraction. All locators are Unicode code point offsets within normalized pages.
 */
public final class TextParser {
  public static final String REVISION = "java-text-parser-v1-codepoints";
  public static final int MAX_BYTES = 20 * 1024 * 1024;

  public record Page(int number, String text) {}

  public record Segment(int ordinal, int page, int start, int end, String text) {}

  public record Parsed(List<Page> pages, List<Segment> segments) {
    public Parsed {
      pages = List.copyOf(pages);
      segments = List.copyOf(segments);
    }
  }

  public Parsed parse(String filename, String mime, byte[] content) {
    validateEnvelope(filename, mime, content);
    try {
      var pages = new ArrayList<Page>();
      if (filename.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
        try (var pdf = Loader.loadPDF(content)) {
          if (pdf.isEncrypted() || pdf.getNumberOfPages() > 500) throw invalid();
          var extractor = new PDFTextStripper();
          long characters = 0;
          for (int i = 1; i <= pdf.getNumberOfPages(); i++) {
            extractor.setStartPage(i);
            extractor.setEndPage(i);
            String text = normalize(extractor.getText(pdf));
            characters += text.codePointCount(0, text.length());
            if (characters > 1_000_000) throw invalid();
            pages.add(new Page(i, text));
          }
        }
      } else {
        String text =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content))
                .toString();
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        text = normalize(text);
        if (text.codePointCount(0, text.length()) > 1_000_000) throw invalid();
        pages.add(new Page(1, text));
      }
      var segments = new ArrayList<Segment>();
      for (var page : pages) chunk(page, segments);
      if (segments.isEmpty()) throw invalid();
      return new Parsed(pages, segments);
    } catch (IOException | IllegalArgumentException error) {
      throw invalid();
    }
  }

  public static void validateEnvelope(String filename, String mime, byte[] content) {
    if (filename == null
        || mime == null
        || content == null
        || content.length == 0
        || content.length > MAX_BYTES
        || filename.codePointCount(0, filename.length()) > 255
        || filename.contains("/")
        || filename.contains("\\")
        || filename.indexOf('.') < 1
        || filename
            .codePoints()
            .anyMatch(c -> Character.isISOControl(c) || c >= 0xD800 && c <= 0xDFFF))
      throw invalid();
    String lower = filename.toLowerCase(Locale.ROOT);
    boolean pdf = lower.endsWith(".pdf");
    if (!(pdf || lower.endsWith(".txt") || lower.endsWith(".md"))) throw invalid();
    if (!(pdf
            ? Set.of("application/pdf", "application/octet-stream")
            : Set.of("text/plain", "text/markdown", "application/octet-stream"))
        .contains(mime)) throw invalid();
    boolean signature =
        content.length >= 4
            && content[0] == '%'
            && content[1] == 'P'
            && content[2] == 'D'
            && content[3] == 'F';
    if (pdf != signature) throw invalid();
  }

  private static String normalize(String text) {
    String result = text.replace("\r\n", "\n").replace('\r', '\n');
    if (result
        .codePoints()
        .anyMatch(
            c ->
                Character.isISOControl(c) && c != '\n' && c != '\t' && c != '\f'
                    || c >= 0xD800 && c <= 0xDFFF)) throw invalid();
    return result;
  }

  private static void chunk(Page page, List<Segment> output) {
    int[] points = page.text().codePoints().toArray();
    int start = 0;
    while (start < points.length) {
      int end = Math.min(start + 1200, points.length);
      if (end < points.length) {
        for (int i = end - 1; i > start + 600; i--) {
          if (points[i] == '\n' || points[i] == '。' || points[i] == '！' || points[i] == '？') {
            end = i + 1;
            break;
          }
        }
      }
      int left = start, right = end;
      while (left < right && space(points[left])) left++;
      while (right > left && space(points[right - 1])) right--;
      if (right > left)
        output.add(
            new Segment(
                output.size(), page.number(), left, right, new String(points, left, right - left)));
      if (end >= points.length) break;
      int next = Math.max(end - 120, start + 1);
      for (int i = next; i < end; i++)
        if (points[i] == '\n') {
          next = i + 1;
          break;
        }
      start = next;
    }
  }

  private static boolean space(int c) {
    return Character.isWhitespace(c) || Character.isSpaceChar(c);
  }

  private static Failure invalid() {
    return new Failure("unsupported_document");
  }

  public static final class Failure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;

    public Failure(String code) {
      super("文件无法安全解析，可能为空、格式不支持或超过处理限制。", null, false, true);
      this.code = code;
    }

    public String code() {
      return code;
    }
  }
}
