package com.evidence.rag.controller;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.dto.AgentMessage;
import com.evidence.rag.model.dto.AgentProtocol;
import com.evidence.rag.service.KnowledgeAgentService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Private run capabilities, deliberately separate from public Actor authentication. */
@RestController
@ConditionalOnProperty(prefix = "rag.knowledge-agent", name = "enabled", havingValue = "true")
public final class KnowledgeAgentCallbackController {
  private final KnowledgeAgentService agents;

  public KnowledgeAgentCallbackController(KnowledgeAgentService agents) {
    this.agents = agents;
  }

  @PostMapping(
      value = "/internal/knowledge-agent/runs/{id}/model",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AgentProtocol.ModelResult> model(
      HttpServletRequest request, @PathVariable String id, @RequestBody Map<String, Object> body) {
    String bearer = credential(request);
    if (!body.keySet().equals(Set.of("messages"))
        || !(body.get("messages") instanceof List<?> messages)) throw ModelValues.invalid();
    var checked = new ArrayList<AgentMessage>();
    for (var item : messages) {
      if (!(item instanceof Map<?, ?> message)
          || !message.keySet().equals(Set.of("role", "content"))
          || !(message.get("role") instanceof String role)
          || !(message.get("content") instanceof String content)) throw ModelValues.invalid();
      checked.add(new AgentMessage(role, content));
    }
    return response(agents.model(id, bearer, new AgentProtocol.ModelRequest(checked)));
  }

  @PostMapping(
      value = "/internal/knowledge-agent/runs/{id}/search",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AgentProtocol.SearchResult> search(
      HttpServletRequest request, @PathVariable String id, @RequestBody Map<String, Object> body) {
    String bearer = credential(request);
    if (!body.keySet().equals(Set.of("query")) || !(body.get("query") instanceof String query))
      throw ModelValues.invalid();
    return response(agents.search(id, bearer, query));
  }

  @PostMapping(
      value = "/internal/knowledge-agent/runs/{id}/read",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AgentProtocol.ReadResult> read(
      HttpServletRequest request, @PathVariable String id, @RequestBody Map<String, Object> body) {
    String bearer = credential(request);
    if (!body.keySet().equals(Set.of("source_ids"))
        || !(body.get("source_ids") instanceof List<?> ids)
        || ids.stream().anyMatch(value -> !(value instanceof String))) throw ModelValues.invalid();
    return response(agents.read(id, bearer, ids.stream().map(String.class::cast).toList()));
  }

  private static String credential(HttpServletRequest request) {
    if (!Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(request.getRemoteAddr())) {
      throw new ApplicationException(
          FailureKind.FORBIDDEN, "agent_callback_denied", "内部任务调用未通过认证。");
    }
    var headers = Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION));
    if (headers.size() != 1
        || request.getHeader("Origin") != null
        || request.getQueryString() != null
        || request.getContentLengthLong() > 2 * 1024 * 1024) throw ModelValues.invalid();
    return headers.getFirst();
  }

  private static <T> ResponseEntity<T> response(T result) {
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(result);
  }
}
