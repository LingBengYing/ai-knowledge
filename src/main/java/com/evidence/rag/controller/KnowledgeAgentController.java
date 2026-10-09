package com.evidence.rag.controller;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.dto.AgentConfiguration;
import com.evidence.rag.model.dto.AgentRunResult;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.KnowledgeAgentService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Existing trusted Actor authentication applies to every public Agent route. */
@RestController
public final class KnowledgeAgentController {
  private final ObjectProvider<KnowledgeAgentService> services;

  public KnowledgeAgentController(ObjectProvider<KnowledgeAgentService> services) {
    this.services = services;
  }

  @GetMapping(value = "/v1/knowledge-agent/config", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AgentConfiguration> configuration(HttpServletRequest request) {
    read(request);
    AuthenticatedActor.require(request);
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .body(
            new AgentConfiguration(
                services.getIfAvailable() != null, "db-gpt", KnowledgeAgentService.MAX_STEPS));
  }

  @PostMapping(
      value = "/v1/knowledge-agent/runs",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AgentRunResult> start(
      HttpServletRequest request, @RequestBody Map<String, Object> body) {
    if (request.getQueryString() != null
        || !body.keySet().equals(Set.of("question", "request_id"))
        || !(body.get("question") instanceof String question)
        || !(body.get("request_id") instanceof String requestId)) throw ModelValues.invalid();
    return ResponseEntity.accepted()
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .body(service().start(AuthenticatedActor.require(request), question, requestId));
  }

  @GetMapping(value = "/v1/knowledge-agent/runs/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AgentRunResult> get(HttpServletRequest request, @PathVariable String id) {
    read(request);
    return response(service().get(AuthenticatedActor.require(request), id));
  }

  @PostMapping(
      value = "/v1/knowledge-agent/runs/{id}/cancel",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AgentRunResult> cancel(
      HttpServletRequest request, @PathVariable String id, @RequestBody Map<String, Object> body) {
    if (request.getQueryString() != null || !body.isEmpty()) throw ModelValues.invalid();
    return response(service().cancel(AuthenticatedActor.require(request), id));
  }

  private KnowledgeAgentService service() {
    var service = services.getIfAvailable();
    if (service == null)
      throw new ApplicationException(FailureKind.UNAVAILABLE, "agent_disabled", "知识助手尚未启用。");
    return service;
  }

  private static void read(HttpServletRequest request) {
    if (request.getQueryString() != null
        || request.getContentLengthLong() > 0
        || request.getHeader("Transfer-Encoding") != null) throw ModelValues.invalid();
  }

  private static ResponseEntity<AgentRunResult> response(AgentRunResult result) {
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(result);
  }
}
