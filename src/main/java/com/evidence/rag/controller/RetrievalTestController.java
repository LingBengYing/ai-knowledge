package com.evidence.rag.controller;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.dto.RetrievalTestResult;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.TextRetrievalTestService;
import com.evidence.rag.web.converter.RetrievalTestRequestMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "rag.model-configuration", name = "enabled", havingValue = "true")
public final class RetrievalTestController {
  private final TextRetrievalTestService service;

  public RetrievalTestController(TextRetrievalTestService service) {
    this.service = service;
  }

  @PostMapping(
      value = "/v1/retrieval-tests",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<RetrievalTestResult> test(HttpServletRequest request) {
    if (request.getQueryString() != null) {
      throw ModelValues.invalid();
    }
    var actor = AuthenticatedActor.require(request);
    try {
      var command =
          RetrievalTestRequestMapper.command(
              request.getInputStream().readNBytes(RetrievalTestRequestMapper.MAX_BYTES + 1));
      return ResponseEntity.ok()
          .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
          .body(service.test(actor, command));
    } catch (IOException failed) {
      throw ModelValues.invalid();
    }
  }
}
