package com.evidence.rag.security.web;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.web.HttpProblemMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Enumeration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.util.UrlPathHelper;

@Component
@Order(-100)
public final class AuthenticationFilter extends OncePerRequestFilter {
  private final RequestAuthenticator authentication;
  private final HandlerExceptionResolver errors;
  private final UrlPathHelper paths = new UrlPathHelper();

  public AuthenticationFilter(
      RequestAuthenticator authentication,
      @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) {
    this.authentication = authentication;
    this.errors = errors;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = paths.getPathWithinApplication(request);
    if (!path.equals("/v1") && !path.startsWith("/v1/")) {
      chain.doFilter(request, response);
      return;
    }
    try {
      validateMutationOrigin(request);
      if (!("GET".equals(request.getMethod()) && path.equals("/v1/config"))
          && !path.equals("/v1/session")) {
        AuthenticatedActor.attach(request, authentication.authenticate(request));
      }
    } catch (ApplicationException problem) {
      response.setStatus(HttpProblemMapper.status(problem));
      response.setHeader("Cache-Control", "private, no-store");
      if (HttpProblemMapper.status(problem) == 401) {
        response.setHeader("WWW-Authenticate", "Bearer");
      }
      errors.resolveException(request, response, null, problem);
      return;
    }
    chain.doFilter(request, response);
  }

  private static void validateMutationOrigin(HttpServletRequest request) {
    if ("GET".equals(request.getMethod())
        || "HEAD".equals(request.getMethod())
        || "OPTIONS".equals(request.getMethod())) {
      return;
    }
    Enumeration<String> origins = request.getHeaders("Origin");
    if (origins == null || !origins.hasMoreElements()) {
      return;
    }
    String origin = origins.nextElement();
    if (origins.hasMoreElements()) {
      throw crossOrigin();
    }
    try {
      URI uri = URI.create(origin);
      int port =
          uri.getPort() < 0
              ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80)
              : uri.getPort();
      if (uri.getScheme() == null
          || !uri.getScheme().equalsIgnoreCase(request.getScheme())
          || uri.getHost() == null
          || !uri.getHost().equalsIgnoreCase(request.getServerName())
          || port != request.getServerPort()
          || uri.getUserInfo() != null
          || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null) {
        throw crossOrigin();
      }
    } catch (IllegalArgumentException exception) {
      throw crossOrigin();
    }
  }

  private static ApplicationException crossOrigin() {
    return new ApplicationException(FailureKind.FORBIDDEN, "cross_origin_denied", "此操作仅允许同源请求。");
  }
}
