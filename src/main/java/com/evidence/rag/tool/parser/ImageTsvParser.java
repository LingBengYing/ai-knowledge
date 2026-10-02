package com.evidence.rag.tool.parser;

import com.evidence.rag.model.domain.ImageDimensions;
import com.evidence.rag.model.domain.ImageTextRegion;
import com.evidence.rag.model.domain.ParsedImage;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Optional;

/** One bounded Tesseract TSV transcript, preserving every word and its original pixel box. */
public final class ImageTsvParser {
  public static final int MAX_BYTES = 4 * 1024 * 1024;
  public static final int MAX_WORDS = 50_000;
  private static final String HEADER =
      "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext";

  private ImageTsvParser() {}

  public static ParsedImage parse(byte[] tsv, ImageDimensions original) {
    return parseOptional(tsv, original).orElseThrow(ImageTsvParser::invalid);
  }

  public static Optional<ParsedImage> parseOptional(byte[] tsv, ImageDimensions original) {
    if (tsv == null
        || tsv.length == 0
        || tsv.length > MAX_BYTES
        || original == null
        || original.width() < 1
        || original.height() < 1
        || (long) original.width() * original.height() > ImageInput.MAX_PIXELS) {
      throw invalid();
    }
    try {
      String output =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(tsv))
              .toString();
      if (!output.endsWith("\n")) {
        throw invalid();
      }
      int offset = output.indexOf('\n');
      if (!HEADER.equals(line(output, 0, offset))) {
        throw invalid();
      }
      offset++;
      var regions = new ArrayList<ImageTextRegion>();
      var transcript = new StringBuilder();
      Row previous = null;
      int points = 0;
      int rows = 0;
      while (offset < output.length()) {
        if (Thread.currentThread().isInterrupted()) {
          throw new TextParser.Failure("parser_interrupted");
        }
        int end = output.indexOf('\n', offset);
        if (++rows > MAX_WORDS * 4 + 1) {
          throw invalid();
        }
        Row row = row(line(output, offset, end), original);
        offset = end + 1;
        if (rows == 1) {
          if (row.level() != 1
              || row.left() != 0
              || row.top() != 0
              || row.width() != original.width()
              || row.height() != original.height()) {
            throw invalid();
          }
        } else if (row.level() == 1) {
          throw invalid();
        }
        if (row.level() != 5) {
          continue;
        }
        if (regions.size() == MAX_WORDS || previous != null && compare(previous, row) >= 0) {
          throw invalid();
        }
        if (previous != null) {
          boolean sameLine =
              previous.block() == row.block()
                  && previous.paragraph() == row.paragraph()
                  && previous.line() == row.line();
          transcript.append(sameLine ? ' ' : '\n');
          points++;
        }
        int start = points;
        points += row.text().codePointCount(0, row.text().length());
        if (points >= 1_000_000) {
          throw invalid();
        }
        transcript.append(row.text());
        regions.add(
            new ImageTextRegion(
                start,
                points,
                row.left(),
                row.top(),
                row.left() + row.width(),
                row.top() + row.height()));
        previous = row;
      }
      if (rows == 0) {
        throw invalid();
      }
      if (regions.isEmpty()) {
        return Optional.empty();
      }
      transcript.append('\n');
      var text =
          new TextParser()
              .parse(
                  "ocr.txt", "text/plain", transcript.toString().getBytes(StandardCharsets.UTF_8));
      if (text.pages().size() != 1 || !text.pages().getFirst().text().contentEquals(transcript)) {
        throw invalid();
      }
      return Optional.of(new ParsedImage(text, original, regions));
    } catch (CharacterCodingException | IllegalArgumentException failure) {
      throw invalid();
    } catch (TextParser.Failure failure) {
      if (failure.code().equals("parser_interrupted")) {
        throw failure;
      }
      throw invalid();
    }
  }

  private static String line(String output, int start, int end) {
    if (end < start) {
      throw invalid();
    }
    return output.substring(start, end > start && output.charAt(end - 1) == '\r' ? end - 1 : end);
  }

  private static Row row(String line, ImageDimensions original) {
    String[] fields = line.split("\t", -1);
    if (fields.length != 12) {
      throw invalid();
    }
    int[] numbers = new int[10];
    for (int i = 0; i < numbers.length; i++) {
      numbers[i] = Integer.parseInt(fields[i]);
      if (numbers[i] < 0) {
        throw invalid();
      }
    }
    var row =
        new Row(
            numbers[0],
            numbers[1],
            numbers[2],
            numbers[3],
            numbers[4],
            numbers[5],
            numbers[6],
            numbers[7],
            numbers[8],
            numbers[9],
            fields[11]);
    double confidence = Double.parseDouble(fields[10]);
    if (row.level() < 1
        || row.level() > 5
        || row.page() != 1
        || !Double.isFinite(confidence)
        || confidence < -1
        || confidence > 100
        || row.width() < 1
        || row.height() < 1
        || (long) row.left() + row.width() > original.width()
        || (long) row.top() + row.height() > original.height()) {
      throw invalid();
    }
    for (int i = 2; i <= 5; i++) {
      if (i <= row.level() ? numbers[i] < 1 : numbers[i] != 0) {
        throw invalid();
      }
    }
    if (row.level() == 5) {
      if (row.text().isBlank() || row.text().codePoints().anyMatch(Character::isISOControl)) {
        throw invalid();
      }
    } else if (!row.text().isEmpty() || confidence != -1) {
      throw invalid();
    }
    return row;
  }

  private static int compare(Row previous, Row current) {
    int order = Integer.compare(previous.block(), current.block());
    if (order == 0) {
      order = Integer.compare(previous.paragraph(), current.paragraph());
    }
    if (order == 0) {
      order = Integer.compare(previous.line(), current.line());
    }
    return order == 0 ? Integer.compare(previous.word(), current.word()) : order;
  }

  private record Row(
      int level,
      int page,
      int block,
      int paragraph,
      int line,
      int word,
      int left,
      int top,
      int width,
      int height,
      String text) {
    @Override
    public String toString() {
      return "Row[redacted]";
    }
  }

  private static TextParser.Failure invalid() {
    return new TextParser.Failure("parser_invalid_output");
  }
}
