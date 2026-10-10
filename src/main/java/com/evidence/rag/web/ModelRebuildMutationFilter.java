package com.evidence.rag.web;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.service.ModelRebuildService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.databind.json.JsonMapper;

/** Content-changing requests wait for one rebuild, while questions and organization continue. */
@Component
@Order(-55)
@ConditionalOnProperty(prefix = "rag.model-configuration", name = "enabled", havingValue = "true")
public final class ModelRebuildMutationFilter extends OncePerRequestFilter {
  private final ModelRebuildService rebuilds;
  private final ProblemHandler errors;
  private final JsonMapper json;
  private final UrlPathHelper paths = new UrlPathHelper();

  public ModelRebuildMutationFilter(
      ModelRebuildService rebuilds, ProblemHandler errors, JsonMapper json) {
    this.rebuilds = rebuilds;
    this.errors = errors;
    this.json = json;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = paths.getPathWithinApplication(request);
    if (!"POST".equals(request.getMethod())) {
      return true;
    }
    // document-actions mixes organization and reindex; its Service checks only reindex.
    return !(path.equals("/v1/documents")
        || path.equals("/v1/sound-documents")
        || path.equals("/v1/video-av-documents")
        || path.equals("/v1/management/document-cleanups")
        || path.matches(
            "/v1/documents/[^/]+/(index|reindex|replacement|replacement/index|cleanup|image-vector|audio-vector|sound-index|video-av-index)")
        || path.matches("/v1/(ingestions|indexings)/[^/]+/retry"));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (rebuilds.mutationsBlocked()) {
      var failure =
          new ApplicationException(
              FailureKind.CONFLICT,
              "model_rebuild_in_progress",
              "模型索引正在重建；完成后可继续导入、更新和索引。现有资料仍可查询。");
      var problem = errors.problem(failure, request);
      response.setStatus(problem.getStatusCode().value());
      response.setHeader("Cache-Control", "private, no-store");
      response.setContentType("application/problem+json");
      response.setCharacterEncoding("UTF-8");
      response.getOutputStream().write(json.writeValueAsBytes(problem.getBody()));
      return;
    }
    chain.doFilter(request, response);
  }
}
