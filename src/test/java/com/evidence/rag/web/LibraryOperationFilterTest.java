package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.LibraryOperationGate;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/** Actual filter bodies and maintenance errors have the same behavior under a servlet context. */
class LibraryOperationFilterTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"", "/kb"})
  void ordinaryBodyHoldsGateUntilItsActualChainReturns(String context) throws Exception {
    var gate = new LibraryOperationGate(directory);
    var filter =
        new LibraryOperationFilter(gate, new ProblemHandler(), JsonMapper.builder().build());
    var called = new AtomicBoolean();
    filter.doFilter(
        request(context, "/v1/documents"),
        new MockHttpServletResponse(),
        (request, response) -> {
          called.set(true);
          assertFalse(gate.isIdle());
          assertTrue(gate.tryMaintenance().isEmpty());
        });
    assertTrue(called.get());
    assertTrue(gate.isIdle());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "/kb"})
  void maintenanceBlocksOrdinaryBodyAndKeepsExplicitControlReadable(String context)
      throws Exception {
    var gate = new LibraryOperationGate(directory);
    var json = JsonMapper.builder().build();
    var filter = new LibraryOperationFilter(gate, new ProblemHandler(), json);
    try (var maintenance = gate.tryMaintenance().orElseThrow()) {
      var response = new MockHttpServletResponse();
      var called = new AtomicBoolean();
      filter.doFilter(
          request(context, "/v1/documents"), response, (request, result) -> called.set(true));
      assertFalse(called.get());
      assertEquals(503, response.getStatus());
      assertEquals(
          "migration_incomplete",
          json.readTree(response.getContentAsString()).path("error_code").asString());
      assertEquals("private, no-store", response.getHeader("Cache-Control"));
      for (String control :
          new String[] {
            "/v1/config", "/v1/documents/local/cleanup", "/v1/management/document-cleanups"
          }) {
        var readable = new AtomicBoolean();
        filter.doFilter(
            request(context, control),
            new MockHttpServletResponse(),
            (request, result) -> readable.set(true));
        assertTrue(readable.get(), control);
      }
    }
    assertTrue(gate.isIdle());
  }

  @Test
  void actualChainFailureReleasesItsOwnLease() {
    var gate = new LibraryOperationGate(directory);
    var filter =
        new LibraryOperationFilter(gate, new ProblemHandler(), JsonMapper.builder().build());
    assertThrows(
        IOException.class,
        () ->
            filter.doFilter(
                request("/kb", "/v1/documents"),
                new MockHttpServletResponse(),
                (request, response) -> {
                  throw new IOException("synthetic chain failure");
                }));
    assertTrue(gate.isIdle());
  }

  private static MockHttpServletRequest request(String context, String path) {
    var request = new MockHttpServletRequest("GET", context + path);
    request.setContextPath(context);
    return request;
  }
}
