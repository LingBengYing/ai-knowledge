package com.evidence.rag.controller;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.dto.VideoAnswerResult;
import com.evidence.rag.model.dto.VideoSourceResult;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.AnswerService;
import com.evidence.rag.service.ManagedTextRuntime;
import com.evidence.rag.web.MediaContentResponse;
import com.evidence.rag.web.converter.VideoAnswerRequestMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Typed video HTTP Adapter; the Service resolves current authorization before reading bytes. */
@RestController
@ConditionalOnExpression("${rag.video.enabled:false} && ${rag.answers.enabled:false}")
public final class VideoAnswerController {
  private final AnswerService answers;
  private final ManagedTextRuntime runtime;

  public VideoAnswerController(AnswerService answers) {
    this.answers = answers;
    this.runtime = null;
  }

  @Autowired
  public VideoAnswerController(
      ObjectProvider<AnswerService> answers, ObjectProvider<ManagedTextRuntime> runtime) {
    this.answers = answers.getIfAvailable();
    this.runtime = runtime.getIfAvailable();
  }

  private AnswerService selected() {
    return runtime == null ? answers : runtime.capture().answers();
  }

  @PostMapping(
      value = "/v1/video-answers",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public VideoAnswerResult answer(
      HttpServletRequest request, @RequestBody Map<String, Object> body) {
    if (request.getQueryString() != null) {
      throw ModelValues.invalid();
    }
    var command = VideoAnswerRequestMapper.command(body);
    var actor = AuthenticatedActor.require(request);
    if (command.subtitle()) {
      return selected().answerVideoSubtitle(actor, command.answer());
    }
    if (command.ocr()) {
      return selected().answerVideoOcr(actor, command.answer());
    }
    return selected().answerVideo(actor, command.answer(), command.mode());
  }

  @GetMapping(
      value = "/v1/video-sources/{answerId}/{ordinal}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public VideoSourceResult source(
      HttpServletRequest request, @PathVariable String answerId, @PathVariable int ordinal) {
    validateSource(request, ordinal);
    var actor = AuthenticatedActor.require(request);
    return selected().videoSource(actor, answerId, ordinal);
  }

  @GetMapping("/v1/video-sources/{answerId}/{ordinal}/frame")
  public ResponseEntity<byte[]> frame(
      HttpServletRequest request, @PathVariable String answerId, @PathVariable int ordinal) {
    validateSource(request, ordinal);
    var actor = AuthenticatedActor.require(request);
    var image = selected().videoFrame(actor, answerId, ordinal);
    byte[] bytes = image.content();
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(image.mediaType()))
        .header("Cache-Control", "no-store")
        .header("X-Content-Type-Options", "nosniff")
        .contentLength(bytes.length)
        .body(bytes);
  }

  @GetMapping("/v1/video-sources/{answerId}/{ordinal}/content")
  public ResponseEntity<byte[]> content(
      HttpServletRequest request, @PathVariable String answerId, @PathVariable int ordinal) {
    validateSource(request, ordinal);
    var actor = AuthenticatedActor.require(request);
    var video = selected().videoContent(actor, answerId, ordinal);
    // Resolve all current-authorized source bytes before parsing Range or exposing their length.
    return MediaContentResponse.create(
        video.mediaType(),
        video.content(),
        Collections.list(request.getHeaders(HttpHeaders.RANGE)));
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
