package com.evidence.rag.controller;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.dto.DocumentCleanupBatchResult;
import com.evidence.rag.model.dto.DocumentCleanupPageResult;
import com.evidence.rag.model.dto.DocumentCleanupResult;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.DocumentCleanupService;
import com.evidence.rag.web.converter.DocumentCleanupRequestMapper;
import com.evidence.rag.web.converter.DocumentCleanupResponseMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Explicit control and authorized status reads; background cleanup owns completion. */
@RestController
@ConditionalOnProperty(prefix = "rag.document-cleanup", name = "enabled", havingValue = "true")
public final class DocumentCleanupController {
  private final DocumentCleanupService cleanup;

  public DocumentCleanupController(DocumentCleanupService cleanup) {
    this.cleanup = cleanup;
  }

  @PostMapping(
      value = "/v1/documents/{documentId}/cleanup",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<DocumentCleanupResult> request(
      HttpServletRequest request, @PathVariable String documentId) {
    bodyless(request, false);
    return ResponseEntity.accepted()
        .body(
            DocumentCleanupResponseMapper.state(
                cleanup.request(
                    AuthenticatedActor.require(request),
                    DocumentCleanupRequestMapper.documentId(documentId))));
  }

  @GetMapping(
      value = "/v1/documents/{documentId}/cleanup",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public DocumentCleanupResult status(HttpServletRequest request, @PathVariable String documentId) {
    bodyless(request, false);
    return DocumentCleanupResponseMapper.state(
        cleanup.status(
            AuthenticatedActor.require(request),
            DocumentCleanupRequestMapper.documentId(documentId)));
  }

  @PostMapping(
      value = "/v1/management/document-cleanups",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<DocumentCleanupBatchResult> batch(
      HttpServletRequest request, @RequestBody byte[] body) {
    if (request.getQueryString() != null) {
      throw ModelValues.invalid();
    }
    return ResponseEntity.accepted()
        .body(
            DocumentCleanupResponseMapper.batch(
                cleanup.batch(
                    AuthenticatedActor.require(request),
                    DocumentCleanupRequestMapper.documentIds(body))));
  }

  @GetMapping(
      value = "/v1/management/document-cleanups",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public DocumentCleanupPageResult list(HttpServletRequest request) {
    bodyless(request, true);
    int[] page = DocumentCleanupRequestMapper.page(request.getParameterMap());
    return DocumentCleanupResponseMapper.page(
        cleanup.list(AuthenticatedActor.require(request), page[0], page[1]));
  }

  private static void bodyless(HttpServletRequest request, boolean queryAllowed) {
    if ((!queryAllowed && request.getQueryString() != null)
        || request.getContentLengthLong() > 0
        || request.getHeader("Transfer-Encoding") != null) {
      throw ModelValues.invalid();
    }
  }
}
