package com.evidence.rag.controller;

import com.evidence.rag.exception.ModelConfigurationInputException;
import com.evidence.rag.model.dto.ModelRebuildResult;
import com.evidence.rag.model.vo.ModelConfigurationProblemResponse;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.ModelRebuildService;
import com.evidence.rag.web.converter.ModelConfigurationRequestMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Explicitly submitted rebuilding; the request does not wait for model processing. */
@RestController
@ConditionalOnProperty(prefix = "rag.model-configuration", name = "enabled", havingValue = "true")
public final class ModelRebuildController {
  private final ModelRebuildService service;

  public ModelRebuildController(ModelRebuildService service) {
    this.service = service;
  }

  @GetMapping(
      value = "/v1/model-configuration/rebuild",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ModelRebuildResult> get(HttpServletRequest request) {
    noQuery(request);
    if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null) {
      throw invalid();
    }
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .body(service.get(AuthenticatedActor.require(request)));
  }

  @PostMapping(
      value = "/v1/model-configuration/rebuild",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ModelRebuildResult> start(HttpServletRequest request) {
    noQuery(request);
    byte[] body;
    try {
      body = request.getInputStream().readNBytes(ModelConfigurationRequestMapper.MAX_BYTES + 1);
    } catch (IOException failure) {
      throw invalid();
    }
    long version = ModelConfigurationRequestMapper.activate(body);
    return ResponseEntity.accepted()
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .body(service.start(AuthenticatedActor.require(request), version));
  }

  // Controller-local like ModelConfigurationController: without it, invalid input became 500.
  @ExceptionHandler(ModelConfigurationInputException.class)
  public ResponseEntity<ModelConfigurationProblemResponse> invalidInput(
      ModelConfigurationInputException failure, HttpServletRequest request) {
    return ResponseEntity.unprocessableEntity()
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .body(
            new ModelConfigurationProblemResponse(
                "https://evidence.local/problems/invalid-model-configuration",
                "请求无效",
                422,
                failure.getMessage(),
                request.getRequestURI(),
                "invalid_model_configuration",
                failure.field()));
  }

  private static void noQuery(HttpServletRequest request) {
    if (request.getQueryString() != null) {
      throw invalid();
    }
  }

  private static ModelConfigurationInputException invalid() {
    return new ModelConfigurationInputException("request");
  }
}
