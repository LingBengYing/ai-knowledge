package com.evidence.rag.security.web;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import jakarta.servlet.http.HttpServletRequest;

/** Trusted request context written only after authentication. */
public final class AuthenticatedActor {
  private static final String ATTRIBUTE = AuthenticatedActor.class.getName();

  private AuthenticatedActor() {}

  static void attach(HttpServletRequest request, Actor actor) {
    request.setAttribute(ATTRIBUTE, actor);
  }

  public static Actor require(HttpServletRequest request) {
    if (request.getAttribute(ATTRIBUTE) instanceof Actor actor) {
      return actor;
    }
    throw new ApplicationException(FailureKind.UNAUTHENTICATED, "unauthenticated", "请先登录。");
  }
}
