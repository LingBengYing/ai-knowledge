package com.evidence.rag.tool.parser;

import com.evidence.rag.model.domain.ImageDimensions;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Bounded PNG/JPEG metadata admission; native decoding remains in the OCR child. */
public final class ImageInput {
  public static final int MAX_BYTES = 10 * 1024 * 1024;
  public static final long MAX_PIXELS = 12_000_000;

  private ImageInput() {}

  public static boolean isImageName(String filename) {
    if (filename == null) {
      return false;
    }
    String lower = filename.toLowerCase(Locale.ROOT);
    return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
  }

  public static void validateMetadata(String filename, String mime) {
    if (!isImageName(filename)
        || filename.codePointCount(0, filename.length()) > 255
        || filename.contains("/")
        || filename.contains("\\")
        || filename.indexOf('.') < 1
        || filename
            .codePoints()
            .anyMatch(c -> Character.isISOControl(c) || c >= 0xD800 && c <= 0xDFFF)
        || !(canonicalMime(filename).equals(mime) || "application/octet-stream".equals(mime))) {
      throw invalid();
    }
  }

  public static String canonicalMime(String filename) {
    if (!isImageName(filename)) {
      throw invalid();
    }
    return filename.toLowerCase(Locale.ROOT).endsWith(".png") ? "image/png" : "image/jpeg";
  }

  public static void validateEnvelope(String filename, String mime, byte[] content) {
    validateMetadata(filename, mime);
    if (!canonicalMime(filename).equals(contentMime(content))) {
      throw invalid();
    }
    inspect(content);
  }

  public static ImageDimensions inspect(byte[] content) {
    String mime = contentMime(content);
    if ("image/png".equals(mime)) {
      rejectAnimation(content);
    }
    var readers = ImageIO.getImageReadersByMIMEType(mime);
    if (!readers.hasNext()) {
      throw invalid();
    }
    var reader = readers.next();
    try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(content))) {
      reader.setInput(input, false, true);
      int width = reader.getWidth(0), height = reader.getHeight(0);
      if (width < 1 || height < 1 || (long) width * height > MAX_PIXELS) {
        throw invalid();
      }
      return new ImageDimensions(width, height);
    } catch (IOException | IllegalArgumentException failure) {
      throw invalid();
    } finally {
      reader.dispose();
    }
  }

  private static void rejectAnimation(byte[] content) {
    for (int offset = 8; offset < content.length; ) {
      if (content.length - offset < 12) {
        throw invalid();
      }
      long size =
          ((content[offset] & 0xffL) << 24)
              | ((content[offset + 1] & 0xffL) << 16)
              | ((content[offset + 2] & 0xffL) << 8)
              | (content[offset + 3] & 0xffL);
      if (size > content.length - offset - 12
          || content[offset + 4] == 'a'
              && content[offset + 5] == 'c'
              && content[offset + 6] == 'T'
              && content[offset + 7] == 'L') {
        throw invalid();
      }
      offset += 12 + (int) size;
    }
  }

  private static String contentMime(byte[] content) {
    if (content == null || content.length < 8 || content.length > MAX_BYTES) {
      throw invalid();
    }
    if (content[0] == (byte) 0x89
        && content[1] == 'P'
        && content[2] == 'N'
        && content[3] == 'G'
        && content[4] == 13
        && content[5] == 10
        && content[6] == 26
        && content[7] == 10) {
      return "image/png";
    }
    if (content[0] == (byte) 0xff && content[1] == (byte) 0xd8 && content[2] == (byte) 0xff) {
      return "image/jpeg";
    }
    throw invalid();
  }

  private static TextParser.Failure invalid() {
    return new TextParser.Failure("unsupported_document");
  }
}
