package com.evidence.rag.controller;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.dto.ConversationResult;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.ConversationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated actor ownership applies even in shared-workspace mode. */
@RestController
public final class ConversationController {
  private final ConversationService conversations;

  public ConversationController(ConversationService conversations) {
    this.conversations = conversations;
  }

  @GetMapping(value = "/v1/knowledge-conversations", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ConversationResult.Page> list(
      HttpServletRequest request,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "30") int limit) {
    bodyless(request);
    if (!Set.of("offset", "limit").containsAll(request.getParameterMap().keySet())
        || request.getParameterMap().values().stream().anyMatch(values -> values.length != 1)) {
      throw ModelValues.invalid();
    }
    return response(conversations.list(AuthenticatedActor.require(request), offset, limit));
  }

  @PostMapping(
      value = "/v1/knowledge-conversations",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ConversationResult> create(
      HttpServletRequest request, @RequestBody Map<String, Object> body) {
    noQuery(request);
    if (!Set.of("title").containsAll(body.keySet())
        || (body.containsKey("title") && !(body.get("title") instanceof String))) {
      throw ModelValues.invalid();
    }
    return ResponseEntity.status(201)
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .body(
            conversations.create(AuthenticatedActor.require(request), (String) body.get("title")));
  }

  @GetMapping(
      value = "/v1/knowledge-conversations/{id}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ConversationResult.Detail> get(
      HttpServletRequest request,
      @PathVariable String id,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "20") int limit) {
    bodyless(request);
    if (!Set.of("offset", "limit").containsAll(request.getParameterMap().keySet())
        || request.getParameterMap().values().stream().anyMatch(values -> values.length != 1)) {
      throw ModelValues.invalid();
    }
    return response(conversations.get(AuthenticatedActor.require(request), id, offset, limit));
  }

  @PatchMapping(
      value = "/v1/knowledge-conversations/{id}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ConversationResult> rename(
      HttpServletRequest request, @PathVariable String id, @RequestBody Map<String, Object> body) {
    noQuery(request);
    if (!body.keySet().equals(Set.of("title")) || !(body.get("title") instanceof String title)) {
      throw ModelValues.invalid();
    }
    return response(conversations.rename(AuthenticatedActor.require(request), id, title));
  }

  @DeleteMapping("/v1/knowledge-conversations/{id}")
  public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable String id) {
    noQuery(request);
    bodyless(request);
    conversations.delete(AuthenticatedActor.require(request), id);
    return ResponseEntity.noContent()
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .build();
  }

  private static void bodyless(HttpServletRequest request) {
    if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null) {
      throw ModelValues.invalid();
    }
  }

  private static void noQuery(HttpServletRequest request) {
    if (request.getQueryString() != null) {
      throw ModelValues.invalid();
    }
  }

  private static <T> ResponseEntity<T> response(T value) {
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(value);
  }
}
