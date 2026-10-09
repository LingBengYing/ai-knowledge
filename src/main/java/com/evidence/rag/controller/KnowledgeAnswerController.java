package com.evidence.rag.controller;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.dto.KnowledgeAnswerResult;
import com.evidence.rag.model.dto.KnowledgeSourceResult;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.KnowledgeAnswerService;
import com.evidence.rag.web.converter.AnswerRequestMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Ordinary knowledge questions with typed document/video-text citations. */
@RestController
@ConditionalOnProperty(prefix = "rag.model-configuration", name = "enabled", havingValue = "true")
public final class KnowledgeAnswerController {
  private final KnowledgeAnswerService answers;

  public KnowledgeAnswerController(KnowledgeAnswerService answers) {
    this.answers = answers;
  }

  @PostMapping(
      value = "/v1/knowledge-answers",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<KnowledgeAnswerResult> answer(
      HttpServletRequest request, @RequestBody Map<String, Object> body) {
    if (request.getQueryString() != null) {
      throw ModelValues.invalid();
    }
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .body(
            answers.answer(AuthenticatedActor.require(request), AnswerRequestMapper.command(body)));
  }

  @GetMapping(
      value = "/v1/knowledge-sources/{answerId}/{ordinal}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<KnowledgeSourceResult> source(
      HttpServletRequest request, @PathVariable String answerId, @PathVariable int ordinal) {
    if (ordinal < 1
        || request.getQueryString() != null
        || request.getContentLengthLong() > 0
        || request.getHeader("Transfer-Encoding") != null) {
      throw ModelValues.invalid();
    }
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .body(answers.source(AuthenticatedActor.require(request), answerId, ordinal));
  }
}
