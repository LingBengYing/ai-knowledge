package com.evidence.rag.controller;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.dto.SoundAnswerResult;
import com.evidence.rag.model.dto.SoundIndexResult;
import com.evidence.rag.model.dto.SoundSourceResult;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.SoundAnswerService;
import com.evidence.rag.service.SoundLibraryService;
import com.evidence.rag.web.MediaContentResponse;
import com.evidence.rag.web.converter.SoundRequestMapper;
import com.evidence.rag.web.converter.SoundResponseMapper;
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

/** Sound-specific HTTP adapter; authorization and complete scope stay in the Services. */
@RestController
@ConditionalOnProperty(prefix = "rag.sound", name = "enabled", havingValue = "true")
public final class SoundController {
  private final SoundLibraryService library;
  private final SoundAnswerService answers;

  public SoundController(SoundLibraryService library, SoundAnswerService answers) {
    this.library = library;
    this.answers = answers;
  }

  @GetMapping(
      value = "/v1/documents/{documentId}/sound-index",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public SoundIndexResult get(HttpServletRequest request, @PathVariable String documentId) {
    noBody(request);
    return SoundResponseMapper.index(
        library.get(AuthenticatedActor.require(request), documentId), library.soundModelRevision());
  }

  @PostMapping(
      value = "/v1/documents/{documentId}/sound-index",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public SoundIndexResult build(HttpServletRequest request, @PathVariable String documentId) {
    noBody(request);
    return SoundResponseMapper.index(
        library.build(AuthenticatedActor.require(request), documentId),
        library.soundModelRevision());
  }

  @PostMapping(
      value = "/v1/sound-answers",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public SoundAnswerResult answer(HttpServletRequest request, @RequestBody byte[] body) {
    if (request.getQueryString() != null) {
      throw ModelValues.invalid();
    }
    return answers.answer(AuthenticatedActor.require(request), SoundRequestMapper.command(body));
  }

  @GetMapping(
      value = "/v1/sound-sources/{answerId}/{ordinal}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public SoundSourceResult source(
      HttpServletRequest request, @PathVariable String answerId, @PathVariable int ordinal) {
    sourceRequest(request, ordinal);
    return answers.source(AuthenticatedActor.require(request), answerId, ordinal);
  }

  @GetMapping("/v1/sound-sources/{answerId}/{ordinal}/content")
  public ResponseEntity<byte[]> content(
      HttpServletRequest request, @PathVariable String answerId, @PathVariable int ordinal) {
    sourceRequest(request, ordinal);
    var original = answers.content(AuthenticatedActor.require(request), answerId, ordinal);
    return MediaContentResponse.create(
        original.mediaType(),
        original.content(),
        Collections.list(request.getHeaders(HttpHeaders.RANGE)));
  }

  private static void sourceRequest(HttpServletRequest request, int ordinal) {
    noBody(request);
    if (ordinal < 1 || ordinal > 32) {
      throw ModelValues.invalid();
    }
  }

  private static void noBody(HttpServletRequest request) {
    if (request.getQueryString() != null
        || request.getContentLengthLong() > 0
        || request.getHeader("Transfer-Encoding") != null) {
      throw ModelValues.invalid();
    }
  }
}
