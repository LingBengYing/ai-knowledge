package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ProblemHandlerTest {
  @Test
  void problemContractHasNoSensitiveExceptionDetails() {
    var request = new MockHttpServletRequest("GET", "/v1/management/documents");
    var result =
        new ProblemHandler()
            .problem(
                new ApplicationException(FailureKind.NOT_FOUND, "not_found", "资料不可用。"), request);
    assertEquals(404, result.getStatusCode().value());
    assertEquals("not_found", result.getBody().errorCode());
    assertEquals("https://evidence.local/problems/not-found", result.getBody().type());
    assertEquals("/v1/management/documents", result.getBody().instance());
    var server =
        new ProblemHandler().unexpected(new IllegalStateException("PRIVATE signing key"), request);
    assertEquals(500, server.getStatusCode().value());
    assertFalse(server.getBody().toString().contains("PRIVATE"));
  }
}
