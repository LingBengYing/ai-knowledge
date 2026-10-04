package com.evidence.rag.controller;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.dto.VisualAnswerResult;
import com.evidence.rag.model.dto.VisualSourceResult;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.VisualAnswerService;
import com.evidence.rag.service.ManagedTextRuntime;
import com.evidence.rag.web.converter.AnswerRequestMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
  private final ManagedTextRuntime runtime;

  public VisualAnswerController(@Nullable VisualAnswerService answers) {
    this.answers = answers;
    this.runtime = null;
  }

  @Autowired
  public VisualAnswerController(
      ObjectProvider<VisualAnswerService> answers, ObjectProvider<ManagedTextRuntime> runtime) {
    this.answers = answers.getIfAvailable();
    this.runtime = runtime.getIfAvailable();
  }

  private VisualAnswerService selected() {
    var selected = runtime == null ? answers : runtime.capture().visual();
    if (selected == null) {
      throw new ApplicationException(
          FailureKind.UNAVAILABLE, "text_configuration_required", "请先完成文字模型及对应媒体功能配置。");
    }
    return selected;
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
    var actor = AuthenticatedActor.require(request);
    return selected().answer(actor, AnswerRequestMapper.command(body));
  }

  @GetMapping(
      value = "/v1/visual-sources/{answerId}/{ordinal}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public VisualSourceResult source(
      HttpServletRequest request, @PathVariable String answerId, @PathVariable int ordinal) {
    validateSource(request, ordinal);
    var actor = AuthenticatedActor.require(request);
    return selected().source(actor, answerId, ordinal);
  }

  @GetMapping("/v1/visual-sources/{answerId}/{ordinal}/content")
  public ResponseEntity<byte[]> content(
      HttpServletRequest request, @PathVariable String answerId, @PathVariable int ordinal) {
    validateSource(request, ordinal);
    var actor = AuthenticatedActor.require(request);
    var original = selected().content(actor, answerId, ordinal);
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
