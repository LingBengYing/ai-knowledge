package com.evidence.rag.controller;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.EvidenceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Binary HTTP Adapter for the original image of a still-authorized saved citation. */
@RestController
@ConditionalOnProperty(prefix = "rag.answers", name = "enabled", havingValue = "true")
public final class SourceContentController {
  private final EvidenceService evidence;

  public SourceContentController(EvidenceService evidence) {
    this.evidence = evidence;
  }

  @GetMapping("/v1/sources/{answerId}/{ordinal}/content")
  public ResponseEntity<byte[]> content(
      HttpServletRequest request, @PathVariable String answerId, @PathVariable int ordinal) {
    if (ordinal < 1
        || ordinal > 32
        || request.getQueryString() != null
        || request.getContentLengthLong() > 0
        || request.getHeader("Transfer-Encoding") != null) {
      throw ModelValues.invalid();
    }
    var source = evidence.source(AuthenticatedActor.require(request), answerId, ordinal);
    if (source.image() == null) {
      throw ModelValues.notFound();
    }
    var image = source.image();
    byte[] content = image.content();
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(image.mimeType()))
        .cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options", "nosniff")
        .contentLength(content.length)
        .body(content);
  }
}
