package com.evidence.rag.model.domain;

import com.evidence.rag.exception.ApplicationException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;

/** Shared constraints for authoritative identity and metadata values. No framework or I/O. */
public final class ModelValues {
  private ModelValues() {}

  public static void indexIdentity(String value) {
    if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
      throw invalid();
    }
  }

  public static List<String> tags(Object value) {
    if (!(value instanceof List<?> list) || list.size() > 20) {
      throw invalid();
    }
    var result = new LinkedHashSet<String>();
    for (Object tag : list) {
      result.add(label(tag, 40));
    }
    return new ArrayList<>(result);
  }

  public static String label(Object value, int maximum) {
    if (!(value instanceof String text)) {
      throw invalid();
    }
    String cleaned = text.strip();
    if (cleaned.isEmpty()) {
      throw invalid();
    }
    return bounded(cleaned, maximum);
  }

  public static String identifier(Object value, int maximum) {
    if (!(value instanceof String text) || text.isEmpty()) {
      throw invalid();
    }
    return bounded(text, maximum);
  }

  public static String bounded(String value, int maximum) {
    if (value == null
        || value.codePointCount(0, value.length()) > maximum
        || value
            .codePoints()
            .anyMatch(c -> Character.isISOControl(c) || (c >= 0xD800 && c <= 0xDFFF))) {
      throw invalid();
    }
    return value;
  }

  public static ApplicationException invalid() {
    return new ApplicationException(
        com.evidence.rag.exception.FailureKind.INVALID_INPUT, "invalid_request", "请求字段、长度或取值无效。");
  }

  public static ApplicationException notFound() {
    return new ApplicationException(
        com.evidence.rag.exception.FailureKind.NOT_FOUND, "not_found", "资料不存在、无操作权限或当前状态不可操作。");
  }

  public static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }
}
