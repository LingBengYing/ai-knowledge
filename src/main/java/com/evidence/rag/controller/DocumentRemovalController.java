package com.evidence.rag.controller;

import static com.evidence.rag.model.domain.ModelValues.invalid;

import com.evidence.rag.model.dto.DocumentRemovalResult;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.DocumentLifecycleService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** HTTP Adapter for committed withdrawal requests, not physical cleanup completion. */
@RestController
@ConditionalOnProperty(prefix = "rag.document-removal", name = "enabled", havingValue = "true")
public final class DocumentRemovalController {
  private final DocumentLifecycleService lifecycle;

  public DocumentRemovalController(DocumentLifecycleService lifecycle) {
    this.lifecycle = lifecycle;
  }

  @DeleteMapping("/v1/documents/{documentId}")
  public ResponseEntity<DocumentRemovalResult> remove(
      HttpServletRequest request, @PathVariable String documentId) {
    if (request.getQueryString() != null
        || request.getContentLengthLong() > 0
        || request.getHeader("Transfer-Encoding") != null) {
      throw invalid();
    }
    return ResponseEntity.accepted()
        .body(lifecycle.removeDocument(AuthenticatedActor.require(request), documentId));
  }
}
