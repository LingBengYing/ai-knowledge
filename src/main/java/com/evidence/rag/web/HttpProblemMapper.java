package com.evidence.rag.web;

import com.evidence.rag.exception.ApplicationException;

/** The single HTTP mapping of application failure categories. */
public final class HttpProblemMapper {
  private HttpProblemMapper() {}

  public static int status(ApplicationException failure) {
    return switch (failure.kind()) {
      case INVALID_REQUEST -> 400;
      case UNAUTHENTICATED -> 401;
      case FORBIDDEN -> 403;
      case NOT_FOUND -> 404;
      case METHOD_NOT_ALLOWED -> 405;
      case TIMEOUT -> 408;
      case CONFLICT -> 409;
      case PAYLOAD_TOO_LARGE -> 413;
      case UNSUPPORTED_MEDIA -> 415;
      case INVALID_INPUT -> 422;
      case CAPACITY_EXCEEDED -> 429;
      case INTERNAL -> 500;
      case NOT_IMPLEMENTED -> 501;
      case UNAVAILABLE -> 503;
    };
  }
}
