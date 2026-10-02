package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.controller.AudioAnswerController;
import com.evidence.rag.exception.ApplicationException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class AudioAnswerControllerTest {
  private final AudioAnswerController controller = new AudioAnswerController(null);

  @Test
  void sourceRequestShapeIsRejectedBeforeAnyUseCase() {
    for (int ordinal : new int[] {-1, 0, 33}) {
      assertInvalid(() -> controller.source(new MockHttpServletRequest(), "trace", ordinal));
      assertInvalid(() -> controller.content(new MockHttpServletRequest(), "trace", ordinal));
    }
    var query = new MockHttpServletRequest();
    query.setQueryString("start_ms=0");
    var body = new MockHttpServletRequest();
    body.setContent(new byte[] {1});
    var transfer = new MockHttpServletRequest();
    transfer.addHeader("Transfer-Encoding", "chunked");
    for (var request : List.of(query, body, transfer)) {
      assertInvalid(() -> controller.source(request, "trace", 1));
      assertInvalid(() -> controller.content(request, "trace", 1));
    }
    assertInvalid(() -> controller.answer(query, Map.of("question", "合成问题？")));
  }

  @Test
  void untrustedCallerCannotReachSourceOrRangeParsing() {
    var request = new MockHttpServletRequest();
    request.addHeader("Range", "bytes=9999999999999999999999-");
    assertEquals(
        "unauthenticated",
        assertThrows(ApplicationException.class, () -> controller.content(request, "trace", 1))
            .code());
    assertEquals(
        "unauthenticated",
        assertThrows(ApplicationException.class, () -> controller.source(request, "trace", 1))
            .code());
    assertEquals(
        "unauthenticated",
        assertThrows(
                ApplicationException.class,
                () -> controller.answer(request, Map.of("question", "合成问题？")))
            .code());
  }

  private static void assertInvalid(org.junit.jupiter.api.function.Executable action) {
    assertEquals("invalid_request", assertThrows(ApplicationException.class, action).code());
  }
}
