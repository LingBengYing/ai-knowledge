package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.controller.AudioVectorController;
import com.evidence.rag.exception.ApplicationException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class AudioVectorControllerTest {
  private final AudioVectorController controller = new AudioVectorController(null);

  @Test
  void bothExactOperationsRejectAnyQueryOrBodyBeforeTheUseCase() {
    var query = new MockHttpServletRequest();
    query.setQueryString("");
    var body = new MockHttpServletRequest();
    body.setContent(new byte[] {1});
    var transfer = new MockHttpServletRequest();
    transfer.addHeader("Transfer-Encoding", "chunked");
    for (var request : List.of(query, body, transfer)) {
      assertEquals(
          "invalid_request",
          assertThrows(ApplicationException.class, () -> controller.get(request, "doc")).code());
      assertEquals(
          "invalid_request",
          assertThrows(ApplicationException.class, () -> controller.build(request, "doc")).code());
    }
  }

  @Test
  void neitherReadNorBuildAcceptsAnUntrustedActor() {
    var request = new MockHttpServletRequest();
    assertEquals(
        "unauthenticated",
        assertThrows(ApplicationException.class, () -> controller.get(request, "doc")).code());
    assertEquals(
        "unauthenticated",
        assertThrows(ApplicationException.class, () -> controller.build(request, "doc")).code());
  }
}
