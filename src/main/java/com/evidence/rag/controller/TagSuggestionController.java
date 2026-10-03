package com.evidence.rag.controller;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.vo.DocumentResponse;
import com.evidence.rag.model.vo.TagSuggestionResponse;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.TagSuggestionService;
import com.evidence.rag.web.converter.ManagementResponseMapper;
import com.evidence.rag.web.converter.TagSuggestionRequestMapper;
import com.evidence.rag.web.converter.TagSuggestionResponseMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP Adapter for explicit preview and confirmation; the Service owns the authority transaction.
 */
@RestController
@ConditionalOnProperty(prefix = "rag.synopsis", name = "enabled", havingValue = "true")
public final class TagSuggestionController {
  private final TagSuggestionService suggestions;

  public TagSuggestionController(TagSuggestionService suggestions) {
    this.suggestions = suggestions;
  }

  @GetMapping(
      value = "/v1/documents/{documentId}/tag-suggestions",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public TagSuggestionResponse get(HttpServletRequest request, @PathVariable String documentId) {
    if (request.getQueryString() != null
        || request.getContentLengthLong() > 0
        || request.getHeader("Transfer-Encoding") != null) {
      throw ModelValues.invalid();
    }
    return TagSuggestionResponseMapper.response(
        suggestions.get(AuthenticatedActor.require(request), documentId));
  }

  @PostMapping(
      value = "/v1/documents/{documentId}/tag-suggestions/apply",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public DocumentResponse apply(
      HttpServletRequest request,
      @PathVariable String documentId,
      @RequestBody Map<String, Object> body) {
    if (request.getQueryString() != null) {
      throw ModelValues.invalid();
    }
    return ManagementResponseMapper.document(
        suggestions.apply(
            AuthenticatedActor.require(request),
            documentId,
            TagSuggestionRequestMapper.command(body)));
  }
}
