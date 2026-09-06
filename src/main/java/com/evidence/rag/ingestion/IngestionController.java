package com.evidence.rag.ingestion;

import com.evidence.rag.management.ManagementModule;
import com.evidence.rag.shared.Actor;
import com.evidence.rag.shared.Problem;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "rag.ingestion", name = "enabled", havingValue = "true")
public final class IngestionController {
  private final ManagementModule authority;

  public IngestionController(ManagementModule authority) {
    this.authority = authority;
  }

  @GetMapping("/v1/ingestions/{taskId}")
  public Map<String, Object> status(HttpServletRequest request, @PathVariable String taskId) {
    noQueryOrBody(request);
    return authority.ingestionStatus(actor(request), taskId);
  }

  @PostMapping("/v1/ingestions/{taskId}/cancel")
  public Map<String, Object> cancel(HttpServletRequest request, @PathVariable String taskId) {
    noQueryOrBody(request);
    return authority.cancelIngestion(actor(request), taskId);
  }

  @PostMapping("/v1/ingestions/{taskId}/retry")
  public Map<String, Object> retry(HttpServletRequest request, @PathVariable String taskId) {
    noQueryOrBody(request);
    return authority.retryIngestion(actor(request), taskId);
  }

  static Actor actor(HttpServletRequest request) {
    if (request.getAttribute(Actor.REQUEST_ATTRIBUTE) instanceof Actor actor) return actor;
    throw new Problem(401, "unauthenticated", "请先登录。");
  }

  private static void noQueryOrBody(HttpServletRequest request) {
    if (request.getQueryString() != null
        || request.getContentLengthLong() > 0
        || request.getHeader("Transfer-Encoding") != null)
      throw new Problem(422, "invalid_request", "此任务操作不接受参数或请求体。");
  }
}
