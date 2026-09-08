package com.evidence.rag.controller;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.vo.TaskResponse;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.IngestionService;
import com.evidence.rag.web.converter.TaskResponseMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "rag.ingestion", name = "enabled", havingValue = "true")
public final class IngestionController {
  private final IngestionService authority;

  public IngestionController(IngestionService authority) {
    this.authority = authority;
  }

  @GetMapping("/v1/ingestions/{taskId}")
  public TaskResponse status(HttpServletRequest request, @PathVariable String taskId) {
    noQueryOrBody(request);
    return TaskResponseMapper.from(
        authority.ingestionStatus(AuthenticatedActor.require(request), taskId));
  }

  @PostMapping("/v1/ingestions/{taskId}/cancel")
  public TaskResponse cancel(HttpServletRequest request, @PathVariable String taskId) {
    noQueryOrBody(request);
    return TaskResponseMapper.from(
        authority.cancelIngestion(AuthenticatedActor.require(request), taskId));
  }

  @PostMapping("/v1/ingestions/{taskId}/retry")
  public TaskResponse retry(HttpServletRequest request, @PathVariable String taskId) {
    noQueryOrBody(request);
    return TaskResponseMapper.from(
        authority.retryIngestion(AuthenticatedActor.require(request), taskId));
  }

  private static void noQueryOrBody(HttpServletRequest request) {
    if (request.getQueryString() != null
        || request.getContentLengthLong() > 0
        || request.getHeader("Transfer-Encoding") != null) {
      throw new ApplicationException(
          FailureKind.INVALID_INPUT, "invalid_request", "此任务操作不接受参数或请求体。");
    }
  }
}
