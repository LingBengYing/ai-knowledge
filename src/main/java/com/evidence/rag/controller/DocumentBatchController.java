package com.evidence.rag.controller;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.dto.DocumentBatchCommand;
import com.evidence.rag.model.dto.DocumentBatchResult;
import com.evidence.rag.model.vo.TaskResponse;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.DocumentBatchService;
import com.evidence.rag.web.converter.TaskResponseMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.Semaphore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Small manifest and bounded individual file bodies; no parsing or models on request threads. */
@RestController
@ConditionalOnProperty(prefix = "rag.ingestion", name = "enabled", havingValue = "true")
public final class DocumentBatchController {
  private final DocumentBatchService service;
  private final Semaphore uploads;

  public DocumentBatchController(DocumentBatchService service) {
    this.service = service;
    uploads = new Semaphore(service.uploadConcurrency());
  }

  @PostMapping(value = "/v1/document-batches", consumes = "application/json")
  public ResponseEntity<DocumentBatchResult> create(
      HttpServletRequest request, @RequestBody DocumentBatchCommand command) {
    noQuery(request);
    return ResponseEntity.accepted()
        .body(service.create(AuthenticatedActor.require(request), command));
  }

  @GetMapping("/v1/document-batches/{id}")
  public DocumentBatchResult status(HttpServletRequest request, @PathVariable String id) {
    if (!Set.of("offset", "limit").containsAll(request.getParameterMap().keySet())
        || request.getParameterMap().values().stream().anyMatch(v -> v.length != 1))
      throw ModelValues.invalid();
    return service.status(
        AuthenticatedActor.require(request),
        id,
        number(request.getParameter("offset"), 0),
        number(request.getParameter("limit"), 100));
  }

  @PostMapping(
      value = "/v1/document-batches/{id}/files/{ordinal}",
      consumes = "application/octet-stream")
  public ResponseEntity<TaskResponse> upload(
      HttpServletRequest request, @PathVariable String id, @PathVariable int ordinal)
      throws IOException {
    noQuery(request);
    var actor = AuthenticatedActor.require(request);
    if (request.getContentLengthLong() > service.maximumUploadBytes()) throw ModelValues.invalid();
    if (!uploads.tryAcquire())
      throw new ApplicationException(FailureKind.CAPACITY_EXCEEDED, "upload_busy", "上传队列繁忙，请稍后继续。");
    try {
      var bytes = request.getInputStream().readNBytes(service.maximumUploadBytes() + 1);
      if (bytes.length > service.maximumUploadBytes()) throw ModelValues.invalid();
      return ResponseEntity.accepted()
          .body(TaskResponseMapper.from(service.upload(actor, id, ordinal, bytes)));
    } finally {
      uploads.release();
    }
  }

  private static int number(String value, int fallback) {
    if (value == null) return fallback;
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException invalid) {
      throw ModelValues.invalid();
    }
  }

  private static void noQuery(HttpServletRequest request) {
    if (request.getQueryString() != null) throw ModelValues.invalid();
  }
}
