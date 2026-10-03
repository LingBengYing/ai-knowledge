package com.evidence.rag.controller;

import com.evidence.rag.exception.ModelConfigurationInputException;
import com.evidence.rag.model.dto.ModelConfigurationResult;
import com.evidence.rag.model.dto.ModelConfigurationTestResult;
import com.evidence.rag.model.vo.ModelConfigurationProblemResponse;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.ModelConfigurationService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "rag.model-configuration", name = "enabled", havingValue = "true")
public final class ModelConfigurationController {
  private final ModelConfigurationService service;

  public ModelConfigurationController(ModelConfigurationService service) {
    this.service = service;
  }

  @GetMapping(value = "/v1/model-configuration", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ModelConfigurationResult> get(HttpServletRequest request) {
    noQuery(request);
    if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null) {
      throw invalid();
    }
    return success(service.get(AuthenticatedActor.require(request)));
  }

  @PutMapping(
      value = "/v1/model-configuration",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ModelConfigurationResult> save(HttpServletRequest request) {
    noQuery(request);
    return success(
        service.save(
            AuthenticatedActor.require(request),
            ModelConfigurationRequestMapper.save(body(request))));
  }

  @PostMapping(
      value = "/v1/model-configuration/test",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ModelConfigurationTestResult> test(HttpServletRequest request) {
    noQuery(request);
    var actor = AuthenticatedActor.require(request);
    var command = ModelConfigurationRequestMapper.test(body(request));
    return success(service.test(actor, command.version(), command.role()));
  }

  @PostMapping(
      value = "/v1/model-configuration/activate",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ModelConfigurationResult> activate(HttpServletRequest request) {
    noQuery(request);
    return success(
        service.activate(
            AuthenticatedActor.require(request),
            ModelConfigurationRequestMapper.activate(body(request))));
  }

  @ExceptionHandler(ModelConfigurationInputException.class)
  public ResponseEntity<ModelConfigurationProblemResponse> invalid(
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

  private static byte[] body(HttpServletRequest request) {
    try {
      return request.getInputStream().readNBytes(ModelConfigurationRequestMapper.MAX_BYTES + 1);
    } catch (IOException failure) {
      throw invalid();
    }
  }

  private static void noQuery(HttpServletRequest request) {
    if (request.getQueryString() != null) {
      throw invalid();
    }
  }

  private static ModelConfigurationInputException invalid() {
    return new ModelConfigurationInputException("request");
  }

  private static <T> ResponseEntity<T> success(T result) {
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(result);
  }
}
