package com.evidence.rag.controller;

import com.evidence.rag.config.IngestionSettings;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.vo.DocumentReplacementResponse;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.DocumentReplacementService;
import com.evidence.rag.service.IngestionService;
import com.evidence.rag.web.ProblemHandler;
import com.evidence.rag.web.UploadServlet;
import com.evidence.rag.web.converter.DocumentReplacementRequestMapper;
import com.evidence.rag.web.converter.DocumentReplacementResponseMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** Explicit same-document original update; asynchronous reception reuses the bounded uploader. */
@RestController
public final class DocumentReplacementController {
  private final DocumentReplacementService replacements;
  private final UploadServlet uploads;

  public DocumentReplacementController(
      DocumentReplacementService replacements,
      IngestionService ingestion,
      IngestionSettings settings,
      JsonMapper json,
      ProblemHandler errors) {
    this.replacements = replacements;
    uploads = new UploadServlet(ingestion, settings.uploadTimeoutMs(), json, errors, replacements);
  }

  @GetMapping("/v1/documents/{documentId}/replacement")
  public DocumentReplacementResponse get(
      HttpServletRequest request, @PathVariable String documentId) {
    noQueryOrBody(request);
    return DocumentReplacementResponseMapper.from(
        replacements.get(AuthenticatedActor.require(request), documentId));
  }

  @PostMapping("/v1/documents/{documentId}/replacement")
  public void upload(
      HttpServletRequest request,
      HttpServletResponse response,
      @PathVariable String documentId)
      throws IOException {
    uploads.replacement(request, response, documentId);
  }

  @PostMapping(
      value = "/v1/documents/{documentId}/replacement/index",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<DocumentReplacementResponse> index(
      HttpServletRequest request, @PathVariable String documentId) {
    if (request.getQueryString() != null) {
      throw ModelValues.invalid();
    }
    byte[] bytes;
    try {
      bytes = request.getInputStream().readNBytes(DocumentReplacementRequestMapper.MAX_BYTES + 1);
    } catch (IOException rejected) {
      throw new ApplicationException(FailureKind.INVALID_INPUT, "invalid_request", "无法读取更新请求。");
    }
    var command = DocumentReplacementRequestMapper.index(bytes);
    var result =
        replacements.index(
            AuthenticatedActor.require(request),
            documentId,
            command.candidateRevisionId(),
            command.baseRevisionId());
    return ResponseEntity.status("published".equals(result.state()) ? 200 : 202)
        .body(DocumentReplacementResponseMapper.from(result));
  }

  private static void noQueryOrBody(HttpServletRequest request) {
    if (request.getQueryString() != null
        || request.getContentLengthLong() > 0
        || request.getHeader("Transfer-Encoding") != null) {
      throw ModelValues.invalid();
    }
  }
}
