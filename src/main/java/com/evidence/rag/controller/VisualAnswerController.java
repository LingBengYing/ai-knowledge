package com.evidence.rag.controller;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.dto.VisualAnswerResult;
import com.evidence.rag.model.dto.VisualSourceResult;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.VisualAnswerService;
import com.evidence.rag.web.converter.AnswerRequestMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Typed image-only HTTP contract; existing text excerpt endpoints are not repurposed. */
@RestController
@ConditionalOnProperty(prefix = "rag.visual", name = "enabled", havingValue = "true")
public final class VisualAnswerController {
  private final VisualAnswerService answers;

  public VisualAnswerController(VisualAnswerService answers) {
    this.answers = answers;
  }

  @PostMapping(
      value = "/v1/visual-answers",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public VisualAnswerResult answer(
      HttpServletRequest request, @RequestBody Map<String, Object> body) {
    if (request.getQueryString() != null) {
      throw invalid();
    }
    return answers.answer(AuthenticatedActor.require(request), AnswerRequestMapper.command(body));
  }

  @GetMapping(
      value = "/v1/visual-sources/{answerId}/{ordinal}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public VisualSourceResult source(
      HttpServletRequest request, @PathVariable String answerId, @PathVariable int ordinal) {
    validateSource(request, ordinal);
    return answers.source(AuthenticatedActor.require(request), answerId, ordinal);
  }

  @GetMapping("/v1/visual-sources/{answerId}/{ordinal}/content")
  public ResponseEntity<byte[]> content(
      HttpServletRequest request, @PathVariable String answerId, @PathVariable int ordinal) {
    validateSource(request, ordinal);
    var original = answers.content(AuthenticatedActor.require(request), answerId, ordinal);
    byte[] bytes = original.content();
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(original.mediaType()))
        .header("Cache-Control", "no-store")
        .header("X-Content-Type-Options", "nosniff")
        .contentLength(bytes.length)
        .body(bytes);
  }

  private static void validateSource(HttpServletRequest request, int ordinal) {
    if (ordinal < 1
        || ordinal > 32
        || request.getQueryString() != null
        || request.getContentLengthLong() > 0
        || request.getHeader("Transfer-Encoding") != null) {
      throw invalid();
    }
  }

  private static ApplicationException invalid() {
    return new ApplicationException(FailureKind.INVALID_INPUT, "invalid_request", "问答或来源请求参数无效。");
  }
}
