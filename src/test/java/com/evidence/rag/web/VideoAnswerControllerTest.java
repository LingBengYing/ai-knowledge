package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.controller.VideoAnswerController;
import com.evidence.rag.exception.ApplicationException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class VideoAnswerControllerTest {
  private final VideoAnswerController controller = new VideoAnswerController(null);

  @Test
  void forgedSourceShapesCannotReachTheService() {
    for (int ordinal : new int[] {-1, 0, 33}) {
      assertInvalid(() -> controller.source(new MockHttpServletRequest(), "trace", ordinal));
      assertInvalid(() -> controller.frame(new MockHttpServletRequest(), "trace", ordinal));
      assertInvalid(() -> controller.content(new MockHttpServletRequest(), "trace", ordinal));
    }
    var query = new MockHttpServletRequest();
    query.setQueryString("frame_us=123");
    var body = new MockHttpServletRequest();
    body.setContent(new byte[] {1});
    var transfer = new MockHttpServletRequest();
    transfer.addHeader("Transfer-Encoding", "chunked");
    for (var request : List.of(query, body, transfer)) {
      assertInvalid(() -> controller.source(request, "trace", 1));
      assertInvalid(() -> controller.frame(request, "trace", 1));
      assertInvalid(() -> controller.content(request, "trace", 1));
    }
    assertInvalid(() -> controller.answer(query, Map.of("question", "合成问题？", "mode", "joint")));
  }

  @Test
  void unauthenticatedRequestsCannotReadFramesOrRevealLengthThroughRange() {
    var request = new MockHttpServletRequest();
    request.addHeader("Range", "bytes=99999999999999999999-");
    assertUnauthenticated(() -> controller.source(request, "trace", 1));
    assertUnauthenticated(() -> controller.frame(request, "trace", 1));
    assertUnauthenticated(() -> controller.content(request, "trace", 1));
    assertUnauthenticated(
        () -> controller.answer(request, Map.of("question", "合成问题？", "mode", "joint")));
  }

  private static void assertInvalid(org.junit.jupiter.api.function.Executable action) {
    assertEquals("invalid_request", assertThrows(ApplicationException.class, action).code());
  }

  private static void assertUnauthenticated(org.junit.jupiter.api.function.Executable action) {
    assertEquals("unauthenticated", assertThrows(ApplicationException.class, action).code());
  }
}
