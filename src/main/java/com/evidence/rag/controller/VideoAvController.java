package com.evidence.rag.controller;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.dto.VideoAvAnswerResult;
import com.evidence.rag.model.dto.VideoAvIndexResult;
import com.evidence.rag.model.dto.VideoAvSourceResult;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.VideoAvAnswerService;
import com.evidence.rag.service.VideoAvLibraryService;
import com.evidence.rag.web.MediaContentResponse;
import com.evidence.rag.web.converter.VideoAvRequestMapper;
import com.evidence.rag.web.converter.VideoAvResponseMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** VideoAv-specific HTTP adapter; authorization and complete scope stay in the Services. */
@RestController
@ConditionalOnProperty(prefix = "rag.video-av", name = "enabled", havingValue = "true")
public final class VideoAvController {
  private final VideoAvLibraryService library;
  private final VideoAvAnswerService answers;

  public VideoAvController(VideoAvLibraryService library, VideoAvAnswerService answers) {
    this.library = library;
    this.answers = answers;
  }

  @GetMapping(
      value = "/v1/documents/{documentId}/video-av-index",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public VideoAvIndexResult get(HttpServletRequest request, @PathVariable String documentId) {
    noBody(request);
    return VideoAvResponseMapper.index(
        library.get(AuthenticatedActor.require(request), documentId),
        library.profileFingerprint(),
        library.analysisModelRevision());
  }

  @PostMapping(
      value = "/v1/documents/{documentId}/video-av-index",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public VideoAvIndexResult build(HttpServletRequest request, @PathVariable String documentId) {
    noBody(request);
    return VideoAvResponseMapper.index(
        library.build(AuthenticatedActor.require(request), documentId),
        library.profileFingerprint(),
        library.analysisModelRevision());
  }

  @PostMapping(
      value = "/v1/video-av-answers",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public VideoAvAnswerResult answer(HttpServletRequest request, @RequestBody byte[] body) {
    if (request.getQueryString() != null) {
      throw ModelValues.invalid();
    }
    return answers.answer(AuthenticatedActor.require(request), VideoAvRequestMapper.command(body));
  }

  @GetMapping(
      value = "/v1/video-av-sources/{answerId}/{ordinal}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public VideoAvSourceResult source(
      HttpServletRequest request, @PathVariable String answerId, @PathVariable String ordinal) {
    int number = sourceRequest(request, ordinal);
    return answers.source(AuthenticatedActor.require(request), answerId, number);
  }

  @GetMapping("/v1/video-av-sources/{answerId}/{ordinal}/content")
  public ResponseEntity<byte[]> content(
      HttpServletRequest request, @PathVariable String answerId, @PathVariable String ordinal) {
    int number = sourceRequest(request, ordinal);
    var original = answers.content(AuthenticatedActor.require(request), answerId, number);
    return MediaContentResponse.create(
        original.mediaType(),
        original.content(),
        Collections.list(request.getHeaders(HttpHeaders.RANGE)));
  }

  private static int sourceRequest(HttpServletRequest request, String ordinal) {
    noBody(request);
    if (ordinal == null || !ordinal.matches("(?:[1-9]|[12][0-9]|3[0-2])")) {
      throw ModelValues.invalid();
    }
    return Integer.parseInt(ordinal);
  }

  private static void noBody(HttpServletRequest request) {
    if (request.getQueryString() != null
        || request.getContentLengthLong() > 0
        || request.getHeader("Transfer-Encoding") != null) {
      throw ModelValues.invalid();
    }
  }
}
