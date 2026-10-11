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
      checked.add(message(item));
    }
    return response(agents.model(id, bearer, new AgentProtocol.ModelRequest(checked)));
  }

  private static AgentMessage message(Object item) {
    if (!(item instanceof Map<?, ?> value) || !(value.get("role") instanceof String role)) {
      throw ModelValues.invalid();
    }
    if ("assistant".equals(role) && value.containsKey("tool_calls")) {
      if (!Set.of("role", "content", "tool_calls", "reasoning_content").containsAll(value.keySet())
          || value.get("content") != null
          || (value.get("reasoning_content") != null
              && !(value.get("reasoning_content") instanceof String))
          || !(value.get("tool_calls") instanceof List<?> calls)) throw ModelValues.invalid();
      var checked = new ArrayList<AgentMessage.ToolCall>();
      for (var itemCall : calls) {
        if (!(itemCall instanceof Map<?, ?> call)
            || !call.keySet().equals(Set.of("id", "type", "function"))
            || !(call.get("id") instanceof String id)
            || !(call.get("type") instanceof String type)
            || !(call.get("function") instanceof Map<?, ?> function)
            || !function.keySet().equals(Set.of("name", "arguments"))
            || !(function.get("name") instanceof String name)
            || !(function.get("arguments") instanceof String arguments))
          throw ModelValues.invalid();
        checked.add(
            new AgentMessage.ToolCall(id, type, new AgentMessage.FunctionCall(name, arguments)));
      }
      return new AgentMessage(role, null, checked, null, (String) value.get("reasoning_content"));
    }
    if (!(value.get("content") instanceof String content)) throw ModelValues.invalid();
    if ("tool".equals(role)) {
      if (!value.keySet().equals(Set.of("role", "content", "tool_call_id"))
          || !(value.get("tool_call_id") instanceof String callId)) throw ModelValues.invalid();
      return new AgentMessage(role, content, null, callId);
    }
    if (!value.keySet().equals(Set.of("role", "content"))) throw ModelValues.invalid();
    return new AgentMessage(role, content);
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
