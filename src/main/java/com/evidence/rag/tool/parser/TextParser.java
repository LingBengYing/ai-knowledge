package com.evidence.rag.tool.parser;

import com.evidence.rag.model.domain.DocumentFormat;
import com.evidence.rag.model.domain.ParsedText;
import com.evidence.rag.model.domain.TextPage;
import com.evidence.rag.model.domain.TextSegment;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Untrusted text extraction. All locators are Unicode code point offsets within normalized pages.
 */
public final class TextParser {
  public static final String REVISION = "java-text-parser-v2-monotonic-codepoints";
  public static final String DOCUMENT_REVISION = "java-document-parser-v1-tika-3.3.2-text-units";
  public static final int MAX_BYTES = 20 * 1024 * 1024;

  public ParsedText parse(String filename, String mime, byte[] content) {
    validateEnvelope(filename, mime, content);
    var format = DocumentFormat.fromFilename(filename).orElseThrow(TextParser::invalid);
    if (!format.legacy() && !format.plainText()) {
      return compilePages(
          List.of(new TextPage(1, new LocalDocumentParser().extract(format, content))));
    }
    try {
      var pages = new ArrayList<TextPage>();
      if (filename.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
        try (var pdf = Loader.loadPDF(content)) {
          if (pdf.isEncrypted() || pdf.getNumberOfPages() > 500) {
            throw invalid();
          }
          var extractor = new PDFTextStripper();
          long characters = 0;
          for (int i = 1; i <= pdf.getNumberOfPages(); i++) {
            extractor.setStartPage(i);
            extractor.setEndPage(i);
            String text = normalize(extractor.getText(pdf));
            characters += text.codePointCount(0, text.length());
            if (characters > 1_000_000) {
              throw invalid();
            }
            pages.add(new TextPage(i, text));
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
        if (text.startsWith("\uFEFF")) {
          text = text.substring(1);
        }
        if (format == DocumentFormat.PROPERTIES) {
          text = decodePropertyUnicode(text);
        }
        text = normalize(text);
        if (text.codePointCount(0, text.length()) > 1_000_000) {
          throw invalid();
        }
        pages.add(new TextPage(1, text));
      }
      return compilePages(pages);
    } catch (IOException | IllegalArgumentException error) {
      throw invalid();
    }
  }

  /** Compiles all original page numbers with the same normalization and source offsets. */
  public static ParsedText compilePages(List<TextPage> input) {
    if (input == null || input.isEmpty() || input.size() > 500) {
      throw invalid();
    }
    var pages = new ArrayList<TextPage>(input.size());
    var segments = new ArrayList<TextSegment>();
    long points = 0;
    for (var page : input) {
      if (Thread.currentThread().isInterrupted()) {
        throw new Failure("parser_interrupted");
      }
      if (page == null || page.number() != pages.size() + 1 || page.text() == null) {
        throw invalid();
      }
      String text = normalize(page.text());
      points += text.codePointCount(0, text.length());
      if (points > 1_000_000) {
        throw invalid();
      }
      var normalized = new TextPage(page.number(), text);
      pages.add(normalized);
      chunk(normalized, segments);
      if (segments.size() > 4096) {
        throw invalid();
      }
    }
    if (segments.isEmpty()) {
      throw invalid();
    }
    return new ParsedText(pages, segments);
  }

  public static void validateEnvelope(String filename, String mime, byte[] content) {
    validateMetadata(filename, mime);
    if (content == null || content.length == 0 || content.length > MAX_BYTES) {
      throw invalid();
    }
    boolean pdf = filename.toLowerCase(Locale.ROOT).endsWith(".pdf");
    boolean signature =
        content.length >= 4
            && content[0] == '%'
            && content[1] == 'P'
            && content[2] == 'D'
            && content[3] == 'F';
    if (pdf != signature) {
      throw invalid();
    }
    var format = DocumentFormat.fromFilename(filename).orElseThrow(TextParser::invalid);
    if (format.ole()
        && !startsWith(
            content,
            new byte[] {
              (byte) 0xD0,
              (byte) 0xCF,
              0x11,
              (byte) 0xE0,
              (byte) 0xA1,
              (byte) 0xB1,
              0x1A,
              (byte) 0xE1
            })) {
      throw invalid();
    }
    if (format.zip() && !startsWith(content, new byte[] {'P', 'K', 3, 4})) {
      throw invalid();
    }
  }

  /** Validates names and declared media before any file bytes are available. */
  public static void validateMetadata(String filename, String mime) {
    if (filename == null
        || mime == null
        || filename.codePointCount(0, filename.length()) > 255
        || filename.contains("/")
        || filename.contains("\\")
        || filename.indexOf('.') < 1
        || filename
            .codePoints()
            .anyMatch(c -> Character.isISOControl(c) || c >= 0xD800 && c <= 0xDFFF)) {
      throw invalid();
    }
    var format = DocumentFormat.fromFilename(filename).orElseThrow(TextParser::invalid);
    if (!format.accepts(mime)) {
      throw invalid();
    }
  }

  public static String revisionFor(String filename) {
    return DocumentFormat.fromFilename(filename).orElseThrow(TextParser::invalid).legacy()
        ? REVISION
        : DOCUMENT_REVISION;
  }

  /** Preserve comments/order/duplicate lines, decoding only unescaped Java Unicode sequences. */
  private static String decodePropertyUnicode(String text) {
    var decoded = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char value = text.charAt(i);
      if (value != '\\' || i + 1 >= text.length()) {
        decoded.append(value);
        continue;
      }
      char next = text.charAt(++i);
      if (next != 'u') {
        decoded.append('\\').append(next);
        continue;
      }
      if (i + 4 >= text.length()) throw invalid();
      int point = 0;
      for (int count = 0; count < 4; count++) {
        int hex = Character.digit(text.charAt(++i), 16);
        if (hex < 0) throw invalid();
        point = point * 16 + hex;
      }
      decoded.append((char) point);
    }
    return decoded.toString();
  }

  private static boolean startsWith(byte[] content, byte[] signature) {
    if (content.length < signature.length) return false;
    for (int i = 0; i < signature.length; i++) if (content[i] != signature[i]) return false;
    return true;
  }

  private static String normalize(String text) {
    String result = text.replace("\r\n", "\n").replace('\r', '\n');
    if (result
        .codePoints()
        .anyMatch(
            c ->
                Character.isISOControl(c) && c != '\n' && c != '\t' && c != '\f'
                    || c >= 0xD800 && c <= 0xDFFF)) {
      throw invalid();
    }
    return result;
  }

  private static void chunk(TextPage page, List<TextSegment> output) {
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
      while (left < right && space(points[left])) {
        left++;
      }
      while (right > left && space(points[right - 1])) {
        right--;
      }
      if (right > left) {
        output.add(
            new TextSegment(
                output.size(), page.number(), left, right, new String(points, left, right - left)));
      }
      if (end >= points.length) {
        break;
      }
      // Trimming can move left into the overlap. Never emit the same evidence start twice;
      // the previous segment already covers left, while empty windows must not skip new text.
      int next = Math.max(end - 120, right > left ? left + 1 : start + 1);
      for (int i = next; i < end; i++) {
        if (points[i] == '\n') {
          next = i + 1;
          break;
        }
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
