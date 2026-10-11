package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.*;

import com.evidence.rag.controller.KnowledgeAgentCallbackController;
import com.evidence.rag.controller.KnowledgeAgentController;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.dto.AgentMessage;
import com.evidence.rag.model.dto.AgentProtocol;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.KnowledgeAgentService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;

class KnowledgeAgentControllerTest {
  @Test
  void defaultDisabledIsExplicitAndStillRequiresTrustedIdentity() {
    var controller =
        new KnowledgeAgentController(
            new StaticListableBeanFactory().getBeanProvider(KnowledgeAgentService.class));
    var request = new MockHttpServletRequest();
    assertThrows(ApplicationException.class, () -> controller.configuration(request));
    request.setAttribute(AuthenticatedActor.class.getName(), new Actor("org-main", "owner"));
    assertFalse(controller.configuration(request).getBody().enabled());
    assertEquals("knowledge-native-agent", controller.configuration(request).getBody().engine());
    assertEquals(
        "agent_disabled",
        assertThrows(
                ApplicationException.class,
                () ->
                    controller.start(
                        request,
                        Map.of(
                            "question",
                            "问题",
                            "request_id",
                            "d0f38479-9858-49a2-9852-50d72be67142")))
            .code());
  }

  @Test
  void privateCallbacksRejectRemoteOriginDuplicateCredentialAndArbitraryShapeBeforeService() {
    var controller = new KnowledgeAgentCallbackController(null);
    var request = new MockHttpServletRequest();
    request.setRemoteAddr("203.0.113.1");
    request.addHeader("Authorization", "Bearer private-run-token");
    assertEquals(
        "agent_callback_denied",
        assertThrows(
                ApplicationException.class,
                () -> controller.search(request, "id", Map.of("query", "资料")))
            .code());
    request.setRemoteAddr("127.0.0.1");
    request.addHeader("Origin", "http://localhost");
    assertThrows(
        ApplicationException.class, () -> controller.search(request, "id", Map.of("query", "资料")));
    request.removeHeader("Origin");
    request.addHeader("Authorization", "Bearer duplicated");
    assertThrows(
        ApplicationException.class, () -> controller.search(request, "id", Map.of("query", "资料")));
    request.removeHeader("Authorization");
    request.addHeader("Authorization", "Bearer private-run-token");
    assertThrows(
        ApplicationException.class,
        () -> controller.search(request, "id", Map.of("query", "资料", "actor", "foreign")));
    assertThrows(
        ApplicationException.class,
        () ->
            controller.model(
                request,
                "id",
                Map.of("messages", java.util.List.of(Map.of("role", "tool", "content", "x")))));
    assertThrows(
        ApplicationException.class,
        () -> controller.read(request, "id", Map.of("source_ids", java.util.List.of(5))));
  }

  @Test
  void privateModelCallbackRetainsNativeHistoryAndReturnsOnlyNativeAssistantMessage() {
    var agents = org.mockito.Mockito.mock(KnowledgeAgentService.class);
    var controller = new KnowledgeAgentCallbackController(agents);
    var request = new MockHttpServletRequest();
    request.setRemoteAddr("127.0.0.1");
    request.addHeader("Authorization", "Bearer private-run-token");
    var call =
        new AgentMessage.ToolCall(
            "call-search",
            "function",
            new AgentMessage.FunctionCall("knowledge_search", "{\"query\":\"topic\"}"));
    var assistant =
        new AgentMessage("assistant", null, List.of(call), null, "fixture native reasoning");
    var expected =
        new AgentProtocol.ModelRequest(
            List.of(
                new AgentMessage("user", "topic"),
                assistant,
                new AgentMessage("tool", "{\"sources\":[]}", null, "call-search")));
    org.mockito.Mockito.when(agents.model("run", "Bearer private-run-token", expected))
        .thenReturn(new AgentProtocol.ModelResult(assistant));
    var body =
        Map.<String, Object>of(
            "messages",
            List.of(
                Map.of("role", "user", "content", "topic"),
                Map.of(
                    "role",
                    "assistant",
                    "reasoning_content",
                    "fixture native reasoning",
                    "tool_calls",
                    List.of(
                        Map.of(
                            "id",
                            "call-search",
                            "type",
                            "function",
                            "function",
                            Map.of(
                                "name",
                                "knowledge_search",
                                "arguments",
                                "{\"query\":\"topic\"}")))),
                Map.of(
                    "role", "tool", "tool_call_id", "call-search", "content", "{\"sources\":[]}")));
    var result = controller.model(request, "run", body);
    assertEquals(assistant, result.getBody().message());
    var json = new tools.jackson.databind.json.JsonMapper().valueToTree(result.getBody());
    assertEquals(java.util.Set.of("message"), json.propertyNames());
    assertFalse(json.path("message").has("tool_call_id"));
    assertFalse(json.path("message").has("content"));
    assertEquals(
        "fixture native reasoning", json.path("message").path("reasoning_content").asString());
    assertEquals(
        "call-search", json.path("message").path("tool_calls").get(0).path("id").asString());
    org.mockito.Mockito.verify(agents).model("run", "Bearer private-run-token", expected);
  }
}
