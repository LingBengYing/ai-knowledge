package com.evidence.rag.controller;

import com.evidence.rag.model.domain.ModelValues;
import jakarta.servlet.http.HttpServletRequest;

/** Exact-request guards shared by controllers whose routes take neither parameters nor a body. */
final class RequestGuards {
  private RequestGuards() {}

  /** Rejects any query string or request body, including a chunked one, as an invalid request. */
  static void noQueryOrBody(HttpServletRequest request) {
    if (request.getQueryString() != null
        || request.getContentLengthLong() > 0
        || request.getHeader("Transfer-Encoding") != null) {
      throw ModelValues.invalid();
    }
  }
}
