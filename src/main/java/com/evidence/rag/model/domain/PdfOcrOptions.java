package com.evidence.rag.model.domain;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Immutable identity of the text-first PDF/OCR profile; no filesystem access or processes. Exactly
 * one backend is set: a local Tesseract ({@code ocr}), the local page service ({@code socket}), or
 * SiliconFlow called directly ({@code provider}) with the key from the managed model settings.
 */
public record PdfOcrOptions(ImageOcrOptions ocr, Path socket, String revision, URI provider) {
  public static final String PIPELINE_PROFILE = "paddleocr-vl-1.5-pipeline-v1";
  public static final String REMOTE_MODEL = "PaddlePaddle/PaddleOCR-VL-1.5";

  public PdfOcrOptions(ImageOcrOptions ocr) {
    this(ocr, null, null, null);
  }

  public PdfOcrOptions(ImageOcrOptions ocr, Path socket, String revision) {
    this(ocr, socket, revision, null);
  }

  /** SiliconFlow chat/completions base URL, e.g. {@code https://api.siliconflow.cn/v1}. */
  public static PdfOcrOptions remote(URI provider) {
    return new PdfOcrOptions(null, null, null, provider);
  }

  public PdfOcrOptions {
    if (provider != null) {
      if (ocr != null
          || socket != null
          || revision != null
          || !provider.isAbsolute()
          || provider.getHost() == null
          || provider.getRawUserInfo() != null
          || provider.getRawQuery() != null
          || provider.getRawFragment() != null
          || !("https".equals(provider.getScheme())
              || "http".equals(provider.getScheme()) && loopback(provider.getHost()))) {
        throw new IllegalArgumentException("Explicit PDF OCR options are required");
      }
    } else if (ocr == null
        ? socket == null
            || !socket.isAbsolute()
            || !socket.normalize().equals(socket)
            || socket.toString().codePoints().anyMatch(Character::isISOControl)
            || revision == null
            || !revision.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}")
            || revision.equalsIgnoreCase("latest")
            || revision.equalsIgnoreCase("default")
            || revision.equalsIgnoreCase("unknown")
        : socket != null || revision != null) {
      throw new IllegalArgumentException("Explicit PDF OCR options are required");
    }
  }

  public String parserRevision() {
    if (provider != null) {
      String profile =
          "java-pdf-ocr-v1\0pdfbox-rgb-144dpi\0text-first-mixed-image-fallback-v2\0"
              + "java-text-parser-v2-monotonic-codepoints\0openai-chat-page-ocr-v1\0"
              + REMOTE_MODEL
              + "\0OCR:\0"
              + provider;
      return "java-pdf-ocr-v1:" + ModelValues.sha256(profile.getBytes(StandardCharsets.UTF_8));
    }
    if (socket != null) {
      String profile =
          "java-pdf-ocr-v1\0pdfbox-rgb-144dpi\0text-first-mixed-image-fallback-v2\0"
              + "java-text-parser-v2-monotonic-codepoints\0unix-page-ocr-v1\0"
              + PIPELINE_PROFILE
              + "\0"
              + revision
              + "\0"
              + socket;
      return "java-pdf-ocr-v1:" + ModelValues.sha256(profile.getBytes(StandardCharsets.UTF_8));
    }
    String profile =
        "java-pdf-ocr-v1\0pdfbox-rgb-144dpi\0text-first-mixed-image-fallback-v2\0"
            + "java-text-parser-v2-monotonic-codepoints\0"
            + ocr.parserRevision()
            + "\0"
            + ocr.executable();
    return "java-pdf-ocr-v1:" + ModelValues.sha256(profile.getBytes(StandardCharsets.UTF_8));
  }

  public boolean pipeline() {
    return socket != null;
  }

  public boolean remote() {
    return provider != null;
  }

  private static boolean loopback(String host) {
    return "127.0.0.1".equals(host) || "[::1]".equals(host) || "localhost".equals(host);
  }

  @Override
  public String toString() {
    return "PdfOcrOptions[redacted]";
  }
}
