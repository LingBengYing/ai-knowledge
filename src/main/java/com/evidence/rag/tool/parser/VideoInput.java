package com.evidence.rag.tool.parser;

import java.util.Locale;

/** Video container-envelope admission only; stream identity and actual times require the worker. */
public final class VideoInput {
  public static final int MAX_BYTES = 20 * 1024 * 1024;

  private VideoInput() {}

  public static boolean isVideoName(String filename) {
    if (filename == null) {
      return false;
    }
    String lower = filename.toLowerCase(Locale.ROOT);
    return lower.endsWith(".mp4")
        || lower.endsWith(".mov")
        || lower.endsWith(".webm")
        || lower.endsWith(".mkv");
  }

  public static String canonicalMime(String filename) {
    if (!isVideoName(filename)) {
      throw invalid();
    }
    return switch (filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)) {
      case "mp4" -> "video/mp4";
      case "mov" -> "video/quicktime";
      case "webm" -> "video/webm";
      case "mkv" -> "video/x-matroska";
      default -> throw invalid();
    };
  }

  public static void validateMetadata(String filename, String mime) {
    if (!isVideoName(filename)
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

  public static void validateEnvelope(String filename, String mime, byte[] content) {
    validateMetadata(filename, mime);
    if (content == null || content.length < 8 || content.length > MAX_BYTES) {
      throw invalid();
    }
    boolean matches =
        switch (canonicalMime(filename)) {
          case "video/mp4", "video/quicktime" ->
              content.length >= 16 && starts(content, 4, 'f', 't', 'y', 'p');
          case "video/webm", "video/x-matroska" -> starts(content, 0, 0x1a, 0x45, 0xdf, 0xa3);
          default -> false;
        };
    if (!matches) {
      throw invalid();
    }
  }

  private static boolean starts(byte[] content, int offset, int... signature) {
    if (offset > content.length - signature.length) {
      return false;
    }
    for (int index = 0; index < signature.length; index++) {
      if ((content[offset + index] & 0xff) != signature[index]) {
        return false;
      }
    }
    return true;
  }

  private static TextParser.Failure invalid() {
    return new TextParser.Failure("unsupported_document");
  }
}
