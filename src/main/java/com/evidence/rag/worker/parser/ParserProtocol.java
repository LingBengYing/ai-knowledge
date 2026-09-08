package com.evidence.rag.worker.parser;

import com.evidence.rag.model.domain.ParsedText;
import com.evidence.rag.model.domain.TextPage;
import com.evidence.rag.model.domain.TextSegment;
import com.evidence.rag.tool.parser.TextParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** Bounded parser wire format and independent parent-side validation of every source locator. */
final class ParserProtocol {
  static final int MAGIC = 0x52414750;
  static final int VERSION = 1;
  static final int MAX_OUTPUT = 16 * 1024 * 1024;
  static final int MAX_SEGMENTS = 4096;
  private static final int MAX_PAGE_BYTES = 4_000_000;

  private ParserProtocol() {}

  record Request(String filename, String mime, byte[] content) {
    @Override
    public String toString() {
      return "ParserRequest[redacted]";
    }
  }

  static Request readRequest(InputStream input) throws IOException {
    var reader = new DataInputStream(input);
    header(reader);
    String filename = readString(reader, 1020);
    String mime = readString(reader, 128);
    int size = bounded(reader.readInt(), 1, TextParser.MAX_BYTES);
    byte[] content = reader.readNBytes(size);
    if (content.length != size || reader.read() != -1) {
      throw invalid();
    }
    return new Request(filename, mime, content);
  }

  static byte[] failure() {
    return new byte[] {0x52, 0x41, 0x47, 0x50, 0, 0, 0, 1, 0, 0, 0, 1};
  }

  static void writeRequest(OutputStream output, String filename, String mime, byte[] content)
      throws IOException {
    var writer = new DataOutputStream(output);
    writer.writeInt(MAGIC);
    writer.writeInt(VERSION);
    writeString(writer, filename);
    writeString(writer, mime);
    writer.writeInt(content.length);
    writer.write(content);
    writer.flush();
  }

  static byte[] encode(ParsedText parsed) throws IOException {
    var bytes = new ByteArrayOutputStream();
    var writer =
        new DataOutputStream(
            new OutputStream() {
              @Override
              public void write(int value) throws IOException {
                if (bytes.size() >= MAX_OUTPUT) {
                  throw new IOException();
                }
                bytes.write(value);
              }

              @Override
              public void write(byte[] value, int offset, int length) throws IOException {
                if (length > MAX_OUTPUT - bytes.size()) {
                  throw new IOException();
                }
                bytes.write(value, offset, length);
              }
            });
    writer.writeInt(MAGIC);
    writer.writeInt(VERSION);
    writer.writeInt(0);
    writer.writeInt(parsed.pages().size());
    for (var page : parsed.pages()) {
      writer.writeInt(page.number());
      writeString(writer, page.text());
    }
    writer.writeInt(parsed.segments().size());
    for (var segment : parsed.segments()) {
      writer.writeInt(segment.ordinal());
      writer.writeInt(segment.page());
      writer.writeInt(segment.start());
      writer.writeInt(segment.end());
      writeString(writer, segment.text());
    }
    return bytes.toByteArray();
  }

  /** The parent treats even a successful child as untrusted, including every source locator. */
  static ParsedText decode(byte[] response) throws IOException {
    if (response.length > MAX_OUTPUT) {
      throw invalid();
    }
    var reader = new DataInputStream(new ByteArrayInputStream(response));
    header(reader);
    int status = reader.readInt();
    if (status == 1 && reader.read() == -1) {
      throw new TextParser.Failure("unsupported_document");
    }
    if (status != 0) {
      throw invalid();
    }
    int pageCount = bounded(reader.readInt(), 1, 500);
    var pages = new ArrayList<TextPage>(pageCount);
    var points = new ArrayList<int[]>(pageCount);
    int total = 0;
    for (int i = 1; i <= pageCount; i++) {
      checkInterruption();
      if (reader.readInt() != i) {
        throw invalid();
      }
      String text = readString(reader, MAX_PAGE_BYTES);
      int[] pagePoints = text.codePoints().toArray();
      total += pagePoints.length;
      if (total > 1_000_000 || !legalText(text)) {
        throw invalid();
      }
      pages.add(new TextPage(i, text));
      points.add(pagePoints);
    }
    int count = bounded(reader.readInt(), 1, MAX_SEGMENTS);
    var segments = new ArrayList<TextSegment>(count);
    int previousPage = 0, previousStart = -1, segmentTotal = 0;
    for (int i = 0; i < count; i++) {
      checkInterruption();
      if (reader.readInt() != i) {
        throw invalid();
      }
      int page = bounded(reader.readInt(), 1, pageCount);
      int start = reader.readInt(), end = reader.readInt();
      int[] pagePoints = points.get(page - 1);
      if (start < 0
          || end <= start
          || end > pagePoints.length
          || end - start > 1200
          || page < previousPage
          || page == previousPage && start <= previousStart) {
        throw invalid();
      }
      String text = readString(reader, 4800);
      segmentTotal += end - start;
      if (segmentTotal > 1_500_000
          || text.codePoints().allMatch(ParserProtocol::space)
          || !text.equals(new String(pagePoints, start, end - start))) {
        throw invalid();
      }
      segments.add(new TextSegment(i, page, start, end, text));
      previousPage = page;
      previousStart = start;
    }
    if (reader.read() != -1) {
      throw invalid();
    }
    return new ParsedText(pages, segments);
  }

  private static boolean legalText(String value) {
    return value
        .codePoints()
        .noneMatch(
            c ->
                Character.isISOControl(c) && c != '\n' && c != '\t' && c != '\f'
                    || c >= 0xD800 && c <= 0xDFFF);
  }

  private static boolean space(int value) {
    return Character.isWhitespace(value) || Character.isSpaceChar(value);
  }

  private static void checkInterruption() {
    if (Thread.currentThread().isInterrupted()) {
      throw new TextParser.Failure("parser_interrupted");
    }
  }

  private static void header(DataInputStream reader) throws IOException {
    if (reader.readInt() != MAGIC || reader.readInt() != VERSION) {
      throw invalid();
    }
  }

  private static int bounded(int value, int minimum, int maximum) {
    if (value < minimum || value > maximum) {
      throw invalid();
    }
    return value;
  }

  private static String readString(DataInputStream reader, int maximum) throws IOException {
    int size = bounded(reader.readInt(), 0, maximum);
    byte[] bytes = reader.readNBytes(size);
    if (bytes.length != size) {
      throw invalid();
    }
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString();
  }

  private static void writeString(DataOutputStream writer, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    writer.writeInt(bytes.length);
    writer.write(bytes);
  }

  private static TextParser.Failure invalid() {
    return new TextParser.Failure("parser_invalid_output");
  }
}
