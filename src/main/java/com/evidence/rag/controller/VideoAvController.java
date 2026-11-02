package com.evidence.rag.controller;

import com.evidence.rag.model.dto.VideoAvIndexResult;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.VideoAvLibraryService;
import com.evidence.rag.web.converter.VideoAvResponseMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** VideoAv-specific HTTP adapter; authorization and complete scope stay in the Services. */
@RestController
@ConditionalOnProperty(prefix = "rag.video-av", name = "enabled", havingValue = "true")
public final class VideoAvController {
  private final VideoAvLibraryService library;

  public VideoAvController(VideoAvLibraryService library) {
    this.library = library;
  }

  @GetMapping(
      value = "/v1/documents/{documentId}/video-av-index",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public VideoAvIndexResult get(HttpServletRequest request, @PathVariable String documentId) {
    RequestGuards.noQueryOrBody(request);
    return VideoAvResponseMapper.index(
        library.get(AuthenticatedActor.require(request), documentId),
        library.profileFingerprint(),
        library.analysisModelRevision());
  }

  @PostMapping(
      value = "/v1/documents/{documentId}/video-av-index",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public VideoAvIndexResult build(HttpServletRequest request, @PathVariable String documentId) {
    RequestGuards.noQueryOrBody(request);
    return VideoAvResponseMapper.index(
        library.build(AuthenticatedActor.require(request), documentId),
        library.profileFingerprint(),
        library.analysisModelRevision());
  }
}
