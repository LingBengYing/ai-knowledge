package com.evidence.rag.controller;

import com.evidence.rag.model.dto.SoundIndexResult;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.SoundLibraryService;
import com.evidence.rag.web.converter.SoundResponseMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sound-specific HTTP adapter; authorization and complete scope stay in the Services. */
@RestController
@ConditionalOnProperty(prefix = "rag.sound", name = "enabled", havingValue = "true")
public final class SoundController {
  private final SoundLibraryService library;

  public SoundController(SoundLibraryService library) {
    this.library = library;
  }

  @GetMapping(
      value = "/v1/documents/{documentId}/sound-index",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public SoundIndexResult get(HttpServletRequest request, @PathVariable String documentId) {
    RequestGuards.noQueryOrBody(request);
    return SoundResponseMapper.index(
        library.get(AuthenticatedActor.require(request), documentId), library.soundModelRevision());
  }

  @PostMapping(
      value = "/v1/documents/{documentId}/sound-index",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public SoundIndexResult build(HttpServletRequest request, @PathVariable String documentId) {
    RequestGuards.noQueryOrBody(request);
    return SoundResponseMapper.index(
        library.build(AuthenticatedActor.require(request), documentId),
        library.soundModelRevision());
  }
}
