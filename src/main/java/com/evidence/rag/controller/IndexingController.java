package com.evidence.rag.controller;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.vo.TaskResponse;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.IndexingService;
import com.evidence.rag.service.IndexingTaskProcessor;
import com.evidence.rag.service.ManagedTextRuntime;
import com.evidence.rag.web.converter.ReindexRequestMapper;
import com.evidence.rag.web.converter.TaskResponseMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
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
  private final ManagedTextRuntime runtime;

  @Autowired
  public IndexingController(
      IndexingService authority,
      ObjectProvider<IndexingTaskProcessor> processor,
      ObjectProvider<ManagedTextRuntime> runtime) {
    this.authority = authority;
    this.processor = processor.getIfAvailable();
    this.runtime = runtime.getIfAvailable();
  }

  private IndexingTaskProcessor selected() {
    return runtime == null ? processor : runtime.capture().indexing();
  }

  @PostMapping("/v1/documents/{documentId}/index")
  public ResponseEntity<TaskResponse> create(
      HttpServletRequest request, @PathVariable String documentId) {
    noQueryOrBody(request);
    var actor = AuthenticatedActor.require(request);
    return ResponseEntity.accepted()
        .body(TaskResponseMapper.from(selected().create(actor, documentId)));
  }

  @PostMapping(
      value = "/v1/documents/{documentId}/reindex",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<TaskResponse> reindex(
      HttpServletRequest request, @PathVariable String documentId) {
    if (request.getQueryString() != null) {
      throw new ApplicationException(FailureKind.INVALID_INPUT, "invalid_request", "此操作不接受查询参数。 ");
    }
    var actor = AuthenticatedActor.require(request);
    byte[] body;
    try {
      body = request.getInputStream().readNBytes(ReindexRequestMapper.MAX_BYTES + 1);
    } catch (IOException failure) {
      throw new ApplicationException(FailureKind.INVALID_INPUT, "invalid_request", "无法读取请求。 ");
    }
    var base = ReindexRequestMapper.basePublicationId(body);
    return ResponseEntity.accepted()
        .body(TaskResponseMapper.from(selected().reindex(actor, documentId, base)));
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
    var actor = AuthenticatedActor.require(request);
    return TaskResponseMapper.from(selected().retry(actor, taskId));
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
