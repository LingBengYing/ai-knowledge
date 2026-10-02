package com.evidence.rag.tool.parser;

import java.util.Locale;

/** Deterministic container-envelope admission; actual stream probing belongs to the worker. */
public final class AudioInput {
  public static final int MAX_BYTES = 20 * 1024 * 1024;

  private AudioInput() {}

  public static boolean isAudioName(String filename) {
    if (filename == null) {
      return false;
    }
    String lower = filename.toLowerCase(Locale.ROOT);
    return lower.endsWith(".wav")
        || lower.endsWith(".mp3")
        || lower.endsWith(".flac")
        || lower.endsWith(".ogg")
        || lower.endsWith(".m4a")
        || lower.endsWith(".mp4")
        || lower.endsWith(".webm");
  }

  public static String canonicalMime(String filename) {
    if (!isAudioName(filename)) {
      throw invalid();
    }
    return switch (filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)) {
      case "wav" -> "audio/wav";
      case "mp3" -> "audio/mpeg";
      case "flac" -> "audio/flac";
      case "ogg" -> "audio/ogg";
      case "m4a", "mp4" -> "audio/mp4";
      case "webm" -> "audio/webm";
      default -> throw invalid();
    };
  }

  public static void validateMetadata(String filename, String mime) {
    if (!isAudioName(filename)
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
          case "audio/wav" ->
              starts(content, 0, 'R', 'I', 'F', 'F') && starts(content, 8, 'W', 'A', 'V', 'E');
          case "audio/mpeg" ->
              starts(content, 0, 'I', 'D', '3')
                  || ((content[0] & 0xff) == 0xff
                      && (content[1] & 0xe0) == 0xe0
                      && (content[1] & 0x06) != 0
                      && (content[1] & 0x18) != 0x08);
          case "audio/flac" -> starts(content, 0, 'f', 'L', 'a', 'C');
          case "audio/ogg" -> starts(content, 0, 'O', 'g', 'g', 'S') && content[4] == 0;
          case "audio/mp4" -> content.length >= 16 && starts(content, 4, 'f', 't', 'y', 'p');
          case "audio/webm" -> starts(content, 0, 0x1a, 0x45, 0xdf, 0xa3);
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
