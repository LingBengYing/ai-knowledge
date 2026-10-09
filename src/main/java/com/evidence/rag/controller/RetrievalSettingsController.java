package com.evidence.rag.controller;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.RetrievalSettings;
import com.evidence.rag.model.dto.RetrievalSettingsResult;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.RetrievalSettingsService;
import com.evidence.rag.web.converter.RetrievalSettingsRequestMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "rag.model-configuration", name = "enabled", havingValue = "true")
public final class RetrievalSettingsController {
  private final RetrievalSettingsService service;

  public RetrievalSettingsController(RetrievalSettingsService service) {
    this.service = service;
  }

  @GetMapping(value = "/v1/retrieval-settings", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<RetrievalSettingsResult> read(HttpServletRequest request) {
    noQuery(request);
    if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null) {
      throw invalid();
    }
    return response(service.read(AuthenticatedActor.require(request)));
  }

  @PutMapping(
      value = "/v1/retrieval-settings",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<RetrievalSettingsResult> save(HttpServletRequest request) {
    noQuery(request);
    var actor = AuthenticatedActor.require(request);
    try {
      var settings =
          RetrievalSettingsRequestMapper.save(
              request.getInputStream().readNBytes(RetrievalSettingsRequestMapper.MAX_BYTES + 1));
      return response(service.save(actor, settings));
    } catch (IOException failed) {
      throw invalid();
    }
  }

  private static ResponseEntity<RetrievalSettingsResult> response(RetrievalSettings settings) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .body(RetrievalSettingsResult.from(settings));
  }

  private static void noQuery(HttpServletRequest request) {
    if (request.getQueryString() != null) {
      throw invalid();
    }
  }

  private static ApplicationException invalid() {
    return new ApplicationException(
        FailureKind.INVALID_REQUEST, "invalid_retrieval_settings", "检索设置请求无效。");
  }
}
