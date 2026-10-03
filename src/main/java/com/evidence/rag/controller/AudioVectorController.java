package com.evidence.rag.controller;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.vo.AudioVectorResponse;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.AudioVectorIndexingService;
import com.evidence.rag.web.converter.AudioVectorResponseMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bodyless HTTP Adapter; current read/edit authority and the build budget belong to the Service.
 */
@RestController
@ConditionalOnProperty(prefix = "rag.audio-embedding", name = "enabled", havingValue = "true")
public final class AudioVectorController {
  private final AudioVectorIndexingService vectors;

  public AudioVectorController(@Nullable AudioVectorIndexingService vectors) {
    this.vectors = vectors;
  }

  private AudioVectorIndexingService selected() {
    if (vectors == null) {
      throw new ApplicationException(
          FailureKind.UNAVAILABLE, "text_configuration_required", "请先完成文字模型及对应媒体功能配置。");
    }
    return vectors;
  }

  @GetMapping(
      value = "/v1/documents/{documentId}/audio-vector",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public AudioVectorResponse get(HttpServletRequest request, @PathVariable String documentId) {
    noQueryOrBody(request);
    var actor = AuthenticatedActor.require(request);
    return AudioVectorResponseMapper.response(selected().get(actor, documentId));
  }

  @PostMapping(
      value = "/v1/documents/{documentId}/audio-vector",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public AudioVectorResponse build(HttpServletRequest request, @PathVariable String documentId) {
    noQueryOrBody(request);
    var actor = AuthenticatedActor.require(request);
    return AudioVectorResponseMapper.response(selected().build(actor, documentId));
  }

  private static void noQueryOrBody(HttpServletRequest request) {
    if (request.getQueryString() != null
        || request.getContentLengthLong() > 0
        || request.getHeader("Transfer-Encoding") != null) {
      throw ModelValues.invalid();
    }
  }
}
