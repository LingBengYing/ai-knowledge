package com.evidence.rag.controller;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.SynopsisSourceMaterial;
import com.evidence.rag.model.vo.SynopsisDocumentResponse;
import com.evidence.rag.model.vo.SynopsisSourceResponse;
import com.evidence.rag.model.vo.SynopsisTaskResponse;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.SynopsisLibraryService;
import com.evidence.rag.service.SynopsisTaskProcessor;
import com.evidence.rag.web.MediaContentResponse;
import com.evidence.rag.web.converter.SynopsisResponseMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP-only synopsis Adapter. Authorization and source integrity are owned by the Service. */
@RestController
@ConditionalOnProperty(prefix = "rag.synopsis", name = "enabled", havingValue = "true")
public final class SynopsisController {
  private final SynopsisLibraryService library;
  private final SynopsisTaskProcessor processor;

  public SynopsisController(SynopsisLibraryService library, SynopsisTaskProcessor processor) {
    this.library = Objects.requireNonNull(library);
    this.processor = Objects.requireNonNull(processor);
  }

  @PostMapping("/v1/documents/{documentId}/synopsis")
  public ResponseEntity<SynopsisTaskResponse> create(
      HttpServletRequest request, @PathVariable String documentId) {
    noQueryOrBody(request);
    return ResponseEntity.accepted()
        .body(
            SynopsisResponseMapper.task(
                processor.create(AuthenticatedActor.require(request), documentId)));
  }

  @GetMapping("/v1/synopsis-tasks/{taskId}")
  public SynopsisTaskResponse task(HttpServletRequest request, @PathVariable String taskId) {
    noQueryOrBody(request);
    return SynopsisResponseMapper.task(library.task(AuthenticatedActor.require(request), taskId));
  }

  @GetMapping("/v1/documents/{documentId}/synopsis")
  public SynopsisDocumentResponse get(HttpServletRequest request, @PathVariable String documentId) {
    noQueryOrBody(request);
    return SynopsisResponseMapper.document(
        library.get(AuthenticatedActor.require(request), documentId));
  }

  @GetMapping("/v1/synopsis-sources/{synopsisId}/{entryOrdinal}/{sourceOrdinal}")
  public SynopsisSourceResponse source(
      HttpServletRequest request,
      @PathVariable String synopsisId,
      @PathVariable int entryOrdinal,
      @PathVariable int sourceOrdinal) {
    return SynopsisResponseMapper.source(
        synopsisId,
        entryOrdinal,
        sourceOrdinal,
        resolve(request, synopsisId, entryOrdinal, sourceOrdinal));
  }

  @GetMapping("/v1/synopsis-sources/{synopsisId}/{entryOrdinal}/{sourceOrdinal}/content")
  public ResponseEntity<byte[]> content(
      HttpServletRequest request,
      @PathVariable String synopsisId,
      @PathVariable int entryOrdinal,
      @PathVariable int sourceOrdinal) {
    var source = resolve(request, synopsisId, entryOrdinal, sourceOrdinal);
    // Authorization and the original SHA are resolved before Range can expose content or size.
    return MediaContentResponse.create(
        source.mediaType(),
        source.content(),
        Collections.list(request.getHeaders(HttpHeaders.RANGE)));
  }

  @GetMapping("/v1/synopsis-sources/{synopsisId}/{entryOrdinal}/{sourceOrdinal}/frame")
  public ResponseEntity<byte[]> frame(
      HttpServletRequest request,
      @PathVariable String synopsisId,
      @PathVariable int entryOrdinal,
      @PathVariable int sourceOrdinal) {
    var source = resolve(request, synopsisId, entryOrdinal, sourceOrdinal);
    if (source.frame() == null) {
      throw ModelValues.notFound();
    }
    return MediaContentResponse.create(
        source.frame().mediaType(),
        source.frame().content(),
        Collections.list(request.getHeaders(HttpHeaders.RANGE)));
  }

  private SynopsisSourceMaterial resolve(
      HttpServletRequest request, String synopsisId, int entryOrdinal, int sourceOrdinal) {
    noQueryOrBody(request);
    if (entryOrdinal < 1 || entryOrdinal > 32 || sourceOrdinal < 1 || sourceOrdinal > 8) {
      throw ModelValues.invalid();
    }
    return library.source(
        AuthenticatedActor.require(request), synopsisId, entryOrdinal - 1, sourceOrdinal - 1);
  }

  private static void noQueryOrBody(HttpServletRequest request) {
    if (request.getQueryString() != null
        || request.getContentLengthLong() > 0
        || request.getHeader("Transfer-Encoding") != null) {
      throw ModelValues.invalid();
    }
  }
}
