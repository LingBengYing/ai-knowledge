package com.evidence.rag.controller;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.vo.TaskResponse;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.IndexingService;
import com.evidence.rag.service.IndexingTaskProcessor;
import com.evidence.rag.web.converter.TaskResponseMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "rag.indexing", name = "enabled", havingValue = "true")
public final class IndexingController {
  private final IndexingService authority;
  private final IndexingTaskProcessor processor;

  public IndexingController(IndexingService authority, IndexingTaskProcessor processor) {
    this.authority = authority;
    this.processor = processor;
  }

  @PostMapping("/v1/documents/{documentId}/index")
  public ResponseEntity<TaskResponse> create(
      HttpServletRequest request, @PathVariable String documentId) {
    noQueryOrBody(request);
    return ResponseEntity.accepted()
        .body(
            TaskResponseMapper.from(
                processor.create(AuthenticatedActor.require(request), documentId)));
  }

  @GetMapping("/v1/indexings/{taskId}")
  public TaskResponse status(HttpServletRequest request, @PathVariable String taskId) {
    noQueryOrBody(request);
    return TaskResponseMapper.from(
        authority.indexingStatus(AuthenticatedActor.require(request), taskId));
  }

  @PostMapping("/v1/indexings/{taskId}/cancel")
  public TaskResponse cancel(HttpServletRequest request, @PathVariable String taskId) {
    noQueryOrBody(request);
    return TaskResponseMapper.from(
        authority.cancelIndexing(AuthenticatedActor.require(request), taskId));
  }

  @PostMapping("/v1/indexings/{taskId}/retry")
  public TaskResponse retry(HttpServletRequest request, @PathVariable String taskId) {
    noQueryOrBody(request);
    return TaskResponseMapper.from(processor.retry(AuthenticatedActor.require(request), taskId));
  }

  private static void noQueryOrBody(HttpServletRequest request) {
    if (request.getQueryString() != null
        || request.getContentLengthLong() > 0
        || request.getHeader("Transfer-Encoding") != null) {
      throw new ApplicationException(
          FailureKind.INVALID_INPUT, "invalid_request", "此索引操作不接受参数或请求体。");
    }
  }
}
