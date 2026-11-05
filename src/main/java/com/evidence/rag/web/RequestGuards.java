package com.evidence.rag.web;

import com.evidence.rag.model.domain.ModelValues;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.function.Supplier;

/** Exact-request guards shared by controllers; each caller keeps its own public failure. */
public final class RequestGuards {
  private RequestGuards() {}

  /** Rejects any query string or request body, including a chunked one, as an invalid request. */
  public static void noQueryOrBody(HttpServletRequest request) {
    noQueryOrBody(request, ModelValues::invalid);
  }

  public static void noQueryOrBody(
      HttpServletRequest request, Supplier<? extends RuntimeException> failure) {
    if (request.getQueryString() != null || hasBody(request)) {
      throw failure.get();
    }
  }

  public static void noQuery(
      HttpServletRequest request, Supplier<? extends RuntimeException> failure) {
    if (request.getQueryString() != null) {
      throw failure.get();
    }
  }

  public static void noBody(
      HttpServletRequest request, Supplier<? extends RuntimeException> failure) {
    if (hasBody(request)) {
      throw failure.get();
    }
  }

  /** Reads at most one byte past a mapper's limit, so oversize bodies never fill the heap. */
  public static byte[] boundedBody(HttpServletRequest request, int maxBytes) {
    try {
      return request.getInputStream().readNBytes(maxBytes + 1);
    } catch (IOException failure) {
      throw ModelValues.invalid();
    }
  }

  private static boolean hasBody(HttpServletRequest request) {
    return request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null;
  }
}
