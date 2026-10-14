package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.evidence.rag.controller.RetrievalSettingsController;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.repository.RetrievalSettingsRepository;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.RetrievalSettingsService;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class ProblemHandlerTest {
  @TempDir Path directory;

  @Test
  void unacceptableAcceptOnAJsonEndpointIsA406ProblemNotAServerFault() throws Exception {
    var service =
        new RetrievalSettingsService(
            new RetrievalSettingsRepository(directory.resolve("settings.json"), "org"), "org");
    var mvc =
        MockMvcBuilders.standaloneSetup(new RetrievalSettingsController(service))
            .setControllerAdvice(new ProblemHandler())
            .build();
    var response =
        mvc.perform(
                get("/v1/retrieval-settings")
                    .accept(MediaType.TEXT_HTML)
                    .requestAttr(AuthenticatedActor.class.getName(), new Actor("org", "reader")))
            .andReturn()
            .getResponse();
    assertEquals(406, response.getStatus());
    assertEquals(MediaType.APPLICATION_PROBLEM_JSON_VALUE, response.getContentType());
    var body = JsonMapper.builder().build().readTree(response.getContentAsString());
    assertEquals(406, body.path("status").intValue());
    assertEquals("not_acceptable", body.path("error_code").stringValue());
  }

  @Test
  void springClientErrorsKeepTheirStatusWithoutEchoingDetailsWhileFaultsStay500() {
    var handler = new ProblemHandler();
    var request = new MockHttpServletRequest("GET", "/v1/example");
    var rejected =
        handler.unexpected(
            new ResponseStatusException(
                org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE, "private detail"),
            request);
    assertEquals(413, rejected.getStatusCode().value());
    assertEquals("invalid_request", rejected.getBody().errorCode());
    assertFalse(rejected.getBody().toString().contains("private detail"));
    var fault =
        handler.unexpected(
            new ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_GATEWAY, "private detail"),
            request);
    assertEquals(500, fault.getStatusCode().value());
    assertEquals("internal_error", fault.getBody().errorCode());
    assertEquals(
        500, handler.unexpected(new IllegalStateException("x"), request).getStatusCode().value());
  }

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
