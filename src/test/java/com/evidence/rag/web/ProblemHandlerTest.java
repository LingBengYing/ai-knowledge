package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.*;

import com.evidence.rag.shared.Problem;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ProblemHandlerTest {
  @Test
  void problemContractHasNoSensitiveExceptionDetails() {
    var request = new MockHttpServletRequest("GET", "/v1/management/documents");
    var result = new ProblemHandler().problem(new Problem(404, "not_found", "资料不可用。"), request);
    assertEquals(404, result.getStatusCode().value());
    assertEquals("not_found", result.getBody().get("error_code"));
    assertEquals("https://evidence.local/problems/not-found", result.getBody().get("type"));
    assertEquals("/v1/management/documents", result.getBody().get("instance"));
    var server =
        new ProblemHandler().unexpected(new IllegalStateException("PRIVATE signing key"), request);
    assertEquals(500, server.getStatusCode().value());
    assertFalse(server.getBody().toString().contains("PRIVATE"));
  }
}
