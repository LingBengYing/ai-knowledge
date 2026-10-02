package com.evidence.rag.controller;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.dto.AudioAnswerResult;
import com.evidence.rag.model.dto.AudioSourceResult;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.AnswerService;
import com.evidence.rag.web.AudioContentResponse;
import com.evidence.rag.web.converter.AnswerRequestMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Typed audio HTTP Adapter sharing the answer Module's admission and current-source authority. */
@RestController
@ConditionalOnExpression("${rag.audio.enabled:false} && ${rag.answers.enabled:false}")
public final class AudioAnswerController {
  private final AnswerService answers;

  public AudioAnswerController(AnswerService answers) {
    this.answers = answers;
  }

  @PostMapping(
      value = "/v1/audio-answers",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public AudioAnswerResult answer(
      HttpServletRequest request, @RequestBody Map<String, Object> body) {
    if (request.getQueryString() != null) {
      throw ModelValues.invalid();
    }
    return answers.answerAudio(
        AuthenticatedActor.require(request), AnswerRequestMapper.command(body));
  }

  @GetMapping(
      value = "/v1/audio-sources/{answerId}/{ordinal}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public AudioSourceResult source(
      HttpServletRequest request, @PathVariable String answerId, @PathVariable int ordinal) {
    validateSource(request, ordinal);
    return answers.audioSource(AuthenticatedActor.require(request), answerId, ordinal);
  }

  @GetMapping("/v1/audio-sources/{answerId}/{ordinal}/content")
  public ResponseEntity<byte[]> content(
      HttpServletRequest request, @PathVariable String answerId, @PathVariable int ordinal) {
    validateSource(request, ordinal);
    var original = answers.audioContent(AuthenticatedActor.require(request), answerId, ordinal);
    // Resolve the entire current-authorized source before Range parsing can expose its length.
    return AudioContentResponse.create(
        original, Collections.list(request.getHeaders(HttpHeaders.RANGE)));
  }

  private static void validateSource(HttpServletRequest request, int ordinal) {
    if (ordinal < 1
        || ordinal > 32
        || request.getQueryString() != null
        || request.getContentLengthLong() > 0
        || request.getHeader("Transfer-Encoding") != null) {
      throw ModelValues.invalid();
    }
  }
}
