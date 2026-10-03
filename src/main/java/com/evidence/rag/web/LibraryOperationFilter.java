package com.evidence.rag.web;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.LibraryOperationGate;
import com.evidence.rag.service.LegacyTextProfileGuard;
import com.evidence.rag.service.VisualAnswerService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.databind.json.JsonMapper;

/** Actual synchronous HTTP bodies hold admission; async receivers explicitly reserve their work. */
@Component
@Order(-50)
public final class LibraryOperationFilter extends OncePerRequestFilter {
  private final LibraryOperationGate operations;
  private final ProblemHandler errors;
  private final JsonMapper json;
  private final LegacyTextProfileGuard legacy;
  private final boolean visualPresent;
  private final UrlPathHelper paths = new UrlPathHelper();

  public LibraryOperationFilter(
      LibraryOperationGate operations, ProblemHandler errors, JsonMapper json) {
    this.operations = Objects.requireNonNull(operations);
    this.errors = Objects.requireNonNull(errors);
    this.json = Objects.requireNonNull(json);
    this.legacy = null;
    this.visualPresent = false;
  }

  @Autowired
  public LibraryOperationFilter(
      LibraryOperationGate operations,
      ProblemHandler errors,
      JsonMapper json,
      ObjectProvider<LegacyTextProfileGuard> legacy,
      ObjectProvider<VisualAnswerService> visual) {
    this.operations = Objects.requireNonNull(operations);
    this.errors = Objects.requireNonNull(errors);
    this.json = Objects.requireNonNull(json);
    this.legacy = legacy.getIfAvailable();
    this.visualPresent = visual.getIfAvailable() != null;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = paths.getPathWithinApplication(request);
    return !path.startsWith("/v1/")
        || path.equals("/v1/config")
        || path.equals("/v1/model-configuration/activate")
        || path.equals("/v1/management/document-cleanups")
        || path.matches("/v1/documents/[A-Za-z0-9][A-Za-z0-9._:-]{0,127}/cleanup");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    LibraryOperationGate.OperationLease operation;
    try {
      operation = operations.enter();
    } catch (ApplicationException unavailable) {
      var problem = errors.problem(unavailable, request);
      response.setStatus(problem.getStatusCode().value());
      response.setHeader("Cache-Control", "private, no-store");
      response.setContentType("application/problem+json");
      response.setCharacterEncoding("UTF-8");
      response.getOutputStream().write(json.writeValueAsBytes(problem.getBody()));
      return;
    }
    try (operation) {
      if (legacy != null && legacyRoute(request)) {
        try {
          legacy.requireCompatible();
        } catch (ApplicationException unavailable) {
          var problem = errors.problem(unavailable, request);
          response.setStatus(problem.getStatusCode().value());
          response.setHeader("Cache-Control", "private, no-store");
          response.setContentType("application/problem+json");
          response.setCharacterEncoding("UTF-8");
          response.getOutputStream().write(json.writeValueAsBytes(problem.getBody()));
          return;
        }
      }
      chain.doFilter(request, response);
    }
  }

  private boolean legacyRoute(HttpServletRequest request) {
    String path = paths.getPathWithinApplication(request);
    if ("GET".equals(request.getMethod())
        && (path.startsWith("/v1/visual-sources/") || path.startsWith("/v1/video-sources/"))) {
      return false;
    }
    if (path.equals("/v1/visual-answers")
        || path.startsWith("/v1/visual-sources/")
        || path.equals("/v1/video-answers")
        || path.startsWith("/v1/video-sources/")
        || path.equals("/v1/attachment-answers")
        || path.matches(
            "/v1/documents/[A-Za-z0-9][A-Za-z0-9._:-]{0,127}/(image-vector|audio-vector)")) {
      return true;
    }
    if (!"POST".equals(request.getMethod()) || !path.equals("/v1/documents")) {
      return false;
    }
    String filename = request.getParameter("filename");
    String mime = request.getContentType();
    String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
    boolean video =
        lower.matches(".*\\.(mp4|webm|mov|mkv)$")
            || (mime != null && mime.toLowerCase(Locale.ROOT).startsWith("video/"));
    boolean image =
        lower.matches(".*\\.(png|jpe?g|webp)$")
            || (mime != null && mime.toLowerCase(Locale.ROOT).startsWith("image/"));
    return video || (visualPresent && image);
  }
}
