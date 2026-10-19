package com.evidence.rag.controller;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.dto.DocumentBatchActionResult;
import com.evidence.rag.model.dto.DocumentBatchAppendCommand;
import com.evidence.rag.model.dto.DocumentBatchAppendResult;
import com.evidence.rag.model.dto.DocumentBatchCommand;
import com.evidence.rag.model.dto.DocumentBatchList;
import com.evidence.rag.model.dto.DocumentBatchResult;
import com.evidence.rag.model.dto.DocumentBatchResumeCommand;
import com.evidence.rag.model.vo.TaskResponse;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.DocumentBatchService;
import com.evidence.rag.web.converter.TaskResponseMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
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

  /** The workspace's import batches, newest first. */
  @GetMapping("/v1/document-batches")
  public DocumentBatchList list(HttpServletRequest request) {
    onlyParameters(request, Set.of("offset", "limit"));
    return service.list(
        AuthenticatedActor.require(request),
        number(request.getParameter("offset"), 0),
        number(request.getParameter("limit"), 20));
  }

  /** One page of a batch; {@code state} is an optional comma-separated list of file states. */
  @GetMapping("/v1/document-batches/{id}")
  public DocumentBatchResult status(HttpServletRequest request, @PathVariable String id) {
    onlyParameters(request, Set.of("offset", "limit", "state"));
    String state = request.getParameter("state");
    if (state != null && (state.isEmpty() || state.length() > 200)) throw ModelValues.invalid();
    return service.status(
        AuthenticatedActor.require(request),
        id,
        state == null ? List.of() : List.of(state.split(",", -1)),
        number(request.getParameter("offset"), 0),
        number(request.getParameter("limit"), 100));
  }

  private static void onlyParameters(HttpServletRequest request, Set<String> allowed) {
    if (!allowed.containsAll(request.getParameterMap().keySet())
        || request.getParameterMap().values().stream().anyMatch(v -> v.length != 1))
      throw ModelValues.invalid();
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

  /**
   * Appends manifest entries to an open batch (explicit, consecutive ordinals; replays allowed).
   */
  @PostMapping(value = "/v1/document-batches/{id}/files", consumes = "application/json")
  public DocumentBatchAppendResult append(
      HttpServletRequest request,
      @PathVariable String id,
      @RequestBody DocumentBatchAppendCommand command) {
    noQuery(request);
    return service.append(AuthenticatedActor.require(request), id, command);
  }

  /** Closes an open batch's manifest with the entries registered so far. */
  @PostMapping("/v1/document-batches/{id}/seal")
  public DocumentBatchActionResult seal(HttpServletRequest request, @PathVariable String id) {
    noQuery(request);
    return service.seal(AuthenticatedActor.require(request), id);
  }

  /** Retries every failed or cancelled file that still has attempts left. */
  @PostMapping("/v1/document-batches/{id}/retry-failed")
  public DocumentBatchActionResult retryFailed(
      HttpServletRequest request, @PathVariable String id) {
    noQuery(request);
    return service.retryFailed(AuthenticatedActor.require(request), id);
  }

  /** Cancels queued work and refuses further uploads; running tasks finish. */
  @PostMapping("/v1/document-batches/{id}/cancel")
  public DocumentBatchActionResult cancel(HttpServletRequest request, @PathVariable String id) {
    noQuery(request);
    return service.cancel(AuthenticatedActor.require(request), id);
  }

  /** Clears the halt, optionally with a new embedding request limit, and requeues stopped work. */
  @PostMapping("/v1/document-batches/{id}/resume")
  public DocumentBatchActionResult resume(
      HttpServletRequest request,
      @PathVariable String id,
      @RequestBody(required = false) DocumentBatchResumeCommand command) {
    noQuery(request);
    return service.resume(
        AuthenticatedActor.require(request),
        id,
        command == null ? null : command.embeddingRequestLimit());
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
