package com.evidence.rag.controller;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.vo.DocumentOriginalResponse;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.ManagementService;
import com.evidence.rag.web.MediaContentResponse;
import com.evidence.rag.web.converter.ManagementResponseMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Saved original HTTP Adapter; reading does not require ingestion, indexing or answers. */
@RestController
public final class DocumentOriginalController {
  private final ManagementService management;

  public DocumentOriginalController(ManagementService management) {
    this.management = management;
  }

  @GetMapping("/v1/documents/{documentId}/original")
  public DocumentOriginalResponse original(
      HttpServletRequest request, @PathVariable String documentId) {
    noQueryOrBody(request);
    return ManagementResponseMapper.original(
        management.documentOriginal(AuthenticatedActor.require(request), documentId));
  }

  @GetMapping("/v1/documents/{documentId}/revisions/{revisionId}/content")
  public ResponseEntity<byte[]> content(
      HttpServletRequest request,
      @PathVariable String documentId,
      @PathVariable String revisionId) {
    noQueryOrBody(request);
    var original =
        management.documentContent(AuthenticatedActor.require(request), documentId, revisionId);
    byte[] bytes = original.content();
    return MediaContentResponse.protectDocument(ResponseEntity.ok(), original.mediaType())
        .contentType(MediaType.parseMediaType(original.mediaType()))
        .header("Cache-Control", "private, no-store")
        .header("X-Content-Type-Options", "nosniff")
        .contentLength(bytes.length)
        .body(bytes);
  }

  private static void noQueryOrBody(HttpServletRequest request) {
    if (request.getQueryString() != null
        || request.getContentLengthLong() > 0
        || request.getHeader("Transfer-Encoding") != null) {
      throw ModelValues.invalid();
    }
  }
}
