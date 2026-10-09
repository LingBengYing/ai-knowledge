package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.*;

import com.evidence.rag.controller.KnowledgeAgentCallbackController;
import com.evidence.rag.controller.KnowledgeAgentController;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.KnowledgeAgentService;
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
    assertEquals("db-gpt", controller.configuration(request).getBody().engine());
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
}
