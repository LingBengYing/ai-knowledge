package com.evidence.rag.controller;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.dto.AnswerResult;
import com.evidence.rag.model.dto.SourceResult;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.AnswerService;
import com.evidence.rag.web.converter.AnswerRequestMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** HTTP Adapter for explicitly enabled answers and current-authorized source excerpts. */
@RestController
@ConditionalOnProperty(prefix = "rag.answers", name = "enabled", havingValue = "true")
public final class AnswerController {
  private final AnswerService answers;

  public AnswerController(AnswerService answers) {
    this.answers = answers;
  }

  @PostMapping(
      value = "/v1/answers",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public AnswerResult answer(HttpServletRequest request, @RequestBody Map<String, Object> body) {
    if (request.getQueryString() != null) {
      throw invalid();
    }
    return answers.answer(AuthenticatedActor.require(request), AnswerRequestMapper.command(body));
  }

  @GetMapping(
      value = "/v1/sources/{answerId}/{ordinal}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public SourceResult source(
      HttpServletRequest request, @PathVariable String answerId, @PathVariable int ordinal) {
    if (ordinal < 1
        || ordinal > 32
        || request.getQueryString() != null
        || request.getContentLengthLong() > 0
        || request.getHeader("Transfer-Encoding") != null) {
      throw invalid();
    }
    return answers.source(AuthenticatedActor.require(request), answerId, ordinal);
  }

  private static ApplicationException invalid() {
    return new ApplicationException(
        FailureKind.INVALID_INPUT, "invalid_request", "问答或来源请求的参数、地址或请求体无效。");
  }
}
